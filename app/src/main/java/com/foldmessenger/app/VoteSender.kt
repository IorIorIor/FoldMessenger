package com.foldmessenger.app

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.foldmessenger.app.Ntfy.withAuth
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Publishes this phone's answer to the closing question back to the admin page,
 * which subscribes to the same topic.
 */
object VoteSender {

    private const val TAG = "VoteSender"
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient()

    /** Fire-and-forget; [onResult] is called on a background thread. */
    fun submit(ctx: Context, picks: List<Int>, onResult: (Boolean) -> Unit) {
        val phoneId = MessageStore.getPhoneId(ctx)
        val table = MessageStore.getActiveTable(ctx)
        val self = Tables.player(ctx, table, phoneId)
        val body = JSONObject().apply {
            put("phone", phoneId)
            put("table", table)
            put("person", self?.seat ?: 0)
            put("personName", self?.name ?: "")
            put("picks", JSONArray(picks))
            put("pickNames", JSONArray(picks.mapNotNull { Tables.player(ctx, table, it)?.name }))
        }.toString()

        executor.execute {
            val ok = try {
                val request = Request.Builder()
                    .url("${Servers.current(ctx)}/${Config.votesTopic()}")
                    .post(body.toRequestBody())
                    .withAuth()
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                Log.w(TAG, "Vote submit failed: ${e.message}")
                false
            }
            onResult(ok)
        }
    }
}
