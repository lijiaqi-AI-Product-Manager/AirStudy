package com.projectfocus.app.home

import android.content.Context

/** Own integer storage bridge to source MapStyle.isNight; existing indices keep their identity. */
internal object HomeMapStyleSelection {
    const val FILE_NAME = "projectfocus_home"
    const val KEY = "map_style"
    const val DEFAULT_INDEX = 6 // Recovered source DAY, including its actual raw bytes and label.
    private val night = booleanArrayOf(true, true, true, true, false, true, false, true)

    fun read(context: Context): Int {
        val saved = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).getInt(KEY, DEFAULT_INDEX)
        return saved.takeIf { it in night.indices } ?: DEFAULT_INDEX
    }

    fun isNight(context: Context): Boolean = night[read(context)]

    fun select(context: Context, index: Int) {
        require(index in night.indices)
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().putInt(KEY, index).apply()
    }
}
