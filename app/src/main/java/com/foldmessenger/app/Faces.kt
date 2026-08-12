package com.foldmessenger.app

import android.content.Context
import java.io.File

/**
 * Selfies taken at the start of a round. Every phone publishes its own and
 * caches everyone else's, so the closing question shows the faces people are
 * actually sitting with rather than the preloaded photos.
 *
 * Cached per table and seat, so switching tables brings back that table's
 * selfies rather than mixing groups up.
 */
object Faces {

    private fun dir(ctx: Context) = File(ctx.filesDir, "faces").apply { mkdirs() }

    private fun file(ctx: Context, table: Int, seat: Int) =
        File(dir(ctx), "t${table}_p$seat.jpg")

    /** The selfie for a seat, or null to fall back to the preloaded photo. */
    fun get(ctx: Context, table: Int, seat: Int): File? =
        file(ctx, table, seat).takeIf { it.exists() && it.length() > 0 }

    fun save(ctx: Context, table: Int, seat: Int, bytes: ByteArray) {
        file(ctx, table, seat).writeBytes(bytes)
    }

    /** Wipe a table's selfies so a new group starts clean. */
    fun clear(ctx: Context, table: Int) {
        dir(ctx).listFiles()?.filter { it.name.startsWith("t${table}_") }?.forEach { it.delete() }
    }
}
