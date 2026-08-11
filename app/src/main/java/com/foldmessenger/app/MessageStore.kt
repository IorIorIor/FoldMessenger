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

    fun saveMessage(ctx: Context, text: String, mediaPath: String?, mime: String, personId: Int) {
        prefs(ctx).edit()
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
        prefs(ctx).edit()
            .remove(KEY_TEXT)
            .remove(KEY_IMAGE)
            .remove(KEY_MIME)
            .remove(KEY_PERSON)
            .remove(KEY_TIME)
            .remove(KEY_VIEWED)
            .apply()
        Handler(Looper.getMainLooper()).post { onMessageChanged?.invoke() }
    }
}
