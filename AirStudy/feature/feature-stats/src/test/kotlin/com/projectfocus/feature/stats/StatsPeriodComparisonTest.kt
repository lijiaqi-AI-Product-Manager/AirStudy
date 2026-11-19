package com.projectfocus.feature.stats

import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.service.focus.history.RecordedFlight
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StatsPeriodComparisonTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    private val day=LocalDate.parse("2026-11-03")
    private fun record(id: String,at: Long,minutes: Int,status: String="COMPLETED")=RecordedFlight.Countdown(FlightHistoryEntity(
        id,null,"SOLO",null,"PEK","Beijing Airport","北京","CN",40.0,116.0,
        "TSN","Tianjin Airport","天津","CN",39.0,117.0,at,at+300_000,25,minutes,99.25,status,
        null,"01A",false,false,false,null,false,at,at,ownerScope="statistics-comparison-owner"))
    @Test fun `day comparison excludes the current range and incomplete previous records`() {
        val selection=StatsDateSelection(day,StatsPeriod.DAY)
        val before=StatsPeriodComparison.previousRange(selection,zone)
        val records=listOf(record("start",before.fromWallMs,2),record("last",before.untilWallMs-1,3),
            record("outside",before.fromWallMs-1,50),record("current",before.untilWallMs,10),
            record("cancelled",before.fromWallMs,50,"CANCELLED"),record("running",before.fromWallMs,50,"ONGOING"))
        assertEquals(StatsFocusComparison(5.0,1.0),StatsPeriodComparison.calculate(records,10.0,selection,zone))
    }
    @Test fun `week comparison uses the previous Monday through Sunday`() {
        val range=StatsPeriodComparison.previousRange(StatsDateSelection(day),zone)
        assertEquals(LocalDate.parse("2026-10-26"),range.firstDate)
        assertEquals(LocalDate.parse("2026-11-01"),range.lastDate)
        val local=ZoneId.of("America/New_York")
        val previous=StatsPeriodComparison.previousRange(StatsDateSelection(day),local)
        assertEquals(169*3_600_000L,previous.untilWallMs-previous.fromWallMs)
    }
    @Test fun `month comparison retains leap day instead of subtracting the current month length`() {
        val range=StatsPeriodComparison.previousRange(StatsDateSelection(LocalDate.parse("2024-03-31"),StatsPeriod.MONTH),zone)
        assertEquals(LocalDate.parse("2024-02-01"),range.firstDate)
        assertEquals(LocalDate.parse("2024-02-29"),range.lastDate)
    }
    @Test fun `year comparison includes the full previous leap year`() {
        val range=StatsPeriodComparison.previousRange(StatsDateSelection(LocalDate.parse("2025-11-03"),StatsPeriod.YEAR),zone)
        assertEquals(LocalDate.parse("2024-01-01"),range.firstDate)
        assertEquals(LocalDate.parse("2024-12-31"),range.lastDate)
        assertEquals(366*86_400_000L,range.untilWallMs-range.fromWallMs)
    }
    @Test fun `custom comparison moves the same number of calendar dates across daylight saving`() {
        val local=ZoneId.of("America/New_York")
        val first=LocalDate.parse("2026-03-08")
        val selected=StatsDateSelection(first).custom(first,first.plusDays(1))
        val current=selected.range(local)
        val previous=StatsPeriodComparison.previousRange(selected,local)
        assertEquals(LocalDate.parse("2026-03-06"),previous.firstDate)
        assertEquals(LocalDate.parse("2026-03-07"),previous.lastDate)
        assertEquals(47*3_600_000L,current.untilWallMs-current.fromWallMs)
        assertEquals(48*3_600_000L,previous.untilWallMs-previous.fromWallMs)
    }
    @Test fun `zero baselines remain distinct from a complete decrease`() {
        val selected=StatsDateSelection(day,StatsPeriod.DAY)
        assertEquals(StatsFocusComparison(0.0,null),StatsPeriodComparison.calculate(emptyList(),0.0,selected,zone))
        assertEquals(StatsFocusComparison(5.0,null),StatsPeriodComparison.calculate(emptyList(),5.0,selected,zone))
        val previous=StatsPeriodComparison.previousRange(selected,zone)
        assertEquals(StatsFocusComparison(-5.0,-1.0),StatsPeriodComparison.calculate(listOf(record("previous",previous.fromWallMs,5)),0.0,selected,zone))
    }
}
