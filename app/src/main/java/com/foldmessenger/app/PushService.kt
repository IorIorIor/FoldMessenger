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
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

        /** A shared selfie, titled "face:<table>:<seat>". */
        private val FACE_MESSAGE = Regex("^face:(\\d+):(\\d+)$")

        /** Admin control message "__TABLE__2": switch every phone to that table. */
        private val TABLE_COMMAND = Regex("^__TABLE__(\\d+)$")

        /** Time given to the direct launch before falling back to a notification. */
        private const val VIEWER_LAUNCH_GRACE_MS = 700L

        /** Catch-up messages older than this are ignored (previous round). */
        private const val STALE_MESSAGE_SECONDS = 15 * 60

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PushService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var webSocket: WebSocket? = null
    private var connectedPhoneId = 0
    private var reconnectDelayMs = 5_000L
    private var destroyed = false
    private var alertPlayer: MediaPlayer? = null
    private var updateCheckScheduled = false

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
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
        return START_STICKY
    }

    /** Poll GitHub Releases for a newer build: once on start, then every 15 min. */
    private fun scheduleUpdateChecks() {
        if (updateCheckScheduled) return
        updateCheckScheduled = true
        val interval = 15L * 60 * 1000
        lateinit var tick: Runnable
        tick = Runnable {
            if (destroyed) return@Runnable
            executor.execute { checkForUpdate() }
            handler.postDelayed(tick, interval)
        }
        handler.postDelayed(tick, 10_000)
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
        val topics = "${Config.phoneTopic(phoneId)},${Config.allTopic()},${Config.facesTopic()}"
        // Resume from the last event we saw: the socket drops regularly (Wi-Fi
        // hiccups, doze), and without this anything published while it was down
        // would never arrive.
        val since = MessageStore.getLastEventTime(this)
        val query = buildList {
            if (since > 0) add("since=$since")
            Ntfy.authQueryParam()?.let { add(it) }
        }.joinToString("&")
        val url = "${Config.NTFY_SERVER}/$topics/ws" + if (query.isNotEmpty()) "?$query" else ""
        Log.i(TAG, "Connecting to ${Config.NTFY_SERVER}/$topics/ws (since=$since, auth=${Ntfy.hasToken})")
        val request = Request.Builder().url(url).withAuth().build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket open")
                reconnectDelayMs = 5_000L
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

    private fun scheduleReconnect() {
        if (destroyed) return
        webSocket = null
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(60_000L)
        handler.postDelayed({ connectIfNeeded() }, delay)
    }

    private fun handleEvent(json: String) {
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
                downloadBytes(attachmentUrl)?.let { bytes ->
                    Faces.save(this, table, seat, bytes)
                    Log.i(TAG, "Cached selfie for table $table seat $seat")
                    handler.post { MessageStore.onMessageChanged?.invoke() }
                }
            }
            return
        }

        if (text == CMD_SELFIE && attachment == null) {
            MessageStore.setSelfieMode(this, true)
            launchViewer()
            handler.postDelayed({
                if (MainActivity.isOnScreen) playAlert() else postMessageNotification()
            }, VIEWER_LAUNCH_GRACE_MS)
            return
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
            launchViewer()
            handler.postDelayed({
                if (MainActivity.isOnScreen) playAlert() else postMessageNotification()
            }, VIEWER_LAUNCH_GRACE_MS)
            return
        }

        if (text == CMD_NEXT_ROUND && attachment == null) {
            MessageStore.clear(this)
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_MESSAGE)
            launchViewer() // put the idle image back on the active screen
            return
        }

        var mediaPath: String? = null
        if (!attachmentUrl.isNullOrEmpty()) {
            mediaPath = downloadAttachment(attachmentUrl)
        }
        val name = attachment?.optString("name") ?: ""
        // ntfy sometimes auto-fills `message` when there is an attachment and no
        // explicit user-entered text (e.g. "clip.mp4" or "You received a file: clip.mp4").
        // If the message appears to be an auto-filled filename/placeholder or is blank,
        // treat it as empty so nothing is shown on the phone when only an asset is sent.
        val displayText = if (mediaPath != null && (
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

        MessageStore.saveMessage(this, displayText, mediaPath, mime, personId)
        // Only skip the notification when the viewer is demonstrably already on
        // screen; otherwise post it, because its full-screen intent is the only
        // sanctioned way to surface over the keyguard when the phone is folded
        // or locked.
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
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Selfie download failed: ${e.message}")
        null
    }

    private fun downloadAttachment(url: String): String? {
        return try {
            val request = Request.Builder().url(url).withAuth().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val dir = File(filesDir, "messages").apply { mkdirs() }
                // keep only the latest image
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "img_${System.currentTimeMillis()}")
                file.outputStream().use { out ->
                    response.body?.byteStream()?.copyTo(out)
                }
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
