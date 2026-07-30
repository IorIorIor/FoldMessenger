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
    private const val KEY_VIEWED = "viewed"

    @Volatile
    var onMessageChanged: (() -> Unit)? = null

    data class Message(
        val text: String,
        val mediaPath: String?,
        val mime: String,
        val timeMillis: Long
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getPhoneId(ctx: Context): Int = prefs(ctx).getInt(KEY_PHONE_ID, 0)

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
            timeMillis = time
        )
    }

    fun saveMessage(ctx: Context, text: String, mediaPath: String?, mime: String) {
        prefs(ctx).edit()
            .putString(KEY_TEXT, text)
            .putString(KEY_IMAGE, mediaPath)
            .putString(KEY_MIME, mime)
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
            .remove(KEY_TIME)
            .remove(KEY_VIEWED)
            .apply()
        Handler(Looper.getMainLooper()).post { onMessageChanged?.invoke() }
    }
}
