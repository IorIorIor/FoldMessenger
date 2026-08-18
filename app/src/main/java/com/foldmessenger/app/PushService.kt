package com.foldmessenger.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.res.ResourcesCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import com.foldmessenger.app.Ntfy.withAuth
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foreground service holding a persistent WebSocket to ntfy.sh.
 * Subscribes to this phone's own topic + the broadcast topic. On message:
 * downloads the attachment (if any), persists it, and posts a high-priority
 * notification with a full-screen intent so MainActivity takes over the
 * active display (cover screen included) immediately.
 */
class PushService : Service() {

    companion object {
        private const val TAG = "PushService"
        private const val CHANNEL_SERVICE = "service"
        // v2: silent channel — the alert sound is played by the app itself
        private const val CHANNEL_MESSAGES = "messages_v3"
        private const val NOTIF_ID_SERVICE = 1
        const val NOTIF_ID_MESSAGE = 2

        /** Admin control message (sent by sender.html "Next round"): wipe all phones. */
        const val CMD_NEXT_ROUND = "__NEXTROUND__"

        /** Admin control message: put every phone into the closing question. */
        const val CMD_FINAL_QUESTION = "__FINALQUESTION__"

        /** Admin control message: ask every phone for a fresh selfie. */
        const val CMD_SELFIE = "__SELFIE__"

        /** Admin control message: show the secret every phone has already fetched. */
        const val CMD_REVEAL = "__REVEAL__"

        /** A shared selfie, titled "face:<table>:<seat>". */
        private val FACE_MESSAGE = Regex("^face:(\\d+):(\\d+)$")

        /** Admin control message "__TABLE__2": switch every phone to that table. */
        private val TABLE_COMMAND = Regex("^__TABLE__(\\d+)$")

        /** Admin control message "__SERVER__http://10.0.0.5:8080", or "__SERVER__off". */
        private val SERVER_COMMAND = Regex("^__SERVER__(.+)$")

        /** How often the watchdog checks that the socket is still there. */
        private const val WATCHDOG_MS = 15_000L

        /** Reconnect delay bounds; see scheduleReconnect. */
        private const val MIN_RECONNECT_MS = 1_000L
        private const val MAX_RECONNECT_MS = 5_000L

        /** Time given to the direct launch before falling back to a notification. */
        private const val VIEWER_LAUNCH_GRACE_MS = 700L

        /** Catch-up messages older than this are ignored (previous round). */
        private const val STALE_MESSAGE_SECONDS = 15 * 60

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PushService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Reconnects and watchdogs run here rather than on the main thread. The
     * shader animates continuously on main, so a timer posted there fires late
     * by however long the main thread is busy — which is exactly when a phone
     * can least afford to be slow reconnecting.
     */
    private val netThread = HandlerThread("fm-net").apply { start() }
    private val netHandler = Handler(netThread.looper)

    /** Incoming secrets, one at a time so the newest is the one left showing. */
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Attachment bytes, kept off [executor] so a slow or stalled transfer cannot
     * hold up the next command. "Next round" arriving behind a wedged 25MB
     * download would leave a phone stuck on a dead secret.
     */
    private val mediaExecutor = Executors.newSingleThreadExecutor()

    /** Update checks, kept away from the secrets so they can never delay one. */
    private val updateExecutor = Executors.newSingleThreadExecutor()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var webSocket: WebSocket? = null
    private var connectedPhoneId = 0
    private var reconnectDelayMs = MIN_RECONNECT_MS
    private var reconnectScheduled = false
    private var destroyed = false
    private var alertPlayer: MediaPlayer? = null
    private var updateCheckScheduled = false
    private var watchdogRunning = false

    /** Built once: decoding and drawing it per message cost time on every secret. */
    private var cachedTeaser: Bitmap? = null

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)   // spot a dead socket quickly
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Downloads get their own client with a hard ceiling on the whole call. The
     * socket client deliberately has no read timeout — it must sit idle for
     * hours — but a transfer that inherits that can hang for ever, which is one
     * way a single phone ends up minutes behind the rest.
     */
    private val downloadClient = client.newBuilder()
        .callTimeout(90, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        acquireLocks()
    }

    /**
     * Wi-Fi power save parks the radio between beacons, so an incoming secret
     * can sit unnoticed for tens of seconds — the phones that look "a minute
     * behind". A high-performance Wi-Fi lock and a partial wake lock keep the
     * radio and CPU available for as long as the service runs.
     */
    private fun acquireLocks() {
        try {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FoldMessenger:push")
                .apply { setReferenceCounted(false); acquire() }
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wifi?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FoldMessenger:push"
            )?.apply { setReferenceCounted(false); acquire() }
            Log.i(TAG, "Locks held: wake=${wakeLock?.isHeld}, wifi=${wifiLock?.isHeld}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not hold locks: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = serviceNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID_SERVICE, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID_SERVICE, notification)
        }
        connectIfNeeded()
        scheduleUpdateChecks()
        startWatchdog()
        return START_STICKY
    }

    /**
     * Last line of defence: if the socket is somehow down and nothing has been
     * scheduled to bring it back, reconnect. Costs nothing when all is well.
     */
    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        lateinit var tick: Runnable
        tick = Runnable {
            if (destroyed) return@Runnable
            if (webSocket == null && !reconnectScheduled) {
                Log.w(TAG, "Watchdog: socket down with no reconnect pending")
                connectIfNeeded()
            }
            netHandler.postDelayed(tick, WATCHDOG_MS)
        }
        netHandler.postDelayed(tick, WATCHDOG_MS)
    }

    /** Poll GitHub Releases for a newer build: once on start, then every 15 min. */
    private fun scheduleUpdateChecks() {
        if (updateCheckScheduled) return
        updateCheckScheduled = true
        val interval = 15L * 60 * 1000
        lateinit var tick: Runnable
        tick = Runnable {
            if (destroyed) return@Runnable
            updateExecutor.execute { checkForUpdate() }
            netHandler.postDelayed(tick, interval)
        }
        netHandler.postDelayed(tick, 10_000)
    }

    private fun checkForUpdate() {
        try {
            val apk = UpdateChecker.fetchUpdate(this, client) ?: return
            // don't interrupt a live round — retry on the next tick instead
            if (MessageStore.getLastMessage(this) != null) {
                Log.i(TAG, "Update ready, waiting for idle to prompt install")
                return
            }
            handler.post { UpdateChecker.promptInstall(this, apk) }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        destroyed = true
        webSocket?.cancel()
        executor.shutdown()
        mediaExecutor.shutdown()
        updateExecutor.shutdown()
        netThread.quitSafely()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Could not release locks: ${e.message}")
        }
        alertPlayer?.release()
        alertPlayer = null
        super.onDestroy()
    }

    private fun connectIfNeeded() {
        val phoneId = MessageStore.getPhoneId(this)
        if (phoneId == 0) return // setup not done yet; activity restarts us after
        if (webSocket != null && connectedPhoneId == phoneId) return
        webSocket?.cancel()
        connect(phoneId)
    }

    private fun connect(phoneId: Int) {
        connectedPhoneId = phoneId
        val base = Servers.resolve(this)
        val topics = "${Config.phoneTopic(phoneId)},${Config.allTopic()},${Config.facesTopic()}"
        // Resume from the last event we saw: the socket drops regularly (Wi-Fi
        // hiccups, doze), and without this anything published while it was down
        // would never arrive.
        val since = MessageStore.getLastEventTime(this)
        val query = buildList {
            if (since > 0) add("since=$since")
            if (Servers.needsAuth(base)) Ntfy.authQueryParam()?.let { add(it) }
        }.joinToString("&")
        val url = "$base/$topics/ws" + if (query.isNotEmpty()) "?$query" else ""
        Log.i(TAG, "Connecting to $base/$topics/ws (since=$since)")
        val request = Request.Builder().url(url).let {
            if (Servers.needsAuth(base)) it.withAuth() else it
        }.build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket open")
                reconnectDelayMs = MIN_RECONNECT_MS
                reconnectScheduled = false
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                executor.execute { handleEvent(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                scheduleReconnect()
            }
        })
    }

    /**
     * These sockets drop regularly, and anything published while one is down
     * only lands on reconnect — so the backoff is what a phone's lateness
     * actually costs. It is kept to a few seconds rather than the usual
     * doubling into minutes: there are six phones on a local network, so
     * reconnecting eagerly costs nothing and a minute of silence would ruin
     * the timing of a reveal.
     */
    private fun scheduleReconnect() {
        if (destroyed || reconnectScheduled) return
        reconnectScheduled = true
        webSocket = null
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_MS)
        netHandler.postDelayed({
            reconnectScheduled = false
            connectIfNeeded()
        }, delay)
    }

    private fun handleEvent(json: String) {
        // Everything downstream is measured against this: the moment the event
        // reached this phone. Times from here are elapsed on one device, so six
        // handsets with six different clocks still produce comparable numbers.
        val recvAt = System.currentTimeMillis()
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            return
        }
        if (obj.optString("event") != "message") return

        // `since` is second-granular, so the catch-up can hand back the message
        // we already showed — skip it rather than replaying the reveal.
        val eventId = obj.optString("id")
        if (eventId.isNotEmpty() && eventId == MessageStore.getLastEventId(this)) return

        val publishedAt = obj.optLong("time", 0L)
        // Remember where we got to before anything else can return early.
        if (eventId.isNotEmpty()) MessageStore.setLastEvent(this, eventId, publishedAt)

        // A catch-up burst after a long outage shouldn't resurrect an old round.
        if (publishedAt > 0 &&
            System.currentTimeMillis() / 1000 - publishedAt > STALE_MESSAGE_SECONDS
        ) {
            Log.i(TAG, "Skipping stale message from ${System.currentTimeMillis() / 1000 - publishedAt}s ago")
            return
        }

        val text = obj.optString("message", "")
        val attachment = obj.optJSONObject("attachment")
        val attachmentUrl = attachment?.optString("url")
        val title = obj.optString("title", "").trim()

        // Somebody's selfie: cache it against their seat and show nothing.
        FACE_MESSAGE.find(title)?.let { match ->
            if (!attachmentUrl.isNullOrEmpty()) {
                val table = match.groupValues[1].toInt()
                val seat = match.groupValues[2].toInt()
                // on the media thread: eighteen selfies must not delay a secret
                mediaExecutor.execute {
                    downloadBytes(attachmentUrl)?.let { bytes ->
                        Faces.save(this, table, seat, bytes)
                        Log.i(TAG, "Cached selfie for table $table seat $seat")
                        handler.post { MessageStore.onMessageChanged?.invoke() }
                    }
                }
            }
            return
        }

        if (text == CMD_SELFIE && attachment == null) {
            MessageStore.setSelfieMode(this, true)
            alertAndShow()
            return
        }

        SERVER_COMMAND.find(text)?.let { match ->
            if (attachment == null) {
                val value = match.groupValues[1].trim()
                val base = if (value.equals("off", true)) null else value.trimEnd('/')
                MessageStore.setLocalServer(this, base)
                Log.i(TAG, "Server set to ${base ?: "ntfy only"}; reconnecting")
                // come back on the new address straight away
                webSocket?.cancel()
                webSocket = null
                netHandler.post { connectIfNeeded() }
                return
            }
        }

        TABLE_COMMAND.find(text)?.let { match ->
            if (attachment == null) {
                val table = match.groupValues[1].toIntOrNull()
                if (table != null && table in 1..Config.TABLE_COUNT) {
                    Log.i(TAG, "Switching to table $table")
                    MessageStore.setActiveTable(this, table)
                }
                return
            }
        }

        if (text == CMD_FINAL_QUESTION && attachment == null) {
            MessageStore.startFinalQuestion(this)
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_MESSAGE)
            alertAndShow()
            return
        }

        if (text == CMD_NEXT_ROUND && attachment == null) {
            MessageStore.clear(this)
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_MESSAGE)
            launchViewer() // put the idle image back on the active screen
            return
        }

        // Show what was fetched earlier. Nothing is downloaded here, so every
        // phone that is listening reveals within the spread of the broadcast
        // itself rather than the spread of six downloads.
        if (text == CMD_REVEAL && attachment == null) {
            if (!MessageStore.promoteStaged(this)) {
                Log.w(TAG, "Reveal arrived with nothing staged — ignoring")
                AckSender.send(this, "shown", -1, "nothing staged")
                return
            }
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_MESSAGE)
            alertAndShow()
            Log.i(TAG, "Revealed staged secret in ${System.currentTimeMillis() - recvAt}ms")
            AckSender.send(this, "shown", System.currentTimeMillis() - recvAt)
            return
        }

        val name = attachment?.optString("name") ?: ""
        val hasMedia = !attachmentUrl.isNullOrEmpty()
        // ntfy sometimes auto-fills `message` when there is an attachment and no
        // explicit user-entered text (e.g. "clip.mp4" or "You received a file: clip.mp4").
        // If the message appears to be an auto-filled filename/placeholder or is blank,
        // treat it as empty so nothing is shown on the phone when only an asset is sent.
        val displayText = if (hasMedia && (
                text.isBlank() ||
                text == name ||
                text == "You received a file: $name" ||
                text.startsWith("You received a file:")
            )
        ) "" else text

        var mime = attachment?.optString("type") ?: ""
        if (mime.isEmpty() && listOf(".mp4", ".webm", ".mov", ".mkv", ".3gp").any {
                name.lowercase().endsWith(it)
            }
        ) {
            mime = "video/mp4"
        }

        val personId = 0 // secrets carry their own artwork; nobody is named

        // Fetch and hold. Nothing is shown and nothing sounds — this phone is
        // being loaded ahead of the round, and the reveal comes separately.
        if (title == Config.TITLE_PRELOAD) {
            val staged = if (hasMedia) downloadAttachment(attachmentUrl!!, staging = true) else null
            MessageStore.stageMessage(this, displayText, staged, mime)
            val ms = System.currentTimeMillis() - recvAt
            Log.i(TAG, "Preloaded in ${ms}ms, waiting for reveal")
            AckSender.send(this, "ready", ms, if (staged == null && hasMedia) "download failed" else "")
            return
        }

        val lagMs = if (publishedAt > 0) System.currentTimeMillis() - publishedAt * 1000 else -1
        Log.i(TAG, "Secret alerted ${lagMs}ms after it was sent" +
            (if (hasMedia) " (media still downloading)" else ""))

        // Alert first, download second. The cover screen shows a teaser that is
        // baked into the app, so the bytes are not needed to tell anyone a
        // secret has arrived — and there are seconds of human time between the
        // chime and the phone being unfolded for the picture to land in. Doing
        // it the other way round made each phone chime in proportion to its own
        // download speed, which is what pulled six of them minutes apart.
        MessageStore.saveMessage(this, displayText, null, mime, personId, mediaPending = hasMedia)
        val serial = MessageStore.getSerial(this)
        alertAndShow()
        AckSender.send(this, "shown", System.currentTimeMillis() - recvAt)

        if (hasMedia) {
            mediaExecutor.execute {
                val path = downloadAttachment(attachmentUrl!!)
                MessageStore.attachMedia(this, serial, path)
                val ms = System.currentTimeMillis() - recvAt
                Log.i(TAG, "Media ready ${ms}ms after arrival")
                AckSender.send(this, "ready", ms, if (path == null) "download failed" else "")
            }
        }
    }

    /**
     * Put the viewer on the active display and make a noise. Only skip the
     * notification when the viewer is demonstrably already on screen; otherwise
     * post it, because its full-screen intent is the only sanctioned way to
     * surface over the keyguard when the phone is folded or locked.
     */
    private fun alertAndShow() {
        launchViewer()
        handler.postDelayed({
            if (MainActivity.isOnScreen) {
                playAlert()
            } else {
                postMessageNotification()
            }
        }, VIEWER_LAUNCH_GRACE_MS)
    }

    // Audio is handled by the NotificationChannel directly (no MediaPlayer).

    /**
     * Bring MainActivity to the front on whatever display is active (cover screen
     * included). Background activity starts are only allowed with the "Appear on
     * top" (SYSTEM_ALERT_WINDOW) permission; without it the full-screen-intent
     * notification is the fallback.
     */
    private fun launchViewer(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        return try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Direct viewer launch failed: ${e.message}")
            false
        }
    }

    /** The Samsung whistle, for when no notification is posted to carry it. */
    private fun playAlert() {
        handler.post {
            try {
                alertPlayer?.release()
                alertPlayer = MediaPlayer.create(this, R.raw.alert)?.apply {
                    setOnCompletionListener { mp ->
                        mp.release()
                        if (alertPlayer == mp) alertPlayer = null
                    }
                    start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Alert sound failed: ${e.message}")
            }
        }
    }

    /** Raw bytes of an attachment, for things cached in memory rather than shown. */
    private fun downloadBytes(url: String): ByteArray? = try {
        val request = Request.Builder().url(url).withAuth().build()
        downloadClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Selfie download failed: ${e.message}")
        null
    }

    /**
     * @param staging when true the file lands in its own directory, held for a
     *   later reveal. It must not share a directory with the live message: each
     *   download wipes its own directory first, so a staged secret kept beside
     *   the live one would be deleted by the next thing to arrive.
     */
    private fun downloadAttachment(url: String, staging: Boolean = false): String? {
        val startedAt = System.currentTimeMillis()
        return try {
            val request = Request.Builder().url(url).withAuth().build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val dir = File(filesDir, if (staging) "staged" else "messages").apply { mkdirs() }
                // keep only the latest image
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "img_${System.currentTimeMillis()}")
                file.outputStream().use { out ->
                    response.body?.byteStream()?.copyTo(out)
                }
                Log.i(TAG, "Downloaded ${file.length() / 1024}KB in " +
                    "${System.currentTimeMillis() - startedAt}ms")
                file.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "Attachment download failed: ${e.message}")
            null
        }
    }

    /**
     * Fallback alert for when the direct launch isn't allowed. Deliberately shows
     * only the teaser image — never the actual message — so nothing is revealed
     * before the phone is unfolded.
     */
    private fun postMessageNotification() {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = Uri.parse("android.resource://$packageName/${R.raw.alert}")

        val builder = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            // Full-screen intent: launches MainActivity on the active display,
            // cover screen included
            .setFullScreenIntent(contentIntent, true)
            // Ensure the builder also references the sound for older platforms
            .setSound(soundUri)

        teaserBitmap()?.let { teaser ->
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(teaser)
                    .bigLargeIcon(null as Bitmap?)
            )
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID_MESSAGE, builder.build())
    }

    /** Cover background with "New Secret!" drawn in the Samsung Sharp Sans font. */
    private fun teaserBitmap(): Bitmap? {
        cachedTeaser?.let { return it }
        return buildTeaserBitmap()?.also { cachedTeaser = it }
    }

    private fun buildTeaserBitmap(): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val src = BitmapFactory.decodeResource(resources, R.drawable.bg_cover, opts)
                ?: return null
            val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
            src.recycle()
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = ResourcesCompat.getFont(
                    this@PushService, R.font.samsung_sharp_sans_bold
                )
                textSize = bmp.width * 0.16f
                textAlign = Paint.Align.CENTER
            }
            val cx = bmp.width / 2f
            val cy = bmp.height * 0.42f
            canvas.drawText("New", cx, cy, paint)
            canvas.drawText("Secret!", cx, cy + paint.textSize * 1.15f, paint)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    private fun serviceNotification(): Notification {
        val phoneId = MessageStore.getPhoneId(this)
        val label = if (phoneId > 0) {
            getString(R.string.notif_service_connected, phoneId)
        } else {
            getString(R.string.notif_service_setup)
        }
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(label)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("messages") // pre-v2 channel with default sound
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.channel_service),
                NotificationManager.IMPORTANCE_MIN
            )
        )
        // Notification channel with bundled custom sound so the OS plays it reliably.
        val soundUri = Uri.parse("android.resource://$packageName/${R.raw.alert}")
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                getString(R.string.channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(soundUri, audioAttrs)
            }
        )
    }
}
