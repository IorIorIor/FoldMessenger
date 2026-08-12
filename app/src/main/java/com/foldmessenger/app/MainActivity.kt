package com.foldmessenger.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var cardText: TextView
    private lateinit var coverTitle: TextView
    private lateinit var idleLabel: TextView
    private lateinit var finalGroup: View
    private lateinit var finalQuestionText: TextView
    private lateinit var voteGrid: GridLayout
    private lateinit var doneButton: Button
    private lateinit var finalThanks: TextView
    private lateinit var setupTitle: TextView

    private var player: MediaPlayer? = null
    private var pendingVideoPath: String? = null
    private var cardBackground: CardBackground? = null
    private var titleTyper: ValueAnimator? = null
    private var showingTeaser = false
    private var showingReveal = false
    private var showingFinal = false
    private val picked = linkedSetOf<Int>()

    // Card sizing bounds, derived from the display the activity is currently on.
    private val maxCardWidth: Int
        get() = (resources.displayMetrics.widthPixels * 0.88f).toInt()

    // The artwork already carries its own text and byline, so it is shown on its
    // own at roughly 60% of the screen, floating on the shader.
    private val maxMediaWidth: Int
        get() = (resources.displayMetrics.widthPixels * MEDIA_SCREEN_FRACTION).toInt()

    /** Height budget for the media, set per message before showMedia() runs. */
    private var maxMediaHeight = 0

    /** Intrinsic size of the current photo/clip, for re-fitting after a re-budget. */
    private var mediaNaturalWidth = 0
    private var mediaNaturalHeight = 0

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
        cardText = findViewById(R.id.card_text)
        coverTitle = findViewById(R.id.cover_title)
        idleLabel = findViewById(R.id.idle_label)
        finalGroup = findViewById(R.id.final_group)
        finalQuestionText = findViewById(R.id.final_question)
        voteGrid = findViewById(R.id.vote_grid)
        doneButton = findViewById(R.id.btn_done)
        finalThanks = findViewById(R.id.final_thanks)
        setupTitle = findViewById(R.id.setup_title)

        applyCardCorners()
        bindSetupButtons()
        requestPermissionsIfNeeded()
    }

    /** Rounded card, with the media clipped to the same corners. */
    private fun applyCardCorners() {
        val radius = resources.getDimension(R.dimen.card_corner_radius)
        cardBackground = CardBackground(radius)
        cardContainer.background = cardBackground
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
        isOnScreen = true
        fx.resume()
        MessageStore.onMessageChanged = { render() }
        render()
        if (MessageStore.getPhoneId(this) > 0) {
            PushService.start(this)
            maybePromptSpecialAccess()
        }
    }

    override fun onPause() {
        isOnScreen = false
        // re-arm, so returning to the teaser types it out again from the start
        stopTypingTitle()
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
        val grid = findViewById<GridLayout>(R.id.phone_grid)
        grid.removeAllViews()
        setupTitle.setText(R.string.setup_title)
        for (phoneId in 1..Config.PHONE_COUNT) {
            val button = layoutInflater
                .inflate(R.layout.view_phone_button, grid, false) as Button
            button.text = phoneId.toString()
            button.setOnClickListener {
                MessageStore.setPhoneId(this, phoneId)
                PushService.start(this)
                maybePromptSpecialAccess()
                render()
            }
            grid.addView(button)
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

        if (MessageStore.isFinalQuestion(this)) {
            showFinalQuestion(isCover)
            return
        }
        finalGroup.visibility = View.GONE

        // Folding closed after the reveal ends the round: wipe the message
        if (isCover && MessageStore.isViewed(this)) {
            MessageStore.clear(this)
        }

        showingFinal = false
        stopVideo()
        val message = MessageStore.getLastMessage(this)
        when {
            message == null -> showIdle(phoneId)
            isCover -> showTeaser()
            else -> showMessage(message)
        }
    }

    private fun showIdle(phoneId: Int) {
        showingReveal = false
        stopTypingTitle()
        fx.show(FxBackground.IDLE)
        cardContainer.visibility = View.GONE
        coverTitle.visibility = View.GONE
        // the allocated phone number, so the crew can tell the handsets apart
        idleLabel.text = getString(R.string.phone_label, phoneId)
        idleLabel.visibility = View.VISIBLE
    }

    private fun showTeaser() {
        showingReveal = false
        fx.show(FxBackground.NEW_REVEAL)
        cardContainer.visibility = View.GONE
        idleLabel.visibility = View.GONE
        coverTitle.visibility = View.VISIBLE
        // only on arrival at the teaser — a re-render (fold, resume) shouldn't
        // restart the typing
        if (!showingTeaser) {
            showingTeaser = true
            typeTitle(getString(R.string.cover_title))
        }
    }

    /**
     * Reveal "New Secret!" a letter at a time. The full string is laid out from
     * the start and the untyped tail is painted transparent, so the line breaks
     * and the block's position never shift while it types.
     */
    private fun typeTitle(full: String) {
        titleTyper?.cancel()
        fitCoverTitle(full)
        // Each letter runs its own short fade, staggered by MS_PER_LETTER, so the
        // line appears to be written rather than switched on a character at a
        // time. The whole string is laid out from the start and the not-yet-faded
        // letters are drawn fully transparent, so nothing reflows while it runs.
        val total = full.length * MS_PER_LETTER + LETTER_FADE_MS
        titleTyper = ValueAnimator.ofFloat(0f, total.toFloat()).apply {
            duration = total
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val elapsed = anim.animatedValue as Float
                val spanned = SpannableString(full)
                for (i in full.indices) {
                    if (full[i].isWhitespace()) continue
                    val progress =
                        ((elapsed - i * MS_PER_LETTER) / LETTER_FADE_MS).coerceIn(0f, 1f)
                    spanned.setSpan(
                        ForegroundColorSpan(
                            Color.argb((progress * 255).toInt(), 255, 255, 255)
                        ),
                        i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                coverTitle.text = spanned
            }
            start()
        }
    }

    private fun stopTypingTitle() {
        titleTyper?.cancel()
        titleTyper = null
        showingTeaser = false
        coverTitle.text = if (MessageStore.isFinalQuestion(this)) {
            getString(R.string.cover_unfold)
        } else {
            getString(R.string.cover_title)
        }
    }

    private fun showMessage(message: MessageStore.Message) {
        stopTypingTitle()
        coverTitle.visibility = View.GONE
        idleLabel.visibility = View.GONE
        finalGroup.visibility = View.GONE

        val hasMedia = message.mediaPath != null
        fx.show(if (hasMedia) FxBackground.MEDIA_MESSAGE else FxBackground.TEXT_MESSAGE)

        // Secrets are pre-made artwork, so a photo or clip is shown on its own —
        // no card behind it, nothing overlaid. The card only survives as a
        // fallback for a plain-text message.
        val hasCaption = message.text.isNotEmpty()
        val pad = resources.getDimensionPixelSize(R.dimen.card_padding)
        val screenH = resources.displayMetrics.heightPixels

        if (hasMedia) {
            // Bare artwork floats on the shader; add a caption and it needs the
            // card behind it again so the words have something to sit on.
            cardContainer.background = if (hasCaption) cardBackground else null
            if (hasCaption) {
                cardText.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    fitTextSize(message.text, maxCardWidth - 2 * pad, (screenH * 0.25f).toInt())
                )
                cardText.layoutParams = cardText.layoutParams.apply { width = maxCardWidth }
                cardText.maxWidth = maxCardWidth - 2 * pad
                cardText.setPadding(pad, pad, pad, pad)
                cardText.text = message.text
                cardText.visibility = View.VISIBLE
                maxMediaHeight = (screenH * MEDIA_SCREEN_FRACTION).toInt() -
                    measureCaptionHeight(message.text, maxCardWidth) - 2 * pad
            } else {
                cardText.visibility = View.GONE
                maxMediaHeight = (screenH * MEDIA_SCREEN_FRACTION).toInt()
            }
            maxMediaHeight = maxMediaHeight.coerceAtLeast((screenH * 0.2f).toInt())
            showMedia(message.mediaPath!!, message.mime)
        } else {
            cardContainer.background = cardBackground
            mediaBlock.visibility = View.GONE
            cardText.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                fitTextSize(message.text, maxCardWidth - 2 * pad, (screenH * 0.80f).toInt())
            )
            cardText.layoutParams = cardText.layoutParams.apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            cardText.maxWidth = maxCardWidth - 2 * pad
            cardText.setPadding(pad, pad, pad, pad)
            cardText.text = message.text
            cardText.visibility = View.VISIBLE
        }

        cardContainer.visibility = View.VISIBLE
        if (!showingReveal) {
            showingReveal = true
            cardContainer.alpha = 0f
            cardContainer.animate()
                .alpha(1f)
                .setDuration(REVEAL_FADE_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        MessageStore.markViewed(this)
        getSystemService(NotificationManager::class.java)
            .cancel(PushService.NOTIF_ID_MESSAGE)
    }

    /** Room the avatar + name row needs under a text-only secret. */
    private fun bylineHeight(): Int =
        resources.getDimensionPixelSize(R.dimen.avatar_size) +
            2 * resources.getDimensionPixelSize(R.dimen.card_padding)

    /**
     * Largest type size (in px, capped at card_text_size) at which the text still
     * fits the given box. Steps down in 5% increments rather than binary-searching
     * because the range is small and this keeps the sizes predictable.
     */
    private fun fitTextSize(text: String, width: Int, maxHeight: Int): Float {
        val maxSize = resources.getDimension(R.dimen.card_text_size)
        val minSize = maxSize * 0.35f
        val step = maxSize * 0.05f
        val paint = TextPaint(cardText.paint)
        var size = maxSize
        while (size > minSize) {
            paint.textSize = size
            val height = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
                .setLineSpacing(0f, 1.15f)
                .build()
                .height
            if (height <= maxHeight) break
            size -= step
        }
        return size
    }

    /**
     * The teaser is set large on purpose, but "It’s time to unfold…" is a longer
     * line than "New Secret!" and overruns a cover screen at that size. Step the
     * type down only as far as the widest line needs, so each string is as big
     * as it can be without wrapping.
     */
    private fun fitCoverTitle(text: String) {
        val maxSize = resources.getDimension(R.dimen.cover_title_size)
        val available = resources.displayMetrics.widthPixels * 0.92f
        val paint = TextPaint(coverTitle.paint)
        var size = maxSize
        while (size > maxSize * 0.5f) {
            paint.textSize = size
            val widest = text.split("\n").maxOf { paint.measureText(it) }
            if (widest <= available) break
            size -= maxSize * 0.04f
        }
        coverTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    /** Screen height left for the media once the caption has taken its share. */
    private fun mediaHeightBudget(text: String, hasCaption: Boolean, captionWidth: Int): Int {
        val pad = resources.getDimensionPixelSize(R.dimen.card_padding)
        val screenH = resources.displayMetrics.heightPixels
        val budget = if (hasCaption) {
            (screenH * 0.92f).toInt() - measureCaptionHeight(text, captionWidth) - 2 * pad
        } else {
            (screenH * 0.88f).toInt()
        }
        return budget.coerceAtLeast((screenH * 0.25f).toInt())
    }

    /** Height the caption will take at the given card width, using its own paint. */
    private fun measureCaptionHeight(text: String, cardWidth: Int): Int {
        val pad = resources.getDimensionPixelSize(R.dimen.card_padding)
        val width = (cardWidth - 2 * pad).coerceAtLeast(1)
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
                mediaNaturalWidth = bitmap.width
                mediaNaturalHeight = bitmap.height
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


    // ---- closing question ---------------------------------------------------

    /**
     * Folded: a typed "It's time to unfold…" on the cover. Open: the question
     * and the other daters' faces to choose from.
     */
    private fun showFinalQuestion(isCover: Boolean) {
        stopTypingTitle()
        cardContainer.visibility = View.GONE
        idleLabel.visibility = View.GONE
        showingReveal = false

        if (isCover) {
            fx.show(FxBackground.NEW_REVEAL)
            finalGroup.visibility = View.GONE
            coverTitle.visibility = View.VISIBLE
            if (!showingTeaser) {
                showingTeaser = true
                typeTitle(getString(R.string.cover_unfold))
            }
            return
        }

        coverTitle.visibility = View.GONE
        fx.show(FxBackground.TEXT_MESSAGE)

        if (!showingFinal) {
            showingFinal = true
            picked.clear()
            buildVoteGrid()
            finalQuestionText.setText(R.string.final_question)
            finalQuestionText.visibility = View.VISIBLE
            voteGrid.visibility = View.VISIBLE
            doneButton.visibility = View.VISIBLE
            doneButton.isEnabled = true
            finalThanks.visibility = View.GONE
            finalGroup.visibility = View.VISIBLE
            finalGroup.alpha = 0f
            finalGroup.animate()
                .alpha(1f)
                .setDuration(REVEAL_FADE_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            finalGroup.visibility = View.VISIBLE
        }
    }

    /** Everyone at the table in play, except whoever is holding this phone. */
    private fun buildVoteGrid() {
        val table = MessageStore.getActiveTable(this)
        val ownSeat = MessageStore.getPhoneId(this)
        voteGrid.removeAllViews()
        Tables.players(this, table).filter { it.seat != ownSeat }.forEach { person ->
            val cell = layoutInflater.inflate(R.layout.view_vote_avatar, voteGrid, false)
            val avatar = cell.findViewById<ImageView>(R.id.vote_avatar)
            val check = cell.findViewById<ImageView>(R.id.vote_check)
            cell.findViewById<TextView>(R.id.vote_name).text = person.name
            avatar.setImageDrawable(circularAvatar(person.avatarRes))
            avatar.alpha = UNPICKED_ALPHA
            cell.setOnClickListener {
                val nowPicked = if (picked.contains(person.seat)) {
                    picked.remove(person.seat); false
                } else {
                    picked.add(person.seat); true
                }
                check.visibility = if (nowPicked) View.VISIBLE else View.GONE
                avatar.animate()
                    .alpha(if (nowPicked) 1f else UNPICKED_ALPHA)
                    .setDuration(140)
                    .start()
            }
            voteGrid.addView(cell)
        }
        doneButton.setOnClickListener { submitPicks() }
    }

    private fun submitPicks() {
        doneButton.isEnabled = false
        VoteSender.submit(this, picked.toList()) { ok ->
            runOnUiThread {
                if (!ok) {
                    // let them try again rather than silently losing the answer
                    doneButton.isEnabled = true
                    Toast.makeText(this, R.string.vote_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                finalQuestionText.visibility = View.GONE
                voteGrid.visibility = View.GONE
                doneButton.visibility = View.GONE
                finalThanks.setText(R.string.final_thanks)
                finalThanks.visibility = View.VISIBLE
                // back to idle after a beat
                finalGroup.postDelayed({ MessageStore.endFinalQuestion(this) }, THANKS_MS)
            }
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
            return
        }
        // "install unknown apps" — lets the app apply its own updates
        if (!packageManager.canRequestPackageInstalls() && !promptedInstall) {
            promptedInstall = true
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    companion object {
        /**
         * Whether the viewer is actually on screen. PushService uses this to
         * decide if its notification is still needed: startActivity() from the
         * background reports success even when the system silently blocks it
         * (locked phone, background-start limits), so "the call worked" is not
         * evidence that anything is visible.
         */
        @Volatile
        var isOnScreen = false
            private set

        private const val TAG = "MainActivity"

        /** Stagger between letters of the cover-screen teaser. */
        private const val MS_PER_LETTER = 90L

        /** How long each individual letter takes to fade up. */
        private const val LETTER_FADE_MS = 260L

        /** Fade of the secret as the phone is opened. */
        private const val REVEAL_FADE_MS = 420L

        /** How long "Bedankt!" stays up before the phone returns to idle. */
        private const val THANKS_MS = 3_000L

        /** Dimming on a dater who has not been picked. */
        private const val UNPICKED_ALPHA = 0.45f

        /** How much of the screen a secret's image or clip takes up. */
        private const val MEDIA_SCREEN_FRACTION = 0.60f
        private var promptedOverlay = false
        private var promptedBattery = false
        private var promptedInstall = false
    }
}
