package rci.domain

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

internal data class Lit(val cond: Condition, val positive: Boolean)

internal typealias Term = List<Lit>

/** 把条件展开为析取范式；每个 Term 是一组合取的文字。 */
internal object Dnf {
    fun dnf(c: Condition, positive: Boolean = true): List<Term> = when (c) {
        is Condition.And -> if (positive) crossAnd(c.parts.map { dnf(it, true) })
        else crossOr(c.parts.map { dnf(it, false) })
        is Condition.Or -> if (positive) crossOr(c.parts.map { dnf(it, true) })
        else crossAnd(c.parts.map { dnf(it, false) })
        is Condition.Not -> dnf(c.inner, !positive)
        else -> listOf(listOf(Lit(c, positive)))
    }

    private fun crossAnd(groups: List<List<Term>>): List<Term> {
        var acc = listOf<Term>(emptyList())
        for (g in groups) {
            val next = mutableListOf<Term>()
            for (a in acc) for (b in g) next.add(a + b)
            acc = next.take(MAX_TERMS)
        }
        return acc
    }
    private fun crossOr(groups: List<List<Term>>): List<Term> = groups.flatten().take(MAX_TERMS)
    private const val MAX_TERMS = 512
}

data class SolveResult(val satisfiable: Boolean, val fact: Fact?, val effectiveAt: LocalDate?)

object ConflictSolver {

    fun canConflict(ruleA: Rule, ruleB: Rule): SolveResult {
        if (ruleA.conclusion == ruleB.conclusion) return SolveResult(false, null, null)
        val window = ruleA.effective.intersectionOrNull(ruleB.effective) ?: return SolveResult(false, null, null)
        val termsA = Dnf.dnf(ruleA.condition)
        val termsB = Dnf.dnf(ruleB.condition)
        var best: Pair<Fact, LocalDate>? = null
        outer@ for (ta in termsA) {
            for (tb in termsB) {
                val attempt = solveTerm(ta + tb, window)
                if (attempt != null) {
                    val score = attempt.first.fields().size
                    if (best == null || score < best!!.first.fields().size) best = attempt
                    // 0 个字段已是理论最小，直接返回
                    if (score == 0) break@outer
                }
            }
        }
        return if (best == null) SolveResult(false, null, null)
        else SolveResult(true, best!!.first, best!!.second)
    }

    // ---- 单项（一组合取文字）求解 -------------------------------------------------

    private enum class FState { PRESENT_NONNULL, NULL, ABSENT, ANY }

    private class Dom {
        var state: FState = FState.ANY
        var numLo: Double = Double.NEGATIVE_INFINITY
        var numHi: Double = Double.POSITIVE_INFINITY
        var dateLo: LocalDate? = null
        var dateHi: LocalDate? = null
        var allowed: Set<String>? = null
        val disallowed = mutableSetOf<String>()
        val usedInCompare = mutableSetOf<Condition.Compare>()
        var forcedString: String? = null
        var forcedNumber: Double? = null
    }

    private fun solveTerm(lits: Term, window: DateRange): Pair<Fact, LocalDate>? {
        val doms = linkedMapOf<String, Dom>()
        fun dom(f: String) = doms.getOrPut(f) { Dom() }

        for (lit in lits) {
            val c = lit.cond; val pos = lit.positive
            when (c) {
                is Condition.NumBetween -> with(dom(c.field)) {
                    if (state == FState.ABSENT || state == FState.NULL) return null
                    state = FState.PRESENT_NONNULL
                    if (pos) { numLo = max(numLo, c.min); numHi = min(numHi, c.max) }
                    // 闭区间之外：合取情况下无法安全缩成单边区间，交给构造后求值器兜底
                    if (numLo > numHi) return null
                }
                is Condition.DateBetween -> with(dom(c.field)) {
                    if (state == FState.ABSENT || state == FState.NULL) return null
                    state = FState.PRESENT_NONNULL
                    if (pos) {
                        val nl = maxDate(dateLo, c.from); val nh = minDate(dateHi, c.to)
                        dateLo = nl; dateHi = nh
                        if (nl != null && nh != null && nl.isAfter(nh)) return null
                    }
                }
                is Condition.StrIn -> with(dom(c.field)) {
                    if (pos) {
                        if (state == FState.ABSENT || state == FState.NULL) return null
                        state = FState.PRESENT_NONNULL
                        allowed = allowed?.intersect(c.values) ?: c.values.toSet()
                        if (allowed!!.isEmpty()) return null
                    } else {
                        if (state == FState.PRESENT_NONNULL) {
                            allowed?.let { if (it.all { v -> v in c.values }) return null }
                        }
                        disallowed += c.values
                    }
                }
                is Condition.FieldPresent -> mergeState(dom(c.field), if (pos) FState.PRESENT_NONNULL else FState.ABSENT) ?: return null
                is Condition.FieldNotNull -> mergeState(dom(c.field), if (pos) FState.PRESENT_NONNULL else FState.NULL) ?: return null
                is Condition.Compare -> {
                    if (!pos) return null // 被否定的跨字段比较不构造
                    val sameFieldFail = !sameFieldFeasible(c)
                    if (sameFieldFail) return null
                    dom(c.left).state = promotePresent(dom(c.left).state)
                    dom(c.right).state = promotePresent(dom(c.right).state)
                    dom(c.left).usedInCompare += c
                    dom(c.right).usedInCompare += c
                }
                else -> error("DNF 叶子之外不应出现 ${c::class}")
            }
        }

        // 先实例化所有字段
        val values = linkedMapOf<String, Any?>()
        for ((f, d) in doms) {
            val v = instantiate(f, d) ?: return null
            values[f] = v
        }
        if (!satisfyCompares(values, doms)) return null

        val fact = Fact.fromAny(values)
        // 求值器兜底验证（也覆盖否定区间等未显式缩界的情况）
        for (lit in lits) {
            val node = Evaluator.eval(lit.cond, fact)
            val want = lit.positive
            if (node.terminal != if (want) Ternary.TRUE else Ternary.FALSE) return null
        }
        val effectiveAt = window.from ?: window.to ?: LocalDate.now()
        return fact to effectiveAt
    }

    private fun mergeState(d: Dom, required: FState): FState? {
        val merged = when (d.state) {
            FState.ANY -> required
            required -> required
            FState.PRESENT_NONNULL -> if (required == FState.PRESENT_NONNULL) FState.PRESENT_NONNULL else null
            FState.NULL -> if (required == FState.NULL) FState.NULL else null
            FState.ABSENT -> if (required == FState.ABSENT) FState.ABSENT else null
        }
        if (merged == null) return null
        d.state = merged
        return merged
    }

    private fun promotePresent(s: FState): FState =
        if (s == FState.ANY || s == FState.PRESENT_NONNULL) FState.PRESENT_NONNULL else s

    private fun sameFieldFeasible(c: Condition.Compare): Boolean {
        if (c.left != c.right) return true
        return when (c.op) {
            Condition.Op.GT, Condition.Op.LT, Condition.Op.NEQ -> false
            Condition.Op.GTE, Condition.Op.LTE, Condition.Op.EQ -> true
        }
    }

    private fun instantiate(field: String, d: Dom): Any? = when (d.state) {
        FState.ABSENT -> MISSING
        FState.NULL -> null
        else -> pickValue(field, d)
    }

    private val MISSING = Any()

    private fun pickValue(field: String, d: Dom): Any? {
        // 字符串优先
        d.allowed?.let { set ->
            val s = set.firstOrNull()
            if (s != null) { d.forcedString = s; return s }
        }
        if (d.dateLo != null || d.dateHi != null) {
            return (d.dateLo ?: d.dateHi).toString()
        }
        if (d.numLo != Double.NEGATIVE_INFINITY || d.numHi != Double.POSITIVE_INFINITY) {
            val v = pickNumber(d.numLo, d.numHi)
            d.forcedNumber = v
            return v
        }
        // 只有否定字符串约束/存在性约束
        if (d.disallowed.isNotEmpty()) {
            var k = 0
            while ("v$k" in d.disallowed) k++
            return "v$k"
        }
        if (d.usedInCompare.isNotEmpty()) {
            val v = 0.0
            d.forcedNumber = v
            return v
        }
        return ""
    }

    private fun pickNumber(lo: Double, hi: Double): Double {
        // 优先整数与端点，让最小事实可读
        val candidates = listOf(lo, hi, 0.0, 1.0, -1.0)
        for (c in candidates) {
            if (c.isFinite() && c in lo..hi && c % 1.0 == 0.0) return c
        }
        if (lo.isFinite()) return lo
        if (hi.isFinite()) return hi
        return 0.0
    }

    /** 求解跨字段比较：按比较边调整数值/日期；无解返回 false。 */
    private fun satisfyCompares(values: MutableMap<String, Any?>, doms: Map<String, Dom>): Boolean {
        val edges = doms.values.flatMap { it.usedInCompare }.distinct()
        if (edges.isEmpty()) return true

        // 两轮：默认按字段名升序 / 降序尝试（覆盖 x<y 与 x>y 的简单链）
        repeat(2) { round ->
            val trial = LinkedHashMap(values)
            val ordered = if (round == 0) doms.keys.toList() else doms.keys.reversed()
            var ok = true
            for (f in ordered) {
                val d = doms[f]!!
                for (cmp in d.usedInCompare.sortedBy { it.op.symbol }) {
                    if (cmp.left != f) continue
                    if (!applyEdge(cmp, trial, doms)) { ok = false; break }
                }
                if (!ok) break
            }
            if (ok) {
                for ((cmp) in edges.map { it to Unit }) {
                    if (!evalCompareRaw(cmp, trial)) { ok = false; break }
                }
                if (ok) {
                    values.clear(); values.putAll(trial); return true
                }
            }
        }
        return false
    }

    private fun applyEdge(cmp: Condition.Compare, trial: MutableMap<String, Any?>, doms: Map<String, Dom>): Boolean {
        val l = trial[cmp.left]
        val r = trial[cmp.right]
        if (l == MISSING || r == MISSING || l == null || r == null) return false
        if (cmp.op == Condition.Op.EQ || cmp.op == Condition.Op.NEQ) return cmp.op == Condition.Op.NEQ || l == r
        val wantGreater = cmp.op == Condition.Op.GT || cmp.op == Condition.Op.GTE
        val inclusive = cmp.op == Condition.Op.GTE || cmp.op == Condition.Op.LTE
        val nums = l is Number && r is Number
        val curCmp = if (nums) (l as Number).toDouble().compareTo((r as Number).toDouble())
        else compareDateStrings(l.toString(), r.toString()) ?: return false
        val satisfied = when (cmp.op) {
            Condition.Op.GT -> curCmp > 0; Condition.Op.GTE -> curCmp >= 0
            Condition.Op.LT -> curCmp < 0; Condition.Op.LTE -> curCmp <= 0
            else -> true
        }
        if (satisfied) return true
        // 调整右字段
        val targetField = cmp.right
        val targetDom = doms[targetField]!!
        val newValue: Any? = if (nums) {
            val base = (l as Number).toDouble()
            val want = if (wantGreater) base - (if (inclusive) 0 else 1) else base + (if (inclusive) 0 else 1)
            if (!withinNumber(targetDom, want)) return false
            want
        } else {
            val baseD = Dates.parse(l.toString())
            val want = if (wantGreater) baseD.minusDays(if (inclusive) 0 else 1)
            else baseD.plusDays(if (inclusive) 0 else 1)
            if (!withinDate(targetDom, want)) return false
            want.toString()
        }
        trial[targetField] = newValue
        return true
    }

    private fun evalCompareRaw(cmp: Condition.Compare, trial: Map<String, Any?>): Boolean {
        val fact = Fact.fromAny(trial.filterValues { it !== MISSING })
        return Evaluator.matches(cmp, fact)
    }

    private fun withinNumber(d: Dom, v: Double): Boolean =
        v in d.numLo..d.numHi && (d.dateLo == null && d.dateHi == null)

    private fun withinDate(d: Dom, v: LocalDate): Boolean =
        (d.dateLo == null || !v.isBefore(d.dateLo)) && (d.dateHi == null || !v.isAfter(d.dateHi))

    private fun compareDateStrings(a: String, b: String): Int? =
        runCatching { Dates.parse(a).compareTo(Dates.parse(b)) }.getOrNull()

    private fun maxDate(a: LocalDate?, b: LocalDate?): LocalDate? =
        when { a == null -> b; b == null -> a; else -> if (a.isAfter(b)) a else b }
    private fun minDate(a: LocalDate?, b: LocalDate?): LocalDate? =
        when { a == null -> b; b == null -> a; else -> if (a.isBefore(b)) a else b }
}
