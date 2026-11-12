package com.projectfocus.feature.stats

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN")
class StatsSceneLabelsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Test fun `all six historical label sets resolve to the current locale`() {
        val labels=StatsSceneLabels(context)
        for(name in listOf("Programming","程式設計","プログラミング","프로그래밍","Программирование"))
            assertEquals("编程",labels.label(name,"programming"))
        for(name in listOf("Fandom","推し活","덕질","Кумиры"))assertEquals("追星",labels.label(name,"starchasing"))
        assertEquals("睡眠",labels.label("meditation"))
        assertEquals("睡眠",labels.label("Sleep","meditation"))
    }
    @Test fun `a translated word used with a different icon retains the custom name`() {
        val labels=StatsSceneLabels(context)
        assertEquals("Study",labels.label("Study","work"))
        assertEquals("学习",labels.label("Study",null))
        assertEquals("学习",labels.label("study","work"))
        assertEquals("Project A",labels.label("\t Project A\r","work"))
        assertEquals("\u00a0Study\u00a0",labels.label("\u00a0Study\u00a0"))
    }
    @Test fun `English display uses English labels without changing stored keys`() {
        val localized=context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        val labels=StatsSceneLabels(localized)
        assertEquals("Reading",labels.label("reading"))
        assertEquals("Study",labels.label("学习","study"))
        assertEquals("Fandom",labels.label("追星","starchasing"))
    }
    @Test fun `chart labels use code point limits and retain complete supplementary characters`() {
        assertEquals("abcdefghijklmno…",StatsDonutView.shortLabel("abcdefghijklmnopq"))
        assertEquals("一二三四五六七…",StatsDonutView.shortLabel("一二三四五六七八九"))
        assertEquals("😀".repeat(15)+"…",StatsDonutView.shortLabel("😀".repeat(17)))
        assertEquals("  ".trim(),StatsDonutView.shortLabel("  "))
        assertEquals("English",StatsDonutView.shortLabel("\tEnglish\r"))
    }
}
