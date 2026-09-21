package rce

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate

object Eval {

    private fun rangeText(min: String?, minInc: Boolean, max: String?, maxInc: Boolean): String =
        (if (minInc) "[" else "(") + (min ?: "-∞") + ", " + (max ?: "+∞") + (if (maxInc) "]" else ")")

    private fun boundPhrase(min: String?, minInc: Boolean, max: String?, maxInc: Boolean): String {
        val parts = mutableListOf<String>()
        if (min != null) parts += "下界 $min（${if (minInc) "含端点" else "不含端点"}）"
        if (max != null) parts += "上界 $max（${if (maxInc) "含端点" else "不含端点"}）"
        return if (parts.isEmpty()) "无界区间" else parts.joinToString("，")
    }

    private sealed class FieldState {
        object Missing : FieldState()
        object ExplicitNull : FieldState()
        data class Value(val v: JsonElement) : FieldState()
    }

    private fun fieldState(fact: Map<String, JsonElement>, field: String): FieldState =
        if (!fact.containsKey(field)) FieldState.Missing
        else when (val v = fact[field]) {
            null, JsonNull -> FieldState.ExplicitNull
            else -> FieldState.Value(v)
        }

    fun eval(cond: Condition, fact: Map<String, JsonElement>): Pair<Boolean, ExplainNode> = when (cond) {
        is Condition.And -> {
            val kids = cond.items.map { eval(it, fact) }
            val r = kids.all { it.first }
            val t = if (cond.items.isEmpty()) "AND（空，恒命中）"
            else "AND：${kids.count { it.first }}/${kids.size} 个子条件命中"
            r to ExplainNode("and", t, r, kids.map { it.second })
        }
        is Condition.Or -> {
            val kids = cond.items.map { eval(it, fact) }
            val r = kids.any { it.first }
            val t = if (cond.items.isEmpty()) "OR（空，恒不命中）"
            else "OR：${kids.count { it.first }}/${kids.size} 个子条件命中"
            r to ExplainNode("or", t, r, kids.map { it.second })
        }
        is Condition.Not -> {
            val (cr, cn) = eval(cond.item, fact)
            !cr to ExplainNode("not", "NOT：子条件${if (cr) "命中 → 整体不命中" else "不命中 → 整体命中"}", !cr, listOf(cn))
        }
        is Condition.NumRange -> {
            val range = rangeText(cond.min?.toString(), cond.minInclusive, cond.max?.toString(), cond.maxInclusive)
            when (val st = fieldState(fact, cond.field)) {
                FieldState.Missing -> false to ExplainNode("numRange",
                    "字段 '${cond.field}' 缺失（非显式 null），区间 $range 判定不命中", false)
                FieldState.ExplicitNull -> false to ExplainNode("numRange",
                    "字段 '${cond.field}' 为显式 null（与缺失区分），不参与数字区间 $range 比较，不命中", false)
                is FieldState.Value -> {
                    val n = (st.v as? JsonPrimitive)?.doubleOrNull
                    if (n == null) false to ExplainNode("numRange",
                        "字段 '${cond.field}' 值 ${st.v} 非数字，区间 $range 判定不命中", false)
                    else {
                        val loOk = cond.min == null || (if (cond.minInclusive) n >= cond.min else n > cond.min)
                        val hiOk = cond.max == null || (if (cond.maxInclusive) n <= cond.max else n < cond.max)
                        val r = loOk && hiOk
                        val t = "字段 '${cond.field}' = $n，${boundPhrase(cond.min?.toString(), cond.minInclusive, cond.max?.toString(), cond.maxInclusive)}" +
                            "；下界${if (loOk) "通过" else "不通过"}、上界${if (hiOk) "通过" else "不通过"} → ${if (r) "命中" else "不命中"}"
                        r to ExplainNode("numRange", t, r)
                    }
                }
            }
        }
        is Condition.DateRange -> {
            val range = rangeText(cond.min, cond.minInclusive, cond.max, cond.maxInclusive)
            when (val st = fieldState(fact, cond.field)) {
                FieldState.Missing -> false to ExplainNode("dateRange",
                    "字段 '${cond.field}' 缺失（非显式 null），日期区间 $range 判定不命中", false)
                FieldState.ExplicitNull -> false to ExplainNode("dateRange",
                    "字段 '${cond.field}' 为显式 null（与缺失区分），不参与日期区间 $range 比较，不命中", false)
                is FieldState.Value -> {
                    val s = (st.v as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val d = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    if (d == null) false to ExplainNode("dateRange",
                        "字段 '${cond.field}' 值 ${st.v} 非 ISO 日期，区间 $range 判定不命中", false)
                    else {
                        val lo = cond.min?.let { LocalDate.parse(it) }
                        val hi = cond.max?.let { LocalDate.parse(it) }
                        val loOk = lo == null || (if (cond.minInclusive) !d.isBefore(lo) else d.isAfter(lo))
                        val hiOk = hi == null || (if (cond.maxInclusive) !d.isAfter(hi) else d.isBefore(hi))
                        val r = loOk && hiOk
                        val t = "字段 '${cond.field}' = $s，${boundPhrase(cond.min, cond.minInclusive, cond.max, cond.maxInclusive)}" +
                            "；下界${if (loOk) "通过" else "不通过"}、上界${if (hiOk) "通过" else "不通过"} → ${if (r) "命中" else "不命中"}"
                        r to ExplainNode("dateRange", t, r)
                    }
                }
            }
        }
        is Condition.StrIn -> {
            val setStr = cond.values.sorted().joinToString(", ", "{", "}")
            when (val st = fieldState(fact, cond.field)) {
                FieldState.Missing -> false to ExplainNode("strIn",
                    "字段 '${cond.field}' 缺失（非显式 null），集合 $setStr 判定不命中", false)
                FieldState.ExplicitNull -> false to ExplainNode("strIn",
                    "字段 '${cond.field}' 为显式 null（与缺失区分），集合 $setStr 判定不命中", false)
                is FieldState.Value -> {
                    val s = (st.v as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val r = s != null && cond.values.contains(s)
                    r to ExplainNode("strIn",
                        "字段 '${cond.field}' = ${st.v}，${if (r) "属于" else "不属于"}集合 $setStr → ${if (r) "命中" else "不命中"}", r)
                }
            }
        }
        is Condition.Exists -> when (val st = fieldState(fact, cond.field)) {
            FieldState.Missing -> false to ExplainNode("exists", "字段 '${cond.field}' 缺失 → 不命中", false)
            FieldState.ExplicitNull -> true to ExplainNode("exists",
                "字段 '${cond.field}' 存在（值为显式 null，键存在即算存在）→ 命中", true)
            is FieldState.Value -> true to ExplainNode("exists",
                "字段 '${cond.field}' 存在，值 ${st.v} → 命中", true)
        }
        is Condition.IsNull -> when (fieldState(fact, cond.field)) {
            FieldState.Missing -> false to ExplainNode("isNull",
                "字段 '${cond.field}' 缺失：缺失 ≠ 显式 null → 不命中", false)
            FieldState.ExplicitNull -> true to ExplainNode("isNull",
                "字段 '${cond.field}' 为显式 null → 命中", true)
            is FieldState.Value -> false to ExplainNode("isNull",
                "字段 '${cond.field}' 有非 null 值 → 不命中", false)
        }
        is Condition.Compare -> {
            val l = fieldState(fact, cond.leftField)
            val r = fieldState(fact, cond.rightField)
            fun num(st: FieldState): Double? = (st as? FieldState.Value)?.let { (it.v as? JsonPrimitive)?.doubleOrNull }
            val ln = num(l); val rn = num(r)
            if (ln == null || rn == null) {
                val why = buildList {
                    if (ln == null) add("'${cond.leftField}'" + if (l is FieldState.Missing) "缺失" else if (l is FieldState.ExplicitNull) "为显式 null" else "非数字")
                    if (rn == null) add("'${cond.rightField}'" + if (r is FieldState.Missing) "缺失" else if (r is FieldState.ExplicitNull) "为显式 null" else "非数字")
                }.joinToString("；")
                false to ExplainNode("cmp", "跨字段比较 ${cond.leftField} ${cond.op} ${cond.rightField}：$why，无法比较 → 不命中", false)
            } else {
                val res = when (cond.op) {
                    CmpOp.LT -> ln < rn; CmpOp.LE -> ln <= rn; CmpOp.EQ -> ln == rn
                    CmpOp.GE -> ln >= rn; CmpOp.GT -> ln > rn
                }
                res to ExplainNode("cmp",
                    "跨字段比较：'${cond.leftField}'=$ln ${cond.op} '${cond.rightField}'=$rn → ${if (res) "命中" else "不命中"}", res)
            }
        }
    }
}
