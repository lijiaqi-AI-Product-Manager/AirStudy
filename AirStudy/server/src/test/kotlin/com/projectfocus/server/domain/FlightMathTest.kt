package com.projectfocus.server.domain

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test










@DisplayName("飞行计算")
class FlightMathTest {

    @Test
    fun `距离只由时长决定 —— 与机场无关`() {

        //   45 分钟飞 PEK→SHA（真实 1070km）与 PEK→NRT（真实 2100km），
        //   记录里都是 600km。公式里根本没有经纬度参数。
        assertThat(FlightMath.distanceKm(45)).isEqualTo(600.0)
        assertThat(FlightMath.distanceKm(30)).isEqualTo(400.0)
        assertThat(FlightMath.distanceKm(60)).isEqualTo(800.0)
        assertThat(FlightMath.distanceKm(180)).isEqualTo(2400.0)
    }

    @Test
    fun `时长越界被夹到合法区间`() {
        assertThat(FlightMath.distanceKm(0)).isEqualTo(FlightMath.distanceKm(5))
        assertThat(FlightMath.distanceKm(-100)).isEqualTo(FlightMath.distanceKm(5))
        assertThat(FlightMath.distanceKm(9999)).isEqualTo(FlightMath.distanceKm(180))
    }

    @Test
    fun `大圆距离对北京到上海约为 1070km`() {
        val d = FlightMath.greatCircleKm(40.08, 116.58, 31.20, 121.34)
        assertThat(d).isWithin(60.0).of(1070.0)
    }

    @Test
    fun `大圆距离对同一点为 0`() {
        assertThat(FlightMath.greatCircleKm(31.2, 121.3, 31.2, 121.3)).isWithin(0.001).of(0.0)
    }

    @Test
    fun `大圆距离对极点附近不产生 NaN`() {

        val d = FlightMath.greatCircleKm(89.9999, 0.0, 89.9999, 180.0)
        assertThat(d.isNaN()).isFalse()
        assertThat(d.isInfinite()).isFalse()
    }
}

@DisplayName("座位分配")
class SeatMathTest {

    @Test
    fun `座位号解析`() {
        assertThat(SeatMath.Seat.parse("3A")).isEqualTo(SeatMath.Seat(3, "A"))
        assertThat(SeatMath.Seat.parse("3a")).isEqualTo(SeatMath.Seat(3, "A"))   // 大小写不敏感
        assertThat(SeatMath.Seat.parse(" 1F ")).isEqualTo(SeatMath.Seat(1, "F"))
    }

    @Test
    fun `非法座位号返回 null 而不是抛异常`() {
        // 输入来自不可信客户端 —— 抛异常会被 StatusPages 归为 500
        listOf(null, "", "A", "1", "1G", "0A", "10A", "1AA", "1A1", "abc").forEach {
            assertThat(SeatMath.Seat.parse(it)).isNull()
        }
    }

    @Test
    fun `全部座位按排优先列优先排列`() {
        val all = SeatMath.ALL
        assertThat(all.size).isEqualTo(SeatMath.MAX_SEATS)
        assertThat(all.first()).isEqualTo(SeatMath.Seat(1, "A"))
        assertThat(all[1]).isEqualTo(SeatMath.Seat(1, "B"))
        assertThat(all[5]).isEqualTo(SeatMath.Seat(1, "F"))
        assertThat(all[6]).isEqualTo(SeatMath.Seat(2, "A"))
    }

    @Test
    fun `优先分配指定座位`() {
        val got = SeatMath.pick(SeatMath.Seat(2, "C"), emptySet())
        assertThat(got).isEqualTo(SeatMath.Seat(2, "C"))
    }

    @Test
    fun `指定座位被占时自动降级到第一个空座`() {

        //   8 人房里座位毫无稀缺性，降级才是对的交互。
        val taken = setOf(SeatMath.Seat(1, "A"), SeatMath.Seat(2, "C"))
        val got = SeatMath.pick(SeatMath.Seat(2, "C"), taken)
        assertThat(got).isEqualTo(SeatMath.Seat(1, "B"))
    }

    @Test
    fun `坐满后返回 null`() {
        assertThat(SeatMath.pick(null, SeatMath.ALL.toSet())).isNull()
    }

    @Test
    fun `严格模式下被占就返回 null`() {
        val taken = setOf(SeatMath.Seat(1, "A"))
        assertThat(SeatMath.requireExact(SeatMath.Seat(1, "A"), taken)).isNull()
        assertThat(SeatMath.requireExact(SeatMath.Seat(1, "B"), taken)).isEqualTo(SeatMath.Seat(1, "B"))
    }
}

@DisplayName("邀请码")
class InviteCodesTest {

    @Test
    fun `生成的码长度固定且只用安全字母表`() {
        repeat(200) {
            val code = InviteCodes.generate()
            assertThat(code.length).isEqualTo(InviteCodes.LENGTH)
            assertThat(code.all { it in InviteCodes.ALPHABET }).isTrue()

            assertThat(code.any { it in "0O1IL" }).isFalse()
        }
    }

    @Test
    fun `归一化去空格且转大写`() {
        assertThat(InviteCodes.normalize(" k7m2qp ")).isEqualTo("K7M2QP")
        assertThat(InviteCodes.normalize("k7-m2 qp")).isEqualTo("K7M2QP")
    }

    @Test
    fun `校验拒绝非法码`() {
        listOf("", "ABC", "ABCDEFG", "ABCDE0", "ABCDEI", "ABC1EF").forEach { bad ->
            // Truth 没有 AssertJ 的 `.as()`，用先断言再补上下文的写法
            val valid = InviteCodes.isValid(bad)
            assertThat(valid).isFalse()
            if (valid) throw AssertionError("非法邀请码被接受：$bad")
        }
        assertThat(InviteCodes.isValid("ABCDEF")).isTrue()
    }

    @Test
    fun `200 次生成没有明显重复`() {
        val set = (1..200).map { InviteCodes.generate() }.toSet()
        // 31^6 ≈ 8.87 亿，200 次抽样撞码概率约 2e-5，出现重复说明随机源有问题
        assertThat(set.size).isEqualTo(200)
    }
}
