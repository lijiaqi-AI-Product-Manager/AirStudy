package com.projectfocus.feature.stats

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan

internal object StatsFocusText {
    fun rich(context: Context, minutes: Double, unitColor: Int): CharSequence {
        require(minutes.isFinite())
        val rounded = Math.round(minutes).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val hours = rounded / 60
        val rest = rounded % 60
        val text = SpannableStringBuilder()
        fun unit(id: Int) {
            val start = text.length
            text.append(context.getString(id))
            text.setSpan(RelativeSizeSpan(0.52f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(unitColor), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (hours > 0) {
            text.append(hours.toString())
            unit(R.string.stats_compact_hour)
            if (rest > 0) text.append(' ')
        }
        if (rest > 0 || hours == 0) {
            text.append(rest.toString())
            unit(R.string.stats_compact_minute)
        }
        return text
    }
}
