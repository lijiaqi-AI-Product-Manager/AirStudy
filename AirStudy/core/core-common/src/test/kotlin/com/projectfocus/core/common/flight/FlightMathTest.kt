package com.projectfocus.core.common.flight

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs
import kotlin.math.cos


class FlightMathTest {

    @Test
    fun `source float progress retains the rounded float remaining-distance field`() {

        assertThat(FlightMath.remainingKm(100.0, FlightMath.progress(6L, 8))).isEqualTo(98.75)
        assertThat(FlightMath.remainingKm(100.0, FlightMath.progress(18L, 8))).isEqualTo(96.25)
        assertThat(FlightMath.remainingKm(1234.5, FlightMath.progress(930L, 93))).isEqualTo(1028.75)
    }

    // ── 1. 距离公式 ─────────────────────────────────────────

    @Test
    @DisplayName("15 分钟应飞 200 公里")
    fun `15 分钟应飞 200 公里`() {
        assertThat(FlightMath.distanceKmForMinutes(15)).isWithin(1e-9).of(200.0)
    }

    @Test
    @DisplayName("45 分钟的恒速距离为 600 公里")
    fun `45 分钟应飞 600 公里`() {
        assertThat(FlightMath.distanceKmForMinutes(45)).isWithin(1e-9).of(600.0)
    }

    @Test
    @DisplayName("60 分钟应飞 800 公里")
    fun `60 分钟应飞 800 公里`() {
        assertThat(FlightMath.distanceKmForMinutes(60)).isWithin(1e-9).of(800.0)
    }

    @Test
    @DisplayName("180 分钟应飞 2400 公里")
    fun `180 分钟应飞 2400 公里`() {
        assertThat(FlightMath.distanceKmForMinutes(180)).isWithin(1e-9).of(2400.0)
    }

    @Test
    @DisplayName("300 分钟应飞 4000 公里")
    fun `300 分钟应飞 4000 公里`() {
        assertThat(FlightMath.distanceKmForMinutes(300)).isWithin(1e-9).of(4000.0)
    }

    @Test
    @DisplayName("0 分钟应为 0 公里，负数应抛异常")
    fun `零与负值边界`() {
        assertThat(FlightMath.distanceKmForMinutes(0)).isEqualTo(0.0)
        assertThrows<IllegalArgumentException> { FlightMath.distanceKmForMinutes(-1) }
    }

    @Test
    @DisplayName("距离反解分钟应可逆")
    fun `距离反解分钟`() {
        assertThat(FlightMath.minutesForDistanceKm(600.0)).isEqualTo(45)
        assertThat(FlightMath.minutesForDistanceKm(800.0)).isEqualTo(60)
        assertThat(FlightMath.minutesForDistanceKm(0.0)).isEqualTo(0)
    }

    @Test
    fun `机场距离转分钟与原包一样四舍五入`() {

        assertThat(FlightMath.minutesForDistanceKm(100.0)).isEqualTo(8)
        assertThat(FlightMath.minutesForDistanceKm(300.0)).isEqualTo(23)
        assertThat(FlightMath.minutesForDistanceKm(299.0)).isEqualTo(22)
        assertThat(FlightMath.minutesForDistanceKm(607.0)).isEqualTo(46)
    }

    @Test
    fun `机场距离先按原包模型保留Float精度再转分钟`() {

        assertThat(FlightMath.minutesForDistanceKm(Math.nextDown(300.0))).isEqualTo(23)
        assertThat(FlightMath.minutesForDistanceKm(Math.nextDown(300f).toDouble())).isEqualTo(22)
    }

    // ── 2. 进度 ─────────────────────────────────────────────

    @Test
    @DisplayName("进度按已用秒/总秒计算，并在两端 clamp")
    fun `进度 clamp`() {
        // 45 分钟 = 2700 秒
        assertThat(FlightMath.progress(0, 45)).isEqualTo(0f)
        assertThat(FlightMath.progress(1350, 45)).isEqualTo(0.5f)
        assertThat(FlightMath.progress(2700, 45)).isEqualTo(1f)
        // 超时 / 负数都不能溢出
        assertThat(FlightMath.progress(9999, 45)).isEqualTo(1f)
        assertThat(FlightMath.progress(-5, 45)).isEqualTo(0f)
        // 总时长为 0 不能除零
        assertThat(FlightMath.progress(10, 0)).isEqualTo(0f)
    }

    @Test
    @DisplayName("进度 50% 时剩余距离 = 总距离一半")
    fun `剩余距离`() {
        val total = FlightMath.distanceKmForMinutes(45)          // 600
        val p = FlightMath.progress(1350, 45)                    // 0.5
        assertThat(FlightMath.remainingKm(total, p)).isWithin(1e-9).of(300.0)
        assertThat(FlightMath.traveledKm(total, p)).isWithin(1e-9).of(300.0)
    }

    @Test
    @DisplayName("剩余秒数不为负")
    fun `剩余秒数不为负`() {
        assertThat(FlightMath.remainingSeconds(0, 45)).isEqualTo(2700L)
        assertThat(FlightMath.remainingSeconds(2700, 45)).isEqualTo(0L)
        assertThat(FlightMath.remainingSeconds(5000, 45)).isEqualTo(0L)
    }

    @Test
    @DisplayName("剩余分钟向上取整：剩 1 秒也显示 1 分钟")
    fun `剩余分钟向上取整`() {
        // 45 分钟航班，已过 2699 秒，剩 1 秒 → 显示 1 分钟（不能显示 0）
        assertThat(FlightMath.remainingMinutesCeil(2699, 45)).isEqualTo(1)
        assertThat(FlightMath.remainingMinutesCeil(2640, 45)).isEqualTo(1)   // 剩 60 秒
        assertThat(FlightMath.remainingMinutesCeil(2639, 45)).isEqualTo(2)   // 剩 61 秒
    }

    // ── 3. 视野适配 ─────────────────────────────────────────

    @Test
    @DisplayName("赤道处经度跨度等于纬度跨度")
    fun `赤道视野`() {
        val (dLat, dLng) = FlightMath.viewportDeltaDegrees(111.32, 0.0)
        assertThat(dLat).isWithin(1e-9).of(1.0)
        assertThat(dLng).isWithin(1e-9).of(1.0)
    }

    @Test
    @DisplayName("60 度纬度处经度跨度翻倍")
    fun `高纬度视野`() {
        val (dLat, dLng) = FlightMath.viewportDeltaDegrees(111.32, 60.0)
        assertThat(dLat).isWithin(1e-9).of(1.0)
        // 1 / cos(60°) = 2
        assertThat(dLng).isWithin(1e-6).of(2.0)
    }

    @Test
    @DisplayName("极点处不除零，经度跨度有限")
    fun `极点防除零`() {
        val (_, dLng) = FlightMath.viewportDeltaDegrees(100.0, 90.0)
        assertThat(dLng.isFinite()).isTrue()
        // 极限值 = 100 / (1e-6 * 111.32)
        assertThat(dLng).isWithin(1.0).of(100.0 / (FlightMath.MIN_COS_LAT * FlightMath.KM_PER_DEGREE))
    }

    @Test
    @DisplayName("boundingBox 返回 [west, south, east, north]")
    fun `boundingBox 顺序`() {
        val box = FlightMath.boundingBox(centerLat = 0.0, centerLng = 0.0, totalKm = 111.32)
        assertThat(box).hasLength(4)
        assertThat(box[0]).isWithin(1e-9).of(-1.0)   // west
        assertThat(box[1]).isWithin(1e-9).of(-1.0)   // south
        assertThat(box[2]).isWithin(1e-9).of(1.0)    // east
        assertThat(box[3]).isWithin(1e-9).of(1.0)    // north
    }

    // ── 4. 大圆距离 ─────────────────────────────────────────

    @Test
    @DisplayName("PEK-SHA 大圆距离约 1070 km")
    fun `北京上海大圆距离`() {
        // 首都机场 40.0799,116.6031 / 虹桥 31.1979,121.3363
        val km = FlightMath.greatCircleKm(40.0799, 116.6031, 31.1979, 121.3363)
        assertThat(km).isWithin(20.0).of(1070.0)
    }

    @Test
    @DisplayName("PEK-NRT 大圆距离约 2100 km")
    fun `北京东京大圆距离`() {
        val km = FlightMath.greatCircleKm(40.0799, 116.6031, 35.7720, 140.3929)
        assertThat(km).isWithin(50.0).of(2100.0)
    }

    @Test
    @DisplayName("同点距离为 0")
    fun `同点距离为零`() {
        assertThat(FlightMath.greatCircleKm(31.2, 121.3, 31.2, 121.3)).isEqualTo(0.0)
    }


    @Test
    @DisplayName("本品恒速距离口径与机场真实距离独立")
    fun `距离与机场真实距离解耦`() {
        val pekShaKm = FlightMath.greatCircleKm(40.0799, 116.6031, 31.1979, 121.3363)
        val pekNrtKm = FlightMath.greatCircleKm(40.0799, 116.6031, 35.7720, 140.3929)

        // 两对机场真实距离差一倍以上
        assertThat(pekNrtKm / pekShaKm).isGreaterThan(1.8)

        // 本品恒速公式对45分钟仍给出600km；这不是原包记录终值断言。
        assertThat(FlightMath.distanceKmForMinutes(45)).isEqualTo(600.0)
    }

    // ── 5. 球面插值 ─────────────────────────────────────────

    @Test
    @DisplayName("插值端点应精确落在起终点")
    fun `插值端点`() {
        val (lat0, lng0) = FlightMath.interpolate(40.0, 116.0, 31.0, 121.0, 0.0)
        assertThat(lat0).isWithin(1e-9).of(40.0)
        assertThat(lng0).isWithin(1e-9).of(116.0)

        val (lat1, lng1) = FlightMath.interpolate(40.0, 116.0, 31.0, 121.0, 1.0)
        assertThat(lat1).isWithin(1e-9).of(31.0)
        assertThat(lng1).isWithin(1e-9).of(121.0)
    }

    @Test
    @DisplayName("插值中点应在线路大圆上")
    fun `插值中点`() {
        val (lat, lng) = FlightMath.interpolate(40.0, 116.0, 31.0, 121.0, 0.5)
        // 中点应在起终点之间
        assertThat(lat).isGreaterThan(31.0)
        assertThat(lat).isLessThan(40.0)
        assertThat(lng).isGreaterThan(116.0)
        assertThat(lng).isLessThan(121.0)
        // 到两端距离应近似相等
        val d1 = FlightMath.greatCircleKm(40.0, 116.0, lat, lng)
        val d2 = FlightMath.greatCircleKm(lat, lng, 31.0, 121.0)
        assertThat(abs(d1 - d2)).isLessThan(1.0)
    }

    @Test
    @DisplayName("跨 180 度经线插值不越界")
    fun `跨日期变更线插值`() {
        val (_, lng) = FlightMath.interpolate(0.0, 179.0, 0.0, -179.0, 0.5)
        assertThat(lng).isAtMost(180.0)
        assertThat(lng).isAtLeast(-180.0)
    }

    @Test
    @DisplayName("同点插值不除零")
    fun `同点插值`() {
        val (lat, lng) = FlightMath.interpolate(31.2, 121.3, 31.2, 121.3, 0.5)
        assertThat(lat).isWithin(1e-9).of(31.2)
        assertThat(lng).isWithin(1e-9).of(121.3)
    }

    // ── 6. 格式化 ────────────────────────────────────────────

    @Test
    @DisplayName("时长格式化 HH:MM:SS")
    fun `时长格式化`() {
        assertThat(FlightMath.formatDuration(0)).isEqualTo("00:00:00")
        assertThat(FlightMath.formatDuration(59)).isEqualTo("00:00:59")
        assertThat(FlightMath.formatDuration(2700)).isEqualTo("00:45:00")
        assertThat(FlightMath.formatDuration(3661)).isEqualTo("01:01:01")
        assertThat(FlightMath.formatDuration(-5)).isEqualTo("00:00:00")
    }

    @Test
    @DisplayName("分钟格式化 1h 30m")
    fun `分钟格式化`() {
        assertThat(FlightMath.formatMinutes(45)).isEqualTo("45m")
        assertThat(FlightMath.formatMinutes(60)).isEqualTo("1h")
        assertThat(FlightMath.formatMinutes(90)).isEqualTo("1h 30m")
    }

    /**
     * 常数自检：任何改动 SPEED_KMH 或 KM_PER_DEGREE 都必须在这里显式暴露。
     */
    @Test
    @DisplayName("核心常数与竞品源码一致")
    fun `核心常数自检`() {
        assertThat(FlightMath.SPEED_KMH).isEqualTo(800.0)
        assertThat(FlightMath.KM_PER_DEGREE).isEqualTo(111.32)
        assertThat(FlightMath.MIN_COS_LAT).isEqualTo(1.0E-6)
        // cos(0) = 1，确保视野公式在赤道不退化为 0
        assertThat(cos(Math.toRadians(0.0))).isWithin(1e-12).of(1.0)
    }
}
