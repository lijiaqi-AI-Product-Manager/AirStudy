package com.projectfocus.feature.flight.hud

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Diagnostics of the exact native hierarchy being drawn, no replacement values or controls. */
internal fun actualHudPreviewMetadata(root: ViewGroup): JSONObject {
    fun node(view: View): JSONObject {
        val rectangle = Rect(0, 0, view.width, view.height)
        if (view !== root) root.offsetDescendantRectToMyCoords(view, rectangle)
        return JSONObject().apply {
            put("class", view.javaClass.name)
            put("id", if (view.id > 0) runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() else null)
            put("bounds", rectangle.toShortString())
            put("visibility", view.visibility)
            put("alpha", view.alpha)
            put("translationY", view.translationY)
            put("scrollX", view.scrollX)
            put("scrollY", view.scrollY)
            put("attached", view.isAttachedToWindow)
            put("measuredWidth", view.measuredWidth)
            put("measuredHeight", view.measuredHeight)
            put("layoutWidth", view.layoutParams?.width)
            put("layoutHeight", view.layoutParams?.height)
            if (view is TextView) {
                put("text", view.text)
                put("textColor", view.currentTextColor)
                put("textSize", view.textSize)
                put("baseline", view.baseline)
                put("lineWidth", view.layout?.let { if (it.lineCount > 0) it.getLineWidth(0) else null })
                put("textLayoutWidth", view.layout?.width)
                put("lineLeft", view.layout?.let { if (it.lineCount > 0) it.getLineLeft(0) else null })
            }
            if (view is SourceNumberScrollView) put("number", view.getValue())
            if (view is ViewGroup) put("children", JSONArray().apply {
                repeat(view.childCount) { put(node(view.getChildAt(it))) }
            })
        }
    }
    return node(root)
}
