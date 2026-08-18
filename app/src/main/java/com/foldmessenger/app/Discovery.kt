package com.foldmessenger.app

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds the laptop on the Wi-Fi without being told where it is.
 *
 * The address used to arrive as an admin broadcast, which only works if the
 * phones can already be reached over the internet — and at a venue that is
 * exactly what cannot be relied on. It is also wrong the moment DHCP hands the
 * laptop a different address. So instead of being told, a phone looks: it takes
 * its own address, walks the rest of the subnet, and asks whoever answers on the
 * port whether they are a Fold Messenger server.
 *
 * A sweep is a few seconds, so it runs when there is nothing better to go on —
 * never on the path of a secret that is already arriving.
 */
object Discovery {

    private const val TAG = "Discovery"
    private const val PORT = 8080
    private const val CONNECT_TIMEOUT_MS = 400
    private const val PARALLEL = 32

    private val verify = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    /** Blocking; call off the main thread. Returns e.g. "http://10.74.11.93:8080". */
    fun findServer(): String? {
        val own = ownAddress() ?: run {
            Log.i(TAG, "No IPv4 address — not on Wi-Fi?")
            return null
        }
        val prefix = own.substringBeforeLast('.')
        Log.i(TAG, "Sweeping $prefix.1-254 for a laptop on port $PORT (self is $own)")

        val pool = Executors.newFixedThreadPool(PARALLEL)
        return try {
            // Own address is swept too rather than skipped: a phone never runs a
            // server, so it costs one failed connection and removes a special case.
            val candidates = (1..254).map { last ->
                pool.submit<String?> {
                    val host = "$prefix.$last"
                    if (portOpen(host)) host else null
                }
            }
            // Check them in address order so a sweep gives the same answer twice
            // running, rather than whichever host happened to answer first.
            for (task in candidates) {
                val host = try { task.get(6, TimeUnit.SECONDS) } catch (e: Exception) { null }
                if (host != null && isOurServer(host)) {
                    val base = "http://$host:$PORT"
                    Log.i(TAG, "Found the laptop at $base")
                    return base
                }
            }
            Log.i(TAG, "No laptop found on $prefix.0/24")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Sweep failed: ${e.message}")
            null
        } finally {
            pool.shutdownNow()
        }
    }

    private fun portOpen(host: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS); true }
    } catch (e: Exception) {
        false
    }

    /** Something else may be on 8080; only our own server says so. */
    private fun isOurServer(host: String): Boolean = try {
        val request = Request.Builder().url("http://$host:$PORT/health").build()
        verify.newCall(request).execute().use { r ->
            r.isSuccessful && (r.body?.string() ?: "").contains("\"ok\"")
        }
    } catch (e: Exception) {
        false
    }

    private fun ownAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
