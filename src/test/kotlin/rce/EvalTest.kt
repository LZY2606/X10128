package rce

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.LocalDate

class EvalTest {

    private fun factOf(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = mapOf(*pairs)

    @Test
    fun `缺失字段与显式 null 必须区分`() {
        val cond = Condition.NumRange("a", 0.0, true, 10.0, true)
        val (rMissing, nMissing) = Eval.eval(cond, emptyMap())
        val (rNull, nNull) = Eval.eval(cond, factOf("a" to JsonNull))
        val (rVal, _) = Eval.eval(cond, factOf("a" to JsonPrimitive(5)))
        assertFalse(rMissing); assertFalse(rNull); assertTrue(rVal)
        assertTrue(nMissing.text.contains("缺失"), nMissing.text)
        assertTrue(nNull.text.contains("显式 null"), nNull.text)
        assertFalse(nNull.text.contains("缺失（"), "null 的解释不应说成缺失")

        // exists：显式 null 算存在，缺失不算
        assertTrue(Eval.eval(Condition.Exists("a"), factOf("a" to JsonNull)).first)
        assertFalse(Eval.eval(Condition.Exists("a"), emptyMap()).first)
        // isNull：显式 null 命中，缺失与有值都不命中
        assertTrue(Eval.eval(Condition.IsNull("a"), factOf("a" to JsonNull)).first)
        assertFalse(Eval.eval(Condition.IsNull("a"), emptyMap()).first)
        assertFalse(Eval.eval(Condition.IsNull("a"), factOf("a" to JsonPrimitive(1))).first)
    }

    @Test
    fun `区间端点开闭在结果与解释中体现`() {
        val cond = Condition.NumRange("x", 10.0, true, 20.0, false)
        assertTrue(Eval.eval(cond, factOf("x" to JsonPrimitive(10))).first, "下界含端点")
        assertFalse(Eval.eval(cond, factOf("x" to JsonPrimitive(20))).first, "上界不含端点")
        val (_, node) = Eval.eval(cond, factOf("x" to JsonPrimitive(20)))
        assertTrue(node.text.contains("不含端点"), node.text)
        assertTrue(node.text.contains("上界") && node.text.contains("不通过"), node.text)

        val d = Condition.DateRange("d", "2026-01-01", false, "2026-12-31", true)
        assertFalse(Eval.eval(d, factOf("d" to JsonPrimitive("2026-01-01"))).first, "下界不含端点")
        assertTrue(Eval.eval(d, factOf("d" to JsonPrimitive("2026-12-31"))).first, "上界含端点")
        val (_, dn) = Eval.eval(d, factOf("d" to JsonPrimitive("2026-01-01")))
        assertTrue(dn.text.contains("不含端点"), dn.text)
    }

    @Test
    fun `布尔组合与字符串集合与跨字段比较`() {
        val cond = Condition.And(listOf(
            Condition.Or(listOf(
                Condition.StrIn("tier", setOf("gold", "platinum")),
                Condition.NumRange("amount", 1000.0, true, null, true),
            )),
            Condition.Not(Condition.Exists("blocked")),
            Condition.Compare("limit", CmpOp.GE, "amount"),
        ))
        val f1 = factOf("tier" to JsonPrimitive("gold"), "limit" to JsonPrimitive(500), "amount" to JsonPrimitive(500))
        assertTrue(Eval.eval(cond, f1).first)
        val f2 = factOf("tier" to JsonPrimitive("gold"), "limit" to JsonPrimitive(400), "amount" to JsonPrimitive(500))
        assertFalse(Eval.eval(cond, f2).first, "limit < amount 不命中")
        val f3 = factOf("tier" to JsonPrimitive("silver"), "amount" to JsonPrimitive(2000),
            "limit" to JsonPrimitive(3000), "blocked" to JsonNull)
        assertFalse(Eval.eval(cond, f3).first, "blocked 存在，NOT 不命中")
    }

    private fun rule(id: String, p: Int) = Rule(
        id = id, condition = Condition.Exists("x"), effectiveFrom = "2026-01-01",
        priority = p, conclusion = "C-$id", source = "t",
    )

    @Test
    fun `相同优先级按规则 ID 字典序稳定决胜且与输入顺序无关`() {
        val rules = listOf(rule("r-b", 5), rule("r-a", 5), rule("r-c", 3))
        val v1 = RuleSetVersion("d1", 0, VersionStatus.DRAFT, null, rules, emptyList(), "t")
        val v2 = v1.copy(id = "d2", rules = rules.reversed())
        val fact = factOf("x" to JsonPrimitive(1))
        val r1 = Engine.run(v1, fact, LocalDate.of(2026, 6, 1))
        val r2 = Engine.run(v2, fact, LocalDate.of(2026, 6, 1))
        assertEquals("r-a", r1.winnerRuleId)
        assertEquals("r-a", r2.winnerRuleId)
        assertEquals(r1.steps, r2.steps, "决胜步骤顺序必须稳定")
        assertEquals(listOf("r-b", "r-c"), r1.suppressed.map { it.ruleId })
        assertTrue(r1.suppressed[0].reason.contains("字典序"), r1.suppressed[0].reason)
        assertTrue(r1.suppressed[1].reason.contains("优先级 3 < 5"), r1.suppressed[1].reason)
        assertTrue(r1.steps.any { it.contains("不依赖存储顺序") })
        assertTrue(r1.irrelevant.isEmpty())
    }

    @Test
    fun `生效区间外的规则列为无关`() {
        val r = rule("r1", 1).copy(effectiveFrom = "2026-03-01", effectiveTo = "2026-03-31")
        val v = RuleSetVersion("d", 0, VersionStatus.DRAFT, null, listOf(r), emptyList(), "t")
        val res = Engine.run(v, factOf("x" to JsonPrimitive(1)), LocalDate.of(2026, 4, 1))
        assertEquals(null, res.conclusion)
        assertTrue(res.irrelevant[0].reason.contains("生效区间"), res.irrelevant[0].reason)
        // 区间端点含端点
        val res2 = Engine.run(v, factOf("x" to JsonPrimitive(1)), LocalDate.of(2026, 3, 31))
        assertEquals("C-r1", res2.conclusion)
    }
}
