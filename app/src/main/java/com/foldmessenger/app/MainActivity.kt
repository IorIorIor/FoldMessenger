package com.foldmessenger.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Single full-screen activity. Shows a phone-picker on first run, then acts as
 * the message viewer. Launched directly by PushService (or via the notification's
 * full-screen intent) so it appears on whichever display is active.
 *
 * States: idle (baked idle image) → teaser on cover screen ("New Secret!" on the
 * baked cover background) → reveal on the unfolded screen (message text and/or
 * image/video on the baked heart background) → fold closed wipes the message.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var setupGroup: View
    private lateinit var viewerGroup: View
    private lateinit var bgImage: ImageView
    private lateinit var mediaImage: ImageView
    private lateinit var mediaVideo: VideoView
    private lateinit var coverTitle: TextView
    private lateinit var centerText: TextView
    private lateinit var messageText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // draw behind the punch-hole camera / status bar area
        window.attributes.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()

        setupGroup = findViewById(R.id.setup_group)
        viewerGroup = findViewById(R.id.viewer_group)
        bgImage = findViewById(R.id.bg_image)
        mediaImage = findViewById(R.id.media_image)
        mediaVideo = findViewById(R.id.media_video)
        coverTitle = findViewById(R.id.cover_title)
        centerText = findViewById(R.id.center_text)
        messageText = findViewById(R.id.message_text)

        bindSetupButtons()
        requestPermissionsIfNeeded()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        MessageStore.onMessageChanged = { render() }
        render()
        if (MessageStore.getPhoneId(this) > 0) {
            PushService.start(this)
            maybePromptSpecialAccess()
        }
    }

    override fun onPause() {
        MessageStore.onMessageChanged = null
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    private fun bindSetupButtons() {
        val ids = listOf(
            R.id.btn_phone_1, R.id.btn_phone_2, R.id.btn_phone_3, R.id.btn_phone_4,
            R.id.btn_phone_5, R.id.btn_phone_6, R.id.btn_phone_7, R.id.btn_phone_8
        )
        ids.forEachIndexed { index, resId ->
            findViewById<Button>(resId).setOnClickListener {
                MessageStore.setPhoneId(this, index + 1)
                PushService.start(this)
                maybePromptSpecialAccess()
                render()
            }
        }
    }

    private fun render() {
        val phoneId = MessageStore.getPhoneId(this)
        if (phoneId == 0) {
            setupGroup.visibility = View.VISIBLE
            viewerGroup.visibility = View.GONE
            return
        }
        setupGroup.visibility = View.GONE
        viewerGroup.visibility = View.VISIBLE

        val isCover = resources.configuration.smallestScreenWidthDp < 600

        // Folding closed after the reveal ends the round: wipe the message
        if (isCover && MessageStore.isViewed(this)) {
            MessageStore.clear(this)
        }

        stopVideo()
        val message = MessageStore.getLastMessage(this)
        when {
            message == null -> showIdle()
            isCover -> showTeaser()
            else -> showMessage(message)
        }
    }

    private fun showIdle() {
        bgImage.setImageResource(R.drawable.img_idle)
        mediaImage.visibility = View.GONE
        coverTitle.visibility = View.GONE
        centerText.visibility = View.GONE
        messageText.visibility = View.GONE
    }

    private fun showTeaser() {
        bgImage.setImageResource(R.drawable.bg_cover)
        coverTitle.visibility = View.VISIBLE
        mediaImage.visibility = View.GONE
        centerText.visibility = View.GONE
        messageText.visibility = View.GONE
    }

    private fun showMessage(message: MessageStore.Message) {
        bgImage.setImageResource(R.drawable.bg_main)
        coverTitle.visibility = View.GONE

        val path = message.mediaPath
        if (path != null) {
            if (message.mime.startsWith("video/")) {
                mediaImage.visibility = View.GONE
                mediaVideo.visibility = View.VISIBLE
                mediaVideo.setVideoPath(path)
                mediaVideo.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    mediaVideo.start()
                }
            } else {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    mediaImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    mediaImage.setImageBitmap(bitmap)
                    mediaImage.visibility = View.VISIBLE
                } else {
                    mediaImage.visibility = View.GONE
                }
            }
            centerText.visibility = View.GONE
            if (message.text.isNotEmpty()) {
                messageText.text = message.text
                messageText.visibility = View.VISIBLE
            } else {
                messageText.visibility = View.GONE
            }
        } else {
            mediaImage.visibility = View.GONE
            messageText.visibility = View.GONE
            centerText.text = message.text
            centerText.visibility = View.VISIBLE
        }

        MessageStore.markViewed(this)
        getSystemService(NotificationManager::class.java)
            .cancel(PushService.NOTIF_ID_MESSAGE)
    }

    private fun stopVideo() {
        if (mediaVideo.isPlaying) mediaVideo.stopPlayback()
        mediaVideo.visibility = View.GONE
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }
    }

    /**
     * One settings prompt per resume, in order: "Appear on top" (lets the service
     * launch the viewer directly on the cover screen when a message arrives),
     * then battery-optimization exemption. Each asked at most once per app run.
     */
    private fun maybePromptSpecialAccess() {
        if (!Settings.canDrawOverlays(this)) {
            if (!promptedOverlay) {
                promptedOverlay = true
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
            return
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !promptedBattery) {
            promptedBattery = true
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    companion object {
        private var promptedOverlay = false
        private var promptedBattery = false
    }
}
