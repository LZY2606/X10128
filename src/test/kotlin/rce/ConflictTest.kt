package rce

import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConflictTest {

    private fun rule(id: String, p: Int, concl: String, cond: Condition, to: String? = null) = Rule(
        id = id, condition = cond, effectiveFrom = "2026-01-01", effectiveTo = to,
        priority = p, conclusion = concl, source = "t",
    )

    @Test
    fun `重叠区间且结论不同产生冲突并给出可触发的最小事实`() {
        val r1 = rule("r1", 1, "A", Condition.NumRange("x", 0.0, true, 100.0, true))
        val r2 = rule("r2", 1, "B", Condition.NumRange("x", 50.0, true, 200.0, true))
        val list = Conflict.detect(listOf(r1, r2), emptyList(), LocalDate.of(2026, 6, 1))
        assertEquals(1, list.size)
        val c = list[0]
        assertEquals("r1", c.ruleA); assertEquals("r2", c.ruleB)
        // 最小事实必须真的能同时触发两条规则
        val v = RuleSetVersion("d", 0, VersionStatus.DRAFT, null, listOf(r1, r2), emptyList(), "t")
        val res = Engine.run(v, c.witness, LocalDate.parse(c.witnessAsOf))
        assertEquals(setOf("r1", "r2"), res.hitRules.toSet(), "见证事实必须同时命中两条规则")
        assertEquals(mapOf("x" to JsonPrimitive(50.0)), c.witness)
    }

    @Test
    fun `不相交区间不产生冲突`() {
        val r1 = rule("r1", 1, "A", Condition.NumRange("x", 0.0, true, 10.0, false))
        val r2 = rule("r2", 1, "B", Condition.NumRange("x", 10.0, true, 20.0, true))
        assertTrue(Conflict.detect(listOf(r1, r2), emptyList(), LocalDate.of(2026, 6, 1)).isEmpty())
    }

    @Test
    fun `结论相同或生效区间不重叠不算冲突`() {
        val r1 = rule("r1", 1, "A", Condition.Exists("x"), to = "2026-06-30")
        val r2 = rule("r2", 1, "A", Condition.Exists("y"))
        assertTrue(Conflict.detect(listOf(r1, r2), emptyList(), LocalDate.of(2026, 6, 1)).isEmpty(), "结论相同")
        val r3 = rule("r3", 1, "B", Condition.Exists("y"), to = "2026-01-31")
        val r4 = rule("r4", 1, "C", Condition.Exists("y")).copy(effectiveFrom = "2026-02-01")
        assertTrue(Conflict.detect(listOf(r3, r4), emptyList(), LocalDate.of(2026, 6, 1)).isEmpty(), "生效区间不重叠")
    }

    @Test
    fun `例外覆盖冲突且过期后重新暴露`() {
        val r1 = rule("r1", 1, "A", Condition.Exists("x"))
        val r2 = rule("r2", 1, "B", Condition.Exists("x"))
        val ex = CoexistException("ex-1", "r1", "r2", "业务确认可并存", "2026-06-30")
        val before = Conflict.detect(listOf(r1, r2), listOf(ex), LocalDate.of(2026, 6, 30))
        assertEquals("ex-1", before[0].coveredByException)
        assertNull(before[0].expiredException)
        val after = Conflict.detect(listOf(r1, r2), listOf(ex), LocalDate.of(2026, 7, 1))
        assertNull(after[0].coveredByException)
        assertEquals("ex-1", after[0].expiredException, "过期后冲突重新暴露")
    }

    @Test
    fun `过期例外与新例外并存时新例外继续覆盖`() {
        val r1 = rule("r1", 1, "A", Condition.Exists("x"))
        val r2 = rule("r2", 1, "B", Condition.Exists("x"))
        val exOld = CoexistException("ex-1", "r1", "r2", "旧理由", "2026-06-30")
        val exNew = CoexistException("ex-2", "r2", "r1", "新理由", "2026-12-31")
        val list = Conflict.detect(listOf(r1, r2), listOf(exOld, exNew), LocalDate.of(2026, 7, 1))
        assertEquals("ex-2", list[0].coveredByException)
        assertNull(list[0].expiredException)
    }

    @Test
    fun `字符串集合交集为空则不相交`() {
        val r1 = rule("r1", 1, "A", Condition.StrIn("t", setOf("a", "b")))
        val r2 = rule("r2", 1, "B", Condition.StrIn("t", setOf("c")))
        assertTrue(Conflict.detect(listOf(r1, r2), emptyList(), LocalDate.of(2026, 6, 1)).isEmpty())
        val r3 = rule("r3", 1, "B", Condition.StrIn("t", setOf("b")))
        val list = Conflict.detect(listOf(r1, r3), emptyList(), LocalDate.of(2026, 6, 1))
        assertEquals(1, list.size)
        assertEquals(mapOf("t" to JsonPrimitive("b")), list[0].witness)
    }
}
