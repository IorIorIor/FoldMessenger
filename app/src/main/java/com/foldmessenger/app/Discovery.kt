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
        val subnets = candidateSubnets()
        if (subnets.isEmpty()) {
            Log.i(TAG, "No IPv4 address on any interface — not on a network?")
            return null
        }
        Log.i(TAG, "Looking for a laptop on: " + subnets.joinToString { "${it.first} (${it.second}.0/24)" })
        for ((iface, prefix) in subnets) {
            sweep(prefix)?.let { base ->
                Log.i(TAG, "Found the laptop at $base via $iface")
                return base
            }
        }
        Log.i(TAG, "No laptop answered on any subnet")
        return null
    }

    /**
     * Every network this phone is on, Wi-Fi first.
     *
     * A handset with mobile data enabled has more than one, and the cellular one
     * carries a private address that looks just as plausible as the Wi-Fi one.
     * Picking whichever came first meant sweeping the carrier's subnet and
     * finding nothing, on a phone sitting on the right Wi-Fi the whole time.
     */
    private fun candidateSubnets(): List<Pair<String, String>> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<java.net.Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .mapNotNull { it.hostAddress?.substringBeforeLast('.') }
                    .map { iface.name to it }
            }
            .distinctBy { it.second }
            .sortedBy { if (it.first.startsWith("wlan")) 0 else 1 }
    } catch (e: Exception) {
        Log.w(TAG, "Could not list interfaces: ${e.message}")
        emptyList()
    }

    private fun sweep(prefix: String): String? {
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
                if (host != null && isOurServer(host)) return "http://$host:$PORT"
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Sweep of $prefix.0/24 failed: ${e.message}")
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

}
