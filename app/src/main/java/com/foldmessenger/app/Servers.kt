package com.foldmessenger.app

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Where to talk to: the laptop on the venue Wi-Fi if it can be reached, ntfy.sh
 * otherwise.
 *
 * The laptop is far quicker — a local hop rather than a round trip to the
 * internet, and attachments come off the LAN instead of six phones sharing the
 * venue uplink — but it is also a single box that can be closed, unplugged or
 * moved to another network. So it is never the only option: every connection
 * re-checks, and a phone that cannot see the laptop carries on over ntfy
 * without anyone touching it.
 *
 * The address is learned from an admin broadcast (`__SERVER__http://…`), so no
 * handset has to be set up by hand.
 */
object Servers {

    private const val TAG = "Servers"
    private const val HEALTH_TIMEOUT_MS = 2_500L

    /**
     * How many probes in a row must fail before a phone gives up on a laptop it
     * was happily using. A single missed probe is common — the radio is busy, or
     * the laptop is mid-transfer to five other handsets — and treating one as
     * proof the laptop has gone splits the fleet across two servers, which is
     * far worse than being slow: a reveal published to one would only reach the
     * phones that happened to be on it.
     */
    private const val FAILURES_BEFORE_GIVING_UP = 3

    @Volatile
    private var consecutiveFailures = 0

    /** Which address the failures above belong to, so a new one starts clean. */
    @Volatile
    private var probedBase: String? = null

    private val probe = OkHttpClient.Builder()
        .connectTimeout(HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Decide which server to use now. Blocking (a short probe); call it off the
     * main thread, and remember the answer for the senders.
     */
    fun resolve(ctx: Context): String {
        val local = MessageStore.getLocalServer(ctx)
            ?: BuildConfig.LOCAL_SERVER.takeIf { it.isNotEmpty() }
        if (local != probedBase) {
            probedBase = local
            consecutiveFailures = 0
        }
        if (!local.isNullOrEmpty() && isReachable(local)) {
            consecutiveFailures = 0
            MessageStore.setActiveServer(ctx, local)
            return local
        }
        if (!local.isNullOrEmpty()) {
            consecutiveFailures++
            if (consecutiveFailures < FAILURES_BEFORE_GIVING_UP) {
                Log.i(TAG, "Laptop at $local missed a probe " +
                    "($consecutiveFailures/$FAILURES_BEFORE_GIVING_UP) — staying on it")
                MessageStore.setActiveServer(ctx, local)
                return local
            }
            Log.i(TAG, "Laptop at $local not answering after $consecutiveFailures tries" +
                " — falling back to ${Config.NTFY_SERVER}")
        }
        MessageStore.setActiveServer(ctx, Config.NTFY_SERVER)
        return Config.NTFY_SERVER
    }

    /** The server last resolved, for publishing votes and selfies. */
    fun current(ctx: Context): String =
        MessageStore.getActiveServer(ctx) ?: Config.NTFY_SERVER

    /** Only the local server is authenticated-free; ntfy still needs the token. */
    fun needsAuth(server: String): Boolean = server == Config.NTFY_SERVER

    private fun isReachable(base: String): Boolean = try {
        probe.newCall(Request.Builder().url("$base/health").build()).execute().use {
            it.isSuccessful
        }
    } catch (e: Exception) {
        false
    }
}
