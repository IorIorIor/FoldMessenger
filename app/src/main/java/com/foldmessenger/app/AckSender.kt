package com.foldmessenger.app

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.foldmessenger.app.Ntfy.withAuth
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reports back to the admin page what this phone did with a secret and how long
 * it took, so "some phones are a minute late" can be read off a table instead of
 * guessed at from the room.
 *
 * Durations are measured on this phone between two points in the same method, so
 * they carry no clock-skew: six handsets that have never agreed on the time still
 * produce comparable numbers. Absolute arrival order comes from the server
 * stamping each ack as it lands.
 */
object AckSender {

    private const val TAG = "AckSender"

    /** Its own thread: an ack must never be in the way of showing a secret. */
    private val executor = Executors.newSingleThreadExecutor()

    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * @param phase what happened — "shown" when the phone alerted, "ready" when
     *   the media finished landing.
     * @param ms how long that took from the moment the event arrived on the socket.
     */
    fun send(ctx: Context, phase: String, ms: Long, note: String = "") {
        val phoneId = MessageStore.getPhoneId(ctx)
        val body = JSONObject().apply {
            put("phone", phoneId)
            put("phase", phase)
            put("ms", ms)
            if (note.isNotEmpty()) put("note", note)
        }.toString()

        executor.execute {
            try {
                val request = Request.Builder()
                    .url("${Servers.current(ctx)}/${Config.acksTopic()}")
                    .post(body.toRequestBody())
                    .withAuth()
                    .build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Ack failed: ${e.message}")
            }
        }
    }
}
