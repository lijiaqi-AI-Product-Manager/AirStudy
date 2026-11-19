package com.projectfocus.core.common.plan

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class SourceFlightPlanStatusTest {
    @Test fun englishAndChineseAliasesUseTheSameBranch() {
        for (value in listOf("canceled", "cancelled", "已取消", " CANCELED ")) {
            assertThat(SourceFlightPlanStatus.parse(value)).isEqualTo(SourceFlightPlanStatus.CANCELED)
        }
        for (value in listOf("in_progress", "进行中", "飞行中")) {
            assertThat(SourceFlightPlanStatus.parse(value)).isEqualTo(SourceFlightPlanStatus.IN_PROGRESS)
        }
        assertThat(SourceFlightPlanStatus.parse("等待起飞")).isEqualTo(SourceFlightPlanStatus.WAITING)
        assertThat(SourceFlightPlanStatus.parse("已完成")).isEqualTo(SourceFlightPlanStatus.COMPLETED)
    }

    @Test fun emptyUnknownAndNonJavaWhitespaceRemainUnknown() {
        for (value in listOf(null, " ", "future_status", "\u00a0canceled\u00a0")) {
            assertThat(SourceFlightPlanStatus.parse(value)).isEqualTo(SourceFlightPlanStatus.UNKNOWN)
        }
    }

    @Test fun caseConversionFollowsTheDeviceLocaleAsInTheSource() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertThat(SourceFlightPlanStatus.parse("IN_PROGRESS")).isEqualTo(SourceFlightPlanStatus.UNKNOWN)
            assertThat(SourceFlightPlanStatus.parse("in_progress")).isEqualTo(SourceFlightPlanStatus.IN_PROGRESS)
        } finally { Locale.setDefault(before) }
    }
}
