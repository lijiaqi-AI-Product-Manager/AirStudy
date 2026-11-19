package com.projectfocus.widget.glance

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.glance.LocalContext










































@Composable
fun str(@androidx.annotation.StringRes id: Int): String {
    val context = LocalContext.current
    return remember(id, context) { context.getString(id) }
}

/** 供**非 Composable** 场景（Receiver、Worker）取文案用 */
fun Context.str(@androidx.annotation.StringRes id: Int): String = getString(id)
