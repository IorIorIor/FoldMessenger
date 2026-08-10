package com.foldmessenger.app

import android.content.Context

/**
 * The cast of people a secret can be attributed to. Baked into the APK so the
 * sender only has to send a person id.
 *
 * To change the cast:
 *   1. edit the `roster_names` array in res/values/roster.xml
 *   2. drop a square photo in as res/drawable-nodpi/avatar_<n>.png, where <n>
 *      is the 1-based position of that name in the array
 *   3. mirror the same names, in the same order, in the ROSTER array in sender.html
 *
 * The list length is driven entirely by the names array — add a 9th name plus
 * avatar_9.png and it shows up in the app without any further code change.
 */
object Roster {

    data class Person(val id: Int, val name: String, val avatarRes: Int)

    fun all(ctx: Context): List<Person> {
        val names = ctx.resources.getStringArray(R.array.roster_names)
        return names.mapIndexed { index, name ->
            Person(index + 1, name, avatarResFor(ctx, index + 1))
        }
    }

    fun byId(ctx: Context, id: Int): Person? {
        val names = ctx.resources.getStringArray(R.array.roster_names)
        if (id < 1 || id > names.size) return null
        return Person(id, names[id - 1], avatarResFor(ctx, id))
    }

    private fun avatarResFor(ctx: Context, id: Int): Int =
        ctx.resources.getIdentifier("avatar_$id", "drawable", ctx.packageName)
}
