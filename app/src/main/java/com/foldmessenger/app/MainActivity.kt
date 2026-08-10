package com.foldmessenger.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.StaticLayout
import android.util.Log
import android.util.TypedValue
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.view.WindowCompat
import androidx.core.widget.TextViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Single full-screen activity. Shows a phone-picker on first run, then acts as
 * the message viewer. Launched directly by PushService (or via the notification's
 * full-screen intent) so it appears on whichever display is active.
 *
 * States: idle (phone number) → teaser on the cover screen ("New Secret!") →
 * reveal on the unfolded screen (a centred card holding the media and/or text,
 * with the roster avatar and name along the bottom) → fold closed wipes the
 * message. Each drives a matching state on the live FxBackground behind it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var setupGroup: View
    private lateinit var viewerGroup: View
    private lateinit var fx: FxBackground
    private lateinit var cardContainer: LinearLayout
    private lateinit var mediaBlock: View
    private lateinit var mediaImage: ImageView
    private lateinit var mediaVideo: TextureView
    private lateinit var mediaScrim: View
    private lateinit var bylineMedia: View
    private lateinit var bylineMediaAvatar: ImageView
    private lateinit var bylineMediaName: TextView
    private lateinit var cardText: TextView
    private lateinit var bylineCard: View
    private lateinit var bylineCardAvatar: ImageView
    private lateinit var bylineCardName: TextView
    private lateinit var coverTitle: TextView
    private lateinit var idleLabel: TextView

    private var player: MediaPlayer? = null
    private var pendingVideoPath: String? = null

    // Card sizing bounds, derived from the display the activity is currently on.
    private val maxCardWidth: Int
        get() = (resources.displayMetrics.widthPixels * 0.72f).toInt()
    private val maxMediaWidth: Int
        get() = (resources.displayMetrics.widthPixels * 0.55f).toInt()

    /** Height budget for the media, set per message before showMedia() runs. */
    private var maxMediaHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Lock orientation to portrait for a consistent UI
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
        fx = FxBackground(findViewById(R.id.fx_background))
        cardContainer = findViewById(R.id.card_container)
        mediaBlock = findViewById(R.id.media_block)
        mediaImage = findViewById(R.id.media_image)
        mediaVideo = findViewById(R.id.media_video)
        mediaScrim = findViewById(R.id.media_scrim)
        bylineMedia = findViewById(R.id.byline_media)
        bylineMediaAvatar = findViewById(R.id.byline_media_avatar)
        bylineMediaName = findViewById(R.id.byline_media_name)
        cardText = findViewById(R.id.card_text)
        bylineCard = findViewById(R.id.byline_card)
        bylineCardAvatar = findViewById(R.id.byline_card_avatar)
        bylineCardName = findViewById(R.id.byline_card_name)
        coverTitle = findViewById(R.id.cover_title)
        idleLabel = findViewById(R.id.idle_label)

        applyCardCorners()
        bindSetupButtons()
        requestPermissionsIfNeeded()
    }

    /** Rounded card, with the media clipped to the same corners. */
    private fun applyCardCorners() {
        val radius = resources.getDimension(R.dimen.card_corner_radius)
        cardContainer.background = CardBackground(radius)
        cardContainer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        cardContainer.clipToOutline = true
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
        fx.resume()
        MessageStore.onMessageChanged = { render() }
        render()
        if (MessageStore.getPhoneId(this) > 0) {
            PushService.start(this)
            maybePromptSpecialAccess()
        }
    }

    override fun onPause() {
        MessageStore.onMessageChanged = null
        releasePlayer()
        fx.pause()
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
            message == null -> showIdle(phoneId)
            isCover -> showTeaser()
            else -> showMessage(message)
        }
    }

    private fun showIdle(phoneId: Int) {
        fx.show(FxBackground.IDLE)
        cardContainer.visibility = View.GONE
        coverTitle.visibility = View.GONE
        // the allocated phone number, so the crew can tell the handsets apart
        idleLabel.text = getString(R.string.phone_label, phoneId)
        idleLabel.visibility = View.VISIBLE
    }

    private fun showTeaser() {
        fx.show(FxBackground.NEW_REVEAL)
        cardContainer.visibility = View.GONE
        idleLabel.visibility = View.GONE
        coverTitle.visibility = View.VISIBLE
    }

    private fun showMessage(message: MessageStore.Message) {
        coverTitle.visibility = View.GONE
        idleLabel.visibility = View.GONE

        val person = Roster.byId(this, message.personId)
        val hasMedia = message.mediaPath != null
        val hasCaption = message.text.isNotEmpty()
        // a photo/clip gets the wider sliced aura; a text-only secret its own state
        fx.show(if (hasMedia) FxBackground.MEDIA_MESSAGE else FxBackground.TEXT_MESSAGE)
        val pad = resources.getDimensionPixelSize(R.dimen.card_padding)
        val screenH = resources.displayMetrics.heightPixels

        // The media gets whatever height the caption leaves over, so the whole
        // card always fits on screen.
        maxMediaHeight = if (hasCaption) {
            (screenH * 0.88f).toInt() - measureCaptionHeight(message.text) - 2 * pad
        } else {
            (screenH * 0.78f).toInt()
        }.coerceAtLeast((screenH * 0.25f).toInt())

        if (hasMedia) {
            showMedia(message.mediaPath!!, message.mime)
            // the byline sits over the bottom of the photo, as in the design
            bindByline(person, bylineMedia, bylineMediaAvatar, bylineMediaName)
            mediaScrim.visibility = if (person != null) View.VISIBLE else View.GONE
            bylineCard.visibility = View.GONE
        } else {
            mediaBlock.visibility = View.GONE
            // …and under the text when there is no photo to sit on
            bindByline(person, bylineCard, bylineCardAvatar, bylineCardName)
        }

        // Text is the whole card on its own, or an optional caption under the media.
        if (hasCaption) {
            // Under media the caption spans the full card width (media centres
            // above it); on its own it wraps to hug its own lines.
            cardText.layoutParams = cardText.layoutParams.apply {
                width = if (hasMedia) maxCardWidth else ViewGroup.LayoutParams.WRAP_CONTENT
            }
            cardText.maxWidth = maxCardWidth - 2 * pad
            // as a caption it is the card's last element, so it needs its own
            // bottom padding; text-only cards get that from the byline row below
            cardText.setPadding(pad, pad, pad, if (hasMedia) pad else 0)
            cardText.text = message.text
            cardText.visibility = View.VISIBLE
        } else {
            cardText.visibility = View.GONE
        }

        cardContainer.visibility = View.VISIBLE
        MessageStore.markViewed(this)
        getSystemService(NotificationManager::class.java)
            .cancel(PushService.NOTIF_ID_MESSAGE)
    }

    /** Height the caption will take at full card width, measured with its own paint. */
    private fun measureCaptionHeight(text: String): Int {
        val pad = resources.getDimensionPixelSize(R.dimen.card_padding)
        val width = (maxCardWidth - 2 * pad).coerceAtLeast(1)
        return StaticLayout.Builder
            .obtain(text, 0, text.length, cardText.paint, width)
            .setLineSpacing(0f, 1.15f)
            .build()
            .height
    }

    private fun showMedia(path: String, mime: String) {
        mediaBlock.visibility = View.VISIBLE
        if (mime.startsWith("video/")) {
            mediaImage.visibility = View.GONE
            playVideo(path)
        } else {
            mediaVideo.visibility = View.GONE
            val bitmap = decodeScaled(path, maxMediaWidth, maxMediaHeight)
            if (bitmap != null) {
                sizeMediaBlock(bitmap.width, bitmap.height)
                mediaImage.setImageBitmap(bitmap)
                mediaImage.visibility = View.VISIBLE
            } else {
                mediaImage.visibility = View.GONE
                mediaBlock.visibility = View.GONE
            }
        }
    }

    /**
     * Give the media frame the exact fitted size of its content. The scrim and
     * byline inside it use match_parent, so without an explicit size they would
     * stretch the frame — and the whole card — to full screen width.
     */
    private fun sizeMediaBlock(contentWidth: Int, contentHeight: Int) {
        if (contentWidth <= 0 || contentHeight <= 0) return
        val scale = minOf(
            maxMediaWidth / contentWidth.toFloat(),
            maxMediaHeight / contentHeight.toFloat()
        )
        mediaBlock.layoutParams = mediaBlock.layoutParams.apply {
            width = (contentWidth * scale).toInt()
            height = (contentHeight * scale).toInt()
        }
    }

    private fun bindByline(
        person: Roster.Person?,
        row: View,
        avatar: ImageView,
        name: TextView
    ) {
        if (person == null) {
            row.visibility = View.GONE
            return
        }
        name.text = person.name
        // keep the name on one line even on a narrow photo
        name.isSingleLine = true
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            name, 12,
            (resources.getDimension(R.dimen.name_text_size) /
                resources.displayMetrics.scaledDensity).toInt().coerceAtLeast(13),
            1, TypedValue.COMPLEX_UNIT_SP
        )
        avatar.setImageDrawable(circularAvatar(person.avatarRes))
        row.visibility = View.VISIBLE
    }

    /** Centre-cropped circular avatar, so non-square photos still look right. */
    private fun circularAvatar(avatarRes: Int): Drawable? {
        if (avatarRes == 0) return null
        val src = BitmapFactory.decodeResource(resources, avatarRes) ?: return null
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(
            src, (src.width - side) / 2, (src.height - side) / 2, side, side
        )
        return RoundedBitmapDrawableFactory.create(resources, square)
            .apply { isCircular = true }
    }

    private fun decodeScaled(path: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxWidth * 2 ||
                bounds.outHeight / sample > maxHeight * 2
            ) {
                sample *= 2
            }
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            Log.w(TAG, "Image decode failed: ${e.message}")
            null
        }
    }

    // ---- video -------------------------------------------------------------
    // A TextureView (rather than VideoView) so playback is clipped to the card's
    // rounded corners; VideoView's SurfaceView ignores the parent outline.

    private fun playVideo(path: String) {
        pendingVideoPath = path
        mediaVideo.visibility = View.VISIBLE
        val texture = mediaVideo.surfaceTexture
        if (mediaVideo.isAvailable && texture != null) {
            startPlayer(path, Surface(texture))
        } else {
            mediaVideo.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    pendingVideoPath?.let { startPlayer(it, Surface(st)) }
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    private fun startPlayer(path: String, surface: Surface) {
        releasePlayer()
        try {
            player = MediaPlayer().apply {
                setSurface(surface)
                setDataSource(path)
                isLooping = true
                setOnVideoSizeChangedListener { _, w, h -> sizeVideo(w, h) }
                setOnPreparedListener { mp ->
                    sizeVideo(mp.videoWidth, mp.videoHeight)
                    mp.start()
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "Video playback error $what/$extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Video setup failed: ${e.message}")
            mediaVideo.visibility = View.GONE
        }
    }

    /** Fit the video inside the card bounds, preserving its aspect ratio. */
    private fun sizeVideo(videoWidth: Int, videoHeight: Int) {
        sizeMediaBlock(videoWidth, videoHeight)
    }

    private fun stopVideo() {
        releasePlayer()
        mediaVideo.visibility = View.GONE
    }

    private fun releasePlayer() {
        pendingVideoPath = null
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (e: IllegalStateException) {
                // player was never prepared; nothing to stop
            }
            it.release()
        }
        player = null
    }

    // ---- permissions -------------------------------------------------------

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
        private const val TAG = "MainActivity"
        private var promptedOverlay = false
        private var promptedBattery = false
    }
}
