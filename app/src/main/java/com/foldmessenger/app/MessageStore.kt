package com.foldmessenger.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Persists the latest received message and notifies the foreground activity (if any).
 * Single-message model: the app always shows the most recent message.
 */
object MessageStore {
    private const val PREFS = "foldmessenger"
    private const val KEY_PHONE_ID = "phone_id"
    private const val KEY_TEXT = "last_text"
    private const val KEY_IMAGE = "last_image_path"
    private const val KEY_TIME = "last_time"
    private const val KEY_MIME = "last_mime"
    private const val KEY_PERSON = "last_person"
    private const val KEY_VIEWED = "viewed"
    private const val KEY_LAST_EVENT = "last_event_id"
    private const val KEY_LAST_EVENT_TIME = "last_event_time"
    private const val KEY_TABLE = "active_table"
    private const val KEY_FINAL_Q = "final_question"
    private const val KEY_SELFIE = "selfie_mode"
    private const val KEY_PENDING = "media_pending"
    private const val KEY_SERIAL = "message_serial"
    private const val KEY_STAGED_TEXT = "staged_text"
    private const val KEY_STAGED_IMAGE = "staged_image_path"
    private const val KEY_STAGED_MIME = "staged_mime"

    @Volatile
    var onMessageChanged: (() -> Unit)? = null

    data class Message(
        val text: String,
        val mediaPath: String?,
        val mime: String,
        /** Position in the baked-in roster; 0 when the sender named nobody. */
        val personId: Int,
        val timeMillis: Long
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getPhoneId(ctx: Context): Int = prefs(ctx).getInt(KEY_PHONE_ID, 0)

    /**
     * Where this phone got to in the topic, so a reconnect can ask for anything
     * published while the socket was down. The timestamp drives ntfy's `since`
     * (its id-based form is accepted but backfills nothing on ntfy.sh); the id
     * is kept alongside so the message we already showed isn't replayed.
     */
    fun getLastEventId(ctx: Context): String? = prefs(ctx).getString(KEY_LAST_EVENT, null)

    /** Publish time in Unix seconds of the last event seen, or 0. */
    fun getLastEventTime(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_EVENT_TIME, 0L)

    fun setLastEvent(ctx: Context, id: String, timeSeconds: Long) {
        prefs(ctx).edit()
            .putString(KEY_LAST_EVENT, id)
            .putLong(KEY_LAST_EVENT_TIME, timeSeconds)
            .apply()
    }

    fun setPhoneId(ctx: Context, id: Int) {
        prefs(ctx).edit().putInt(KEY_PHONE_ID, id).apply()
    }

    /** Table currently in play; defaults to the first. */
    fun getActiveTable(ctx: Context): Int = prefs(ctx).getInt(KEY_TABLE, 1)

    fun setActiveTable(ctx: Context, table: Int) {
        prefs(ctx).edit().putInt(KEY_TABLE, table).apply()
        Handler(Looper.getMainLooper()).post { onMessageChanged?.invoke() }
    }

    /** True while this phone has been asked for a selfie. */
    fun isSelfieMode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SELFIE, false)

    fun setSelfieMode(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SELFIE, on).apply()
        Handler(Looper.getMainLooper()).post { onMessageChanged?.invoke() }
    }

    /** True while the closing question is running on this phone. */
    fun isFinalQuestion(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FINAL_Q, false)

    fun startFinalQuestion(ctx: Context) {
        getLastMessage(ctx)?.mediaPath?.let { File(it).delete() }
        clearStaged(ctx)
        prefs(ctx).edit()
            .putBoolean(KEY_PENDING, false)
            .putLong(KEY_SERIAL, prefs(ctx).getLong(KEY_SERIAL, 0L) + 1)
            .putBoolean(KEY_FINAL_Q, true)
            .putBoolean(KEY_SELFIE, false)
            .remove(KEY_TEXT).remove(KEY_IMAGE).remove(KEY_MIME)
            .remove(KEY_PERSON).remove(KEY_TIME).remove(KEY_VIEWED)
            .apply()
        Handler(Looper.getMainLooper()).post { onMessageChanged?.invoke() }
    }

    fun endFinalQuestion(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_FINAL_Q, false).apply()
        Handler(Looper.getMainLooper()).post { onMessageChanged?.invoke() }
    }

    fun getLastMessage(ctx: Context): Message? {
        val p = prefs(ctx)
        val time = p.getLong(KEY_TIME, 0L)
        if (time == 0L) return null
        return Message(
            text = p.getString(KEY_TEXT, "") ?: "",
            mediaPath = p.getString(KEY_IMAGE, null),
            mime = p.getString(KEY_MIME, "") ?: "",
            personId = p.getInt(KEY_PERSON, 0),
            timeMillis = time
        )
    }

    /**
     * True when a secret is on screen but its picture or clip is still coming
     * down. The phone is alerted before the bytes arrive — waiting for them is
     * what made six handsets chime at six different moments — so the viewer
     * holds on the teaser until this clears rather than popping the image in
     * halfway through a reveal.
     */
    fun isMediaPending(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_PENDING, false)

    /**
     * Counts secrets, so a download that finishes late can tell whether the
     * message it belongs to is still the one on screen. Media arrives on its own
     * thread, so by the time it lands the round may have moved on.
     */
    fun getSerial(ctx: Context): Long = prefs(ctx).getLong(KEY_SERIAL, 0L)

    /** Attach media to the message identified by [serial]; a no-op if superseded. */
    fun attachMedia(ctx: Context, serial: Long, mediaPath: String?) {
        val p = prefs(ctx)
        if (p.getLong(KEY_SERIAL, 0L) != serial) {
            mediaPath?.let { File(it).delete() }
            return
        }
        p.edit().putString(KEY_IMAGE, mediaPath).putBoolean(KEY_PENDING, false).apply()
        Handler(Looper.getMainLooper()).post { onMessageChanged?.invoke() }
    }

    /**
     * Hold a secret without showing it. Kept in its own slot and its own
     * directory so the live message — and the wipe that precedes each download —
     * cannot disturb it while it waits for the reveal.
     */
    fun stageMessage(ctx: Context, text: String, mediaPath: String?, mime: String) {
        prefs(ctx).getString(KEY_STAGED_IMAGE, null)
            ?.takeIf { it != mediaPath }?.let { File(it).delete() }
        prefs(ctx).edit()
            .putString(KEY_STAGED_TEXT, text)
            .putString(KEY_STAGED_IMAGE, mediaPath)
            .putString(KEY_STAGED_MIME, mime)
            .apply()
    }

    /**
     * Promote the held secret to the live one. The media is already on disk, so
     * this is a rename and a preferences write — fast enough that every phone
     * reveals on the same beat.
     *
     * @return false if nothing was staged, so the caller can fall back.
     */
    fun promoteStaged(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.contains(KEY_STAGED_TEXT)) return false
        val text = p.getString(KEY_STAGED_TEXT, "") ?: ""
        val mime = p.getString(KEY_STAGED_MIME, "") ?: ""
        val stagedPath = p.getString(KEY_STAGED_IMAGE, null)

        var livePath: String? = null
        if (stagedPath != null) {
            val staged = File(stagedPath)
            val dir = File(ctx.filesDir, "messages").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, staged.name)
            livePath = if (staged.renameTo(target)) target.absolutePath else stagedPath
        }
        p.edit()
            .remove(KEY_STAGED_TEXT).remove(KEY_STAGED_IMAGE).remove(KEY_STAGED_MIME)
            .apply()
        saveMessage(ctx, text, livePath, mime, 0)
        return true
    }

    /** Drop anything held without showing it (round over, or replaced). */
    fun clearStaged(ctx: Context) {
        prefs(ctx).getString(KEY_STAGED_IMAGE, null)?.let { File(it).delete() }
        prefs(ctx).edit()
            .remove(KEY_STAGED_TEXT).remove(KEY_STAGED_IMAGE).remove(KEY_STAGED_MIME)
            .apply()
    }

    fun saveMessage(
        ctx: Context,
        text: String,
        mediaPath: String?,
        mime: String,
        personId: Int,
        mediaPending: Boolean = false
    ) {
        prefs(ctx).edit()
            .putBoolean(KEY_PENDING, mediaPending)
            .putLong(KEY_SERIAL, prefs(ctx).getLong(KEY_SERIAL, 0L) + 1)
            // a new secret ends the closing question and any unfinished selfie:
            // the admin has moved on, and no phone should be left stranded
            .putBoolean(KEY_FINAL_Q, false)
            .putBoolean(KEY_SELFIE, false)
            .putString(KEY_TEXT, text)
            .putString(KEY_IMAGE, mediaPath)
            .putString(KEY_MIME, mime)
            .putInt(KEY_PERSON, personId)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .putBoolean(KEY_VIEWED, false)
            .apply()
        Handler(Looper.getMainLooper()).post { onMessageChanged?.invoke() }
    }

    /** True once the message has been shown on the unfolded screen. */
    fun isViewed(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_VIEWED, false)

    fun markViewed(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_VIEWED, true).apply()
    }

    /** Wipe the current message (round over, or admin reset). */
    fun clear(ctx: Context) {
        getLastMessage(ctx)?.mediaPath?.let { File(it).delete() }
        clearStaged(ctx)
        prefs(ctx).edit()
            .putBoolean(KEY_PENDING, false)
            // supersede any download still in flight, so it cannot land on the
            // blank screen a moment after the round was cleared
            .putLong(KEY_SERIAL, prefs(ctx).getLong(KEY_SERIAL, 0L) + 1)
            .remove(KEY_TEXT)
            .remove(KEY_IMAGE)
            .remove(KEY_MIME)
            .remove(KEY_PERSON)
            .remove(KEY_TIME)
            .remove(KEY_VIEWED)
            .putBoolean(KEY_FINAL_Q, false)
            .putBoolean(KEY_SELFIE, false)
            .apply()
        Handler(Looper.getMainLooper()).post { onMessageChanged?.invoke() }
    }
}
