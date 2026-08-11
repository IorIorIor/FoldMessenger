package com.foldmessenger.app

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Self-update against GitHub Releases. PushService calls check() periodically;
 * when the latest release tag is newer than the running build, the APK asset is
 * downloaded and Android's install dialog is opened (updates are one tap on the
 * phone — silent installs aren't possible for sideloaded apps).
 *
 * All builds are signed with the shared release keystore, so the update
 * installs straight over the running version and keeps the phone's setup.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/IorIorIor/FoldMessenger/releases/latest"

    /** "v1.8.0" / "1.8.0" → 1008000; -1 when unparseable. */
    fun versionScore(version: String): Long {
        val parts = version.trim().removePrefix("v").split(".")
        if (parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return -1
        return parts.take(3).fold(0L) { acc, p -> acc * 1000 + p.toInt() }
            .let { score ->
                // pad short versions ("1.8" == "1.8.0")
                var s = score
                repeat(3 - parts.size.coerceAtMost(3)) { s *= 1000 }
                s
            }
    }

    /**
     * Returns the downloaded update APK when a newer release exists, else null.
     * Blocking — call off the main thread.
     */
    fun fetchUpdate(ctx: Context, client: OkHttpClient): File? {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Release check failed: HTTP ${response.code}")
                return null
            }
            response.body?.string() ?: return null
        }

        val release = JSONObject(body)
        val tag = release.optString("tag_name")
        val current = versionScore(BuildConfig.VERSION_NAME)
        val latest = versionScore(tag)
        if (latest < 0 || latest <= current) return null

        val assets = release.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk")) {
                apkUrl = asset.optString("browser_download_url")
                break
            }
        }
        if (apkUrl.isNullOrEmpty()) return null

        Log.i(TAG, "Update available: $tag (running ${BuildConfig.VERSION_NAME})")
        val dir = File(ctx.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, "FoldMessenger-$tag.apk")
        if (target.exists() && target.length() > 0) return target // already fetched

        dir.listFiles()?.forEach { it.delete() }
        val download = Request.Builder().url(apkUrl).build()
        client.newCall(download).execute().use { response ->
            if (!response.isSuccessful) return null
            target.outputStream().use { out ->
                response.body?.byteStream()?.copyTo(out) ?: return null
            }
        }
        return target
    }

    /** Open Android's package-install dialog for the downloaded APK. */
    fun promptInstall(ctx: Context, apk: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            Log.w(TAG, "Not allowed to install packages; enable 'install unknown apps'")
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                )
        )
    }
}
