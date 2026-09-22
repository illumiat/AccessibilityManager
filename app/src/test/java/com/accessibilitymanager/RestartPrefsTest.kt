package com.accessibilitymanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「设置串纯函数」的回归测试 —— **瞄准历史缺陷面，不是凑覆盖率**。
 *
 * ## 为什么先测这里
 *
 * 项目的核心功能是「正常重启不掉线」，而历史缺陷 **M1 / M2 全出在设置串的语法判定上**：
 *
 * - **M1**：用 `contains` 判服务是否已启用 —— `"pkg/.Svc"` 是 `"pkg/.SvcExtra"` 的**子串**，
 *   于是误判已启用、跳过恢复 → **掉线不恢复**。现由 [RestartPrefs.isEnabledIn] 的「按 `:` 段精确相等」取代。
 * - **M2**：用 `String.replace` 移除服务 —— `"pkg/.SvcX:pkg/.Svc"` 移除 `"pkg/.Svc"` 会把
 *   `"X:"` 留成垃圾段。现由 [RestartPrefs.removeService] 的「按 `:` 段过滤重建」取代。
 *
 * ## 一处**刻意保留的怪癖**（不得"修好"）
 *
 * [RestartPrefs.prependService] 对空串会留下尾冒号（`prependService("", "a/b.C") == "a/b.C:"`）。
 * 这与 Worker / daemonService 的既有写法一致，且 `splitIds` 跳过空段、下游按段精确匹配，
 * 故不影响判定 —— 改掉它反而是行为漂移。**「怪癖记录：刻意设计」。**
 */
class RestartPrefsTest {

    // ---------- M1：子串误判（历史缺陷，必须永远为 false） ----------

    @Test
    fun `M1 短 id 不得命中更长的同类 id（子串误判）`() {
        assertFalse(RestartPrefs.isEnabledIn("pkg/.SvcExtra", "pkg/.Svc"))
        assertFalse(RestartPrefs.isEnabledIn("pkg/.Svc", "pkg/.SvcExtra"))
        assertFalse(RestartPrefs.isEnabledIn("pkg/.SvcA:pkg/.SvcB", "pkg/.Svc"))
    }

    @Test
    fun `M1 互为前缀的两个服务互不命中`() {
        val s = "pkg/.SvcA:pkg/.SvcB"
        assertTrue(RestartPrefs.isEnabledIn(s, "pkg/.SvcA"))
        assertTrue(RestartPrefs.isEnabledIn(s, "pkg/.SvcB"))
        assertFalse(RestartPrefs.isEnabledIn(s, "pkg/.Svc"))
        assertFalse(RestartPrefs.isEnabledIn(s, "pkg/.SvcC"))
    }

    // ---------- 短形态 / 展开形态等价（flattenToShortString 的两种写法指同一服务） ----------

    @Test
    fun `短形态 serviceId 可命中展开形态段`() {
        assertTrue(RestartPrefs.isEnabledIn("pkg/pkg.Cls", "pkg/.Cls"))
        assertTrue(RestartPrefs.isEnabledIn("pkg/.Cls", "pkg/.Cls"))
    }

    @Test
    fun `serviceId 传展开形态属越界用法（固定现状，不是背书）`() {
        // 契约（expandedForm 的 KDoc）：输入是**短形态** id，"pkg/.Cls" -> "pkg/pkg.Cls"；
        // 非 pkg/cls 形态原样返回。传**展开形态**不在契约内 —— 会再拼一次成 "pkg/pkgpkg.Cls"。
        // 实践中 serviceId 全部来自 AccessibilityServiceInfo.getId() = 短形态，故不出现。
        // 此处**只固定现状**（真机回归前不动保真链语义），不是认可该行为合理。
        assertFalse(RestartPrefs.isEnabledIn("pkg/.Cls", "pkg/pkg.Cls"))
    }

    @Test
    fun `removeService 同时按两种形态移除`() {
        assertEquals("other/x.Y", RestartPrefs.removeService("pkg/pkg.Cls:other/x.Y", "pkg/.Cls"))
        assertEquals("other/x.Y", RestartPrefs.removeService("pkg/.Cls:other/x.Y", "pkg/.Cls"))
    }

    @Test
    fun `不含斜杠的 id 原样比较（不展开）`() {
        assertTrue(RestartPrefs.isEnabledIn("noSlash", "noSlash"))
        assertFalse(RestartPrefs.isEnabledIn("noSlash", "noSlash2"))
    }

    // ---------- M2：remove 子串链的垃圾段（历史缺陷，必须精确到段边界） ----------

    @Test
    fun `M2 移除不得留下垃圾段（前缀 id 不被误伤）`() {
        // 旧的 replace 子串链会把 "pkg/.SvcX:pkg/.Svc" 移除 "pkg/.Svc" 后留下 "X:" 垃圾
        assertEquals("pkg/.SvcX", RestartPrefs.removeService("pkg/.SvcX:pkg/.Svc", "pkg/.Svc"))
    }

    @Test
    fun `M2 移除唯一项后是空串（不是残留分隔符）`() {
        assertEquals("", RestartPrefs.removeService("pkg/.Svc", "pkg/.Svc"))
    }

    @Test
    fun `removeService 保序且不动其它段`() {
        assertEquals(
            "a/b.C:d/e.F",
            RestartPrefs.removeService("a/b.C:x/y.Z:d/e.F", "x/y.Z"),
        )
    }

    // ---------- 往返：prepend 后 remove 应回到原串 ----------

    @Test
    fun `prepend 再 remove 回到原串（目标不在原串中时）`() {
        val s = "a/b.C:d/e.F"
        assertEquals(s, RestartPrefs.removeService(RestartPrefs.prependService(s, "x/y.Z"), "x/y.Z"))
    }

    @Test
    fun `prependService 对空串留下尾冒号（刻意保留的怪癖）`() {
        // 不得"修好"：与 Worker / daemonService 既有写法一致；splitIds 跳过空段，下游判定不受影响。
        assertEquals("a/b.C:", RestartPrefs.prependService("", "a/b.C"))
    }

    // ---------- splitIds：串切分的唯一实现 ----------

    @Test
    fun `splitIds 跳过空段（含连续冒号与首尾冒号）`() {
        assertEquals(listOf("a/b.C", "d/e.F"), RestartPrefs.splitIds("a/b.C::d/e.F:"))
        assertEquals(listOf<String>(), RestartPrefs.splitIds(""))
        assertEquals(listOf<String>(), RestartPrefs.splitIds(":"))
    }

    @Test
    fun `splitIds 保序（top 顺序即排序键，乱序会改变列表排列）`() {
        assertEquals(
            listOf("first/a.A", "second/b.B", "third/c.C"),
            RestartPrefs.splitIds("first/a.A:second/b.B:third/c.C"),
        )
    }

    // ---------- 空值与空串：一律判未启用 ----------

    @Test
    fun `空值与空串一律判未启用`() {
        assertFalse(RestartPrefs.isEnabledIn(null, "a/b.C"))
        assertFalse(RestartPrefs.isEnabledIn("", "a/b.C"))
        assertFalse(RestartPrefs.isEnabledIn("a/b.C", null))
        assertFalse(RestartPrefs.isEnabledIn("a/b.C", ""))
    }

    @Test
    fun `containsService 与 isEnabledIn 同口径（收口后不得分叉）`() {
        val s = "pkg/pkg.Cls:pkg/.SvcExtra"
        assertEquals(
            RestartPrefs.isEnabledIn(s, "pkg/.Svc"),
            RestartPrefs.containsService(s, "pkg/.Svc"),
        )
        assertEquals(
            RestartPrefs.isEnabledIn(s, "pkg/.Cls"),
            RestartPrefs.containsService(s, "pkg/.Cls"),
        )
    }

    // ---------- clampPeriod：周期取值域 ----------

    @Test
    fun `clampPeriod 把越界值夹回区间`() {
        assertEquals(RestartPrefs.MIN_PERIOD_MIN, RestartPrefs.clampPeriod(0L))
        assertEquals(RestartPrefs.MIN_PERIOD_MIN, RestartPrefs.clampPeriod(-5L))
        assertEquals(RestartPrefs.MIN_PERIOD_MIN, RestartPrefs.clampPeriod(30L))
        assertEquals(RestartPrefs.MAX_PERIOD_MIN, RestartPrefs.clampPeriod(999_999L))
        assertEquals(RestartPrefs.MAX_PERIOD_MIN, RestartPrefs.clampPeriod(43_200L))
    }

    @Test
    fun `clampPeriod 区间内原样返回`() {
        assertEquals(60L, RestartPrefs.clampPeriod(60L))
        assertEquals(1_440L, RestartPrefs.clampPeriod(1_440L))
    }
}
