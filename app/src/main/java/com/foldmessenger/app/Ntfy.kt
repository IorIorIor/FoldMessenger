package com.foldmessenger.app

import okhttp3.Request

/**
 * Authentication for ntfy. The token belongs to the paid account, which is what
 * lifts the anonymous rate and bandwidth limits — without it a busy night hits
 * "attachment too large, or bandwidth limit reached" and secrets stop arriving.
 *
 * It is injected at build time (see app/build.gradle.kts) and is deliberately
 * not in the repository.
 */
object Ntfy {

    val hasToken: Boolean get() = BuildConfig.NTFY_TOKEN.isNotEmpty()

    fun Request.Builder.withAuth(): Request.Builder =
        if (hasToken) header("Authorization", "Bearer ${BuildConfig.NTFY_TOKEN}") else this

    /**
     * Subscriptions can't set headers on every transport, so ntfy also accepts
     * the Authorization header base64url-encoded in an `auth` query parameter.
     */
    fun authQueryParam(): String? {
        if (!hasToken) return null
        val header = "Bearer ${BuildConfig.NTFY_TOKEN}"
        val encoded = android.util.Base64.encodeToString(
            header.toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        return "auth=$encoded"
    }
}
