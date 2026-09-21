package rce

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

object Conflict {

    /**
     * 仅当条件可展开为纯 AND 叶子树时提取叶子约束；
     * 含 OR/NOT 时返回 null，表示保守地假设与任何条件都可能重叠。
     */
    private fun extractLeaves(cond: Condition): List<Condition>? = when (cond) {
        is Condition.And -> {
            val lists = cond.items.map { extractLeaves(it) ?: return null }
            lists.flatten()
        }
        is Condition.Or, is Condition.Not -> null
        else -> listOf(cond)
    }

    private class FieldAcc {
        var numMin: Double? = null; var numMinInc = true
        var numMax: Double? = null; var numMaxInc = true
        var dateMin: LocalDate? = null; var dateMinInc = true
        var dateMax: LocalDate? = null; var dateMaxInc = true
        var strSet: MutableSet<String>? = null
        var mustExist = false
        var mustBeNull = false
        val compares = mutableListOf<Condition.Compare>()
    }

    private fun merge(leaves: List<Condition>): Map<String, FieldAcc> {
        val byField = sortedMapOf<String, FieldAcc>()
        fun acc(f: String) = byField.getOrPut(f) { FieldAcc() }
        for (leaf in leaves) when (leaf) {
            is Condition.NumRange -> acc(leaf.field).also { a ->
                if (leaf.min != null && (a.numMin == null || leaf.min > a.numMin!! ||
                        (leaf.min == a.numMin && !leaf.minInclusive))) {
                    a.numMin = leaf.min; a.numMinInc = leaf.minInclusive
                }
                if (leaf.max != null && (a.numMax == null || leaf.max < a.numMax!! ||
                        (leaf.max == a.numMax && !leaf.maxInclusive))) {
                    a.numMax = leaf.max; a.numMaxInc = leaf.maxInclusive
                }
            }
            is Condition.DateRange -> acc(leaf.field).also { a ->
                val lo = leaf.min?.let { LocalDate.parse(it) }
                val hi = leaf.max?.let { LocalDate.parse(it) }
                if (lo != null && (a.dateMin == null || lo.isAfter(a.dateMin) ||
                        (lo == a.dateMin && !leaf.minInclusive))) {
                    a.dateMin = lo; a.dateMinInc = leaf.minInclusive
                }
                if (hi != null && (a.dateMax == null || hi.isBefore(a.dateMax) ||
                        (hi == a.dateMax && !leaf.maxInclusive))) {
                    a.dateMax = hi; a.dateMaxInc = leaf.maxInclusive
                }
            }
            is Condition.StrIn -> acc(leaf.field).also { a ->
                a.strSet = (a.strSet?.intersect(leaf.values) ?: leaf.values).toMutableSet()
            }
            is Condition.Exists -> acc(leaf.field).mustExist = true
            is Condition.IsNull -> acc(leaf.field).mustBeNull = true
            is Condition.Compare -> {
                acc(leaf.leftField).compares += leaf
                acc(leaf.rightField)
            }
            else -> {}
        }
        return byField
    }

    /** 为合并后的约束挑选确定性的最小见证事实；矛盾时返回 null。 */
    private fun witness(fields: Map<String, FieldAcc>): Map<String, JsonElement>? {
        val out = sortedMapOf<String, JsonElement>()
        for ((field, a) in fields) {
            val hasNum = a.numMin != null || a.numMax != null
            val hasDate = a.dateMin != null || a.dateMax != null
            val hasStr = a.strSet != null
            if (a.mustBeNull && (hasNum || hasDate || hasStr)) return null
            if (listOf(hasNum, hasDate, hasStr).count { it } > 1) return null
            when {
                a.mustBeNull -> out[field] = JsonNull
                hasStr -> {
                    val v = a.strSet!!.sorted().firstOrNull() ?: return null
                    out[field] = JsonPrimitive(v)
                }
                hasNum -> {
                    val lo = a.numMin; val hi = a.numMax
                    if (lo != null && hi != null && (lo > hi || (lo == hi && (!a.numMinInc || !a.numMaxInc)))) return null
                    val v = when {
                        lo != null -> if (a.numMinInc) lo else Math.nextUp(lo)
                        hi != null -> if (a.numMaxInc) hi else Math.nextDown(hi)
                        else -> 0.0
                    }
                    if (hi != null && (if (a.numMaxInc) v > hi else v >= hi)) return null
                    out[field] = JsonPrimitive(v)
                }
                hasDate -> {
                    val lo = a.dateMin; val hi = a.dateMax
                    if (lo != null && hi != null && (lo.isAfter(hi) || (lo == hi && (!a.dateMinInc || !a.dateMaxInc)))) return null
                    val v = when {
                        lo != null -> if (a.dateMinInc) lo else lo.plusDays(1)
                        hi != null -> if (a.dateMaxInc) hi else hi.minusDays(1)
                        else -> LocalDate.of(2000, 1, 1)
                    }
                    if (hi != null && (if (a.dateMaxInc) v.isAfter(hi) else !v.isBefore(hi))) return null
                    out[field] = JsonPrimitive(v.toString())
                }
                a.mustExist -> out[field] = JsonNull
            }
        }
        // 跨字段比较：为未赋值字段补默认值并校验
        val allCompares = fields.values.flatMap { it.compares }.distinctBy { it.leftField + it.op + it.rightField }
        for (c in allCompares) {
            fun numOf(f: String): Double? = (out[f] as? JsonPrimitive)?.content?.toDoubleOrNull()
            var l = numOf(c.leftField); var r = numOf(c.rightField)
            if (l == null && r == null) { l = 0.0; r = 0.0 }
            if (l == null) l = when (c.op) {
                CmpOp.LT, CmpOp.LE -> r!! - 1; else -> r!! + 1
            }
            if (r == null) r = when (c.op) {
                CmpOp.GT, CmpOp.GE -> l - 1; CmpOp.LT, CmpOp.LE -> l + 1; CmpOp.EQ -> l
            }
            if (c.op == CmpOp.EQ) r = l
            val ok = when (c.op) {
                CmpOp.LT -> l < r; CmpOp.LE -> l <= r; CmpOp.EQ -> l == r
                CmpOp.GE -> l >= r; CmpOp.GT -> l > r
            }
            if (!ok) return null
            if (!out.containsKey(c.leftField)) out[c.leftField] = JsonPrimitive(l)
            if (!out.containsKey(c.rightField)) out[c.rightField] = JsonPrimitive(r)
        }
        return out
    }

    private fun overlap(a: Rule, b: Rule): Pair<LocalDate, LocalDate?>? {
        val from = maxOf(LocalDate.parse(a.effectiveFrom), LocalDate.parse(b.effectiveFrom))
        val tos = listOfNotNull(a.effectiveTo, b.effectiveTo).map { LocalDate.parse(it) }
        val to = tos.minOrNull()
        if (to != null && from.isAfter(to)) return null
        return from to to
    }

    /** 检测同一版本内可能同时命中且结论不同的规则对，并生成最小见证事实。 */
    fun detect(rules: List<Rule>, exceptions: List<CoexistException>, asOf: LocalDate): List<ConflictPair> {
        val sorted = rules.sortedBy { it.id }
        val out = mutableListOf<ConflictPair>()
        for (i in sorted.indices) for (j in i + 1 until sorted.size) {
            val a = sorted[i]; val b = sorted[j]
            if (a.conclusion == b.conclusion) continue
            val (ovFrom, ovTo) = overlap(a, b) ?: continue
            val la = extractLeaves(a.condition)
            val lb = extractLeaves(b.condition)
            val leaves = listOfNotNull(la, lb).flatten()
            val wit = witness(merge(leaves)) ?: continue
            val matched = exceptions.filter {
                setOf(it.ruleA, it.ruleB) == setOf(a.id, b.id)
            }
            val active = matched.firstOrNull { !asOf.isAfter(LocalDate.parse(it.expiresOn)) }
            val expired = matched.filter { asOf.isAfter(LocalDate.parse(it.expiresOn)) }
            out += ConflictPair(
                ruleA = a.id, ruleB = b.id,
                conclusionA = a.conclusion, conclusionB = b.conclusion,
                overlapFrom = ovFrom.toString(), overlapTo = ovTo?.toString(),
                witness = wit, witnessAsOf = ovFrom.toString(),
                coveredByException = active?.id,
                expiredException = if (active == null) expired.firstOrNull()?.id else null,
            )
        }
        return out
    }
}
