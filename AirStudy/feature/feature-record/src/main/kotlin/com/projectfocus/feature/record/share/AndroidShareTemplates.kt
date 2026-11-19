package com.projectfocus.feature.record.share

import android.content.Context
import com.projectfocus.feature.record.R














fun Context.shareTemplates(): ShareCardSpec.Templates = object : ShareCardSpec.Templates {

    override fun text(from: String, to: String, distance: String, focus: String): String =
        getString(R.string.record_share_text, from, to, distance, focus)

    override fun subject(from: String, to: String): String =
        getString(R.string.record_share_subject, from, to)

    override fun cardDesc(from: String, to: String, flightNo: String): String =
        getString(R.string.record_share_card_desc, from, to, flightNo)


    override val unknownPlace: String
        get() = getString(R.string.record_share_unknown_place)
}
