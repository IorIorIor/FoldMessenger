'use strict';

/**
 * A local stand-in for ntfy.sh.
 *
 * The phones already speak a small slice of ntfy's protocol, so rather than
 * changing the app this serves that same slice from the laptop:
 *
 *   GET  /<topic>[,<topic>...]/ws?since=<unix>   WebSocket; back-fills, then streams
 *   GET  /<topic>/json?poll=1&since=<unix>       one-shot catch-up (the sender's votes)
 *   POST /<topic>                                publish text        (X-Title)
 *   PUT  /<topic>                                publish attachment  (X-Filename, X-Title, X-Message)
 *   GET  /file/<id><ext>                         download an attachment
 *   GET  /health                                 is the laptop up?
 *
 * Everything is held in memory and on disk under ./data — a night's worth of
 * secrets is nothing, and starting fresh each event is a feature.
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT || 8080);
const DATA_DIR = path.join(__dirname, 'data');
const KEEP_MESSAGES = 500;

fs.mkdirSync(DATA_DIR, { recursive: true });

/** @type {Map<string, Array<object>>} topic -> messages, oldest first */
const history = new Map();
/** @type {Set<{socket: import('ws').WebSocket, topics: string[]}>} */
const subscribers = new Set();

const nowSeconds = () => Math.floor(Date.now() / 1000);
const newId = () => crypto.randomBytes(6).toString('base64url');

function remember(topic, message) {
  const list = history.get(topic) || [];
  list.push(message);
  if (list.length > KEEP_MESSAGES) list.splice(0, list.length - KEEP_MESSAGES);
  history.set(topic, list);
}

function since(topic, sinceValue) {
  const list = history.get(topic) || [];
  if (!sinceValue) return [];
  // ntfy takes a unix timestamp or a duration like "6h"; both are used here
  const duration = /^(\d+)([smhd])$/.exec(String(sinceValue));
  if (duration) {
    const scale = { s: 1, m: 60, h: 3600, d: 86400 }[duration[2]];
    const from = nowSeconds() - Number(duration[1]) * scale;
    return list.filter(m => m.time >= from);
  }
  const from = Number(sinceValue);
  return Number.isFinite(from) ? list.filter(m => m.time >= from) : [];
}

/** Hand a message to everyone listening to its topic, right now. */
function fanOut(message) {
  for (const sub of subscribers) {
    if (!sub.topics.includes(message.topic)) continue;
    if (sub.socket.readyState !== sub.socket.OPEN) continue;
    try {
      sub.socket.send(JSON.stringify(message));
    } catch (err) {
      console.warn('  ! could not reach a subscriber:', err.message);
    }
  }
}

function publish(topic, { text, title, file, filename }) {
  const message = {
    id: newId(),
    time: nowSeconds(),
    event: 'message',
    topic,
    message: text || '',
  };
  if (title) message.title = title;

  if (file && file.length) {
    const ext = path.extname(filename || '') || '.bin';
    const stored = `${message.id}${ext}`;
    fs.writeFileSync(path.join(DATA_DIR, stored), file);
    message.attachment = {
      name: filename || stored,
      type: mimeFor(ext),
      size: file.length,
      url: `${publicBase()}/file/${stored}`,
    };
    if (!message.message) message.message = message.attachment.name;
  }

  remember(topic, message);
  fanOut(message);
  console.log(`  → ${topic}  ${message.attachment ? `[${Math.round(message.attachment.size / 1024)}KB] ` : ''}${(message.message || '').slice(0, 60)}`);
  return message;
}

function mimeFor(ext) {
  const types = {
    '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png',
    '.gif': 'image/gif', '.webp': 'image/webp',
    '.mp4': 'video/mp4', '.webm': 'video/webm', '.mov': 'video/quicktime',
  };
  return types[ext.toLowerCase()] || 'application/octet-stream';
}

/** The address the phones should use to fetch attachments. */
function publicBase() {
  return `http://${lanAddress()}:${PORT}`;
}

function lanAddress() {
  for (const entries of Object.values(os.networkInterfaces())) {
    for (const entry of entries || []) {
      if (entry.family === 'IPv4' && !entry.internal) return entry.address;
    }
  }
  return '127.0.0.1';
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

/**
 * ntfy allows non-ASCII headers to be RFC 2047 encoded, and the sender chunks
 * long text into several encoded-words. Only the whitespace *between* adjacent
 * encoded-words is a separator to be dropped — whitespace inside a word is part
 * of the message and has to survive.
 */
function decodeHeader(value) {
  if (!value) return '';
  return value
    .replace(/\?=\s+=\?/g, '?==?')
    .replace(/=\?UTF-8\?B\?([^?]*)\?=/gi, (_, b64) =>
      Buffer.from(b64, 'base64').toString('utf8')
    );
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const send = (code, body, headers = {}) => {
    res.writeHead(code, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': '*',
      'Access-Control-Allow-Methods': 'GET,POST,PUT,OPTIONS',
      ...headers,
    });
    res.end(body);
  };

  if (req.method === 'OPTIONS') return send(204, '');

  // the admin page, served from the laptop so it knows its own address
  if (url.pathname === '/' || url.pathname === '/sender.html') {
    const page = path.join(__dirname, '..', 'sender.html');
    if (!fs.existsSync(page)) return send(404, 'sender.html not found next to server/');
    return send(200, fs.readFileSync(page), { 'Content-Type': 'text/html; charset=utf-8' });
  }

  if (url.pathname === '/health') {
    return send(200, JSON.stringify({ ok: true, base: publicBase() }),
      { 'Content-Type': 'application/json' });
  }

  // attachments
  if (url.pathname.startsWith('/file/')) {
    const file = path.join(DATA_DIR, path.basename(url.pathname));
    if (!fs.existsSync(file)) return send(404, 'not found');
    return send(200, fs.readFileSync(file), { 'Content-Type': mimeFor(path.extname(file)) });
  }

  const parts = url.pathname.split('/').filter(Boolean);

  // catch-up poll: /<topic>/json?poll=1&since=...
  if (req.method === 'GET' && parts.length === 2 && parts[1] === 'json') {
    const lines = since(parts[0], url.searchParams.get('since'))
      .map(m => JSON.stringify(m)).join('\n');
    return send(200, lines ? lines + '\n' : '', { 'Content-Type': 'application/x-ndjson' });
  }

  // publish
  if ((req.method === 'POST' || req.method === 'PUT') && parts.length === 1) {
    const topic = parts[0];
    const body = await readBody(req);
    const title = decodeHeader(req.headers['x-title']);
    if (req.method === 'PUT') {
      publish(topic, {
        file: body,
        filename: req.headers['x-filename'] || 'file.bin',
        title,
        text: decodeHeader(req.headers['x-message']),
      });
    } else {
      publish(topic, { text: body.toString('utf8'), title });
    }
    return send(200, JSON.stringify({ ok: true }), { 'Content-Type': 'application/json' });
  }

  send(404, 'not found');
});

// subscriptions: /<topic1>,<topic2>/ws?since=...
const wss = new WebSocketServer({ noServer: true });
server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://localhost');
  const parts = url.pathname.split('/').filter(Boolean);
  if (parts.length !== 2 || parts[1] !== 'ws') return socket.destroy();
  const topics = parts[0].split(',').filter(Boolean);

  wss.handleUpgrade(req, socket, head, ws => {
    const sub = { socket: ws, topics };
    subscribers.add(sub);
    console.log(`  + phone connected [${topics.join(', ')}]  (${subscribers.size} online)`);

    // anything published while this phone was away
    const sinceValue = url.searchParams.get('since');
    if (sinceValue) {
      const missed = topics.flatMap(t => since(t, sinceValue)).sort((a, b) => a.time - b.time);
      for (const m of missed) ws.send(JSON.stringify(m));
      if (missed.length) console.log(`    caught it up on ${missed.length} message(s)`);
    }

    ws.on('close', () => {
      subscribers.delete(sub);
      console.log(`  - phone disconnected  (${subscribers.size} online)`);
    });
    ws.on('error', () => subscribers.delete(sub));
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('');
  console.log('  Fold Messenger — local server');
  console.log(`  listening on ${publicBase()}`);
  console.log('');
  console.log('  Point the phones and sender at that address.');
  console.log('  Keep this laptop awake and on the same Wi-Fi as the phones.');
  console.log('');
});
