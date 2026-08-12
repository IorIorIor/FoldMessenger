package com.foldmessenger.app

import android.content.Context

/**
 * The three tables of players. Only one table is in play at a time; the admin
 * switches between them, and every phone follows.
 *
 * A player's seat is their phone number: the first name in a table's list is on
 * phone 1. That means a handset can work out who is holding it from its own
 * phone number alone, with nothing to set up on the night.
 *
 * To change the cast see res/values/tables.xml.
 */
object Tables {

    data class Player(
        /** Seat at the table, and therefore the phone number: 1-based. */
        val seat: Int,
        val name: String,
        val avatarRes: Int
    )

    /** Everyone at [table], in phone order. Empty if that table isn't defined. */
    fun players(ctx: Context, table: Int): List<Player> {
        val namesId = ctx.resources.getIdentifier(
            "table_${table}_names", "array", ctx.packageName
        )
        if (namesId == 0) return emptyList()
        return ctx.resources.getStringArray(namesId).mapIndexed { index, name ->
            val seat = index + 1
            Player(seat, name, avatarRes(ctx, table, seat))
        }
    }

    /** Whoever is holding [phoneId] at [table]. */
    fun player(ctx: Context, table: Int, phoneId: Int): Player? =
        players(ctx, table).getOrNull(phoneId - 1)

    private fun avatarRes(ctx: Context, table: Int, seat: Int): Int =
        ctx.resources.getIdentifier("avatar_t${table}_p$seat", "drawable", ctx.packageName)
}
