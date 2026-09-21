package rci.domain

import java.time.LocalDate

/**
 * 规则运行引擎。
 * 决胜顺序是完全确定的：
 *   1) 只考虑生效区间覆盖 effectiveAt 且条件命中的规则；
 *   2) 结论相同的命中规则归为一组（同结论永远并存，不需要比较）；
 *   3) 组间先比最高优先级（数值大者胜），明确高低的比较记为「按优先级」；
 *   4) 最高优先级相同的组之间，用组内规则 ID 的字典序做确定性决胜
 *      （绝不依赖数据库/存储返回顺序），并在比较步骤里显式说明这是同优先级平局。
 */
class Engine(private val clock: () -> LocalDate = { LocalDate.now() }) {

    data class Run(
        val finalConclusion: String?,
        val winners: List<String>,
        val ruleResults: List<RuleResult>,
        val steps: List<ComparisonStep>,
    )

    fun runRules(rules: List<Rule>, fact: Fact, effectiveAt: LocalDate): Run {
        val sorted = rules.sortedWith(compareBy({ -it.priority }, { it.id }))
        val evaluated = sorted.map { rule ->
            val inWindow = rule.effective.contains(effectiveAt)
            val node = Evaluator.eval(rule.condition, fact)
            val effStatus = when {
                !inWindow && rule.effective.from != null && effectiveAt.isBefore(rule.effective.from) ->
                    "未生效：${effectiveAt} 早于起点 ${rule.effective.from}（起点包含）"
                !inWindow && rule.effective.to != null && effectiveAt.isAfter(rule.effective.to) ->
                    "已失效：${effectiveAt} 晚于终点 ${rule.effective.to}（终点包含）"
                else -> "生效中：${effectiveAt} 落在闭区间 [${rule.effective}]"
            }
            Triple(rule, inWindow && node.terminal == Ternary.TRUE, RuleResult(
                ruleId = rule.id,
                matched = inWindow && node.terminal == Ternary.TRUE,
                priority = rule.priority,
                conclusion = rule.conclusion,
                effectiveStatus = effStatus,
                kind = null,
                explanation = node,
            ))
        }

        val matched = evaluated.filter { it.second }.map { it.first }
        val groups = matched.groupBy { it.conclusion }
            .map { (conclusion, rs) -> Group(conclusion, rs.sortedBy { it.id }) }

        val steps = mutableListOf<ComparisonStep>()
        val activeGroups = groups.toMutableList()
        val defeatedGroups = mutableListOf<Group>()
        while (activeGroups.size > 1) {
            val g1 = activeGroups[0]; val g2 = activeGroups[1]
            val (winnerGroup, loserGroup, basis, detail) = compareGroups(g1, g2)
            steps.add(ComparisonStep(
                winner = winnerGroup.rules.first().id,
                loser = loserGroup.rules.first().id,
                basis = basis,
                detail = detail,
            ))
            activeGroups.remove(loserGroup)
            defeatedGroups.add(loserGroup)
        }
        val champion = activeGroups.firstOrNull()

        val results = evaluated.map { (rule, isMatch, base) ->
            val kind = when {
                !isMatch -> null
                champion != null && rule.conclusion == champion.conclusion ->
                    if (rule.priority == champion.topPriority() && champion.rules.size > 1) RuleOutcomeKind.COWINNER
                    else if (champion.rules.any { it.id == rule.id }) RuleOutcomeKind.WINNER
                    else RuleOutcomeKind.WINNER
                else -> RuleOutcomeKind.OVERRIDDEN
            }
            base.copy(kind = kind)
        }
        return Run(
            finalConclusion = champion?.conclusion,
            winners = champion?.rules?.map { it.id }.orEmpty(),
            ruleResults = results,
            steps = steps,
        )
    }

    private data class Group(val conclusion: String, val rules: List<Rule>) {
        fun topPriority(): Int = rules.maxOf { it.priority }
        fun tieKey(): String = rules.filter { it.priority == topPriority() }.minOf { it.id }
    }

    private data class GroupCmp(val winner: Group, val loser: Group, val basis: String, val detail: String)

    private fun compareGroups(g1: Group, g2: Group): GroupCmp {
        val p1 = g1.topPriority(); val p2 = g2.topPriority()
        return if (p1 != p2) {
            val (w, l) = if (p1 > p2) g1 to g2 else g2 to g1
            GroupCmp(w, l, "按优先级",
                "结论“${w.conclusion}”最高优先级 $p1 高于“${l.conclusion}”的 $p2（数值大者胜）")
        } else {
            val k1 = g1.tieKey(); val k2 = g2.tieKey()
            val (w, l) = if (k1 <= k2) g1 to g2 else g2 to g1
            GroupCmp(w, l, "同优先级·规则 ID 字典序决胜",
                "结论“${w.conclusion}”与“${l.conclusion}”最高优先级同为 $p1；" +
                    "不靠返回顺序，改为比较各自最高优先级规则的最小 ID：“${w.tieKey()}” < “${l.tieKey()}”，前者胜")
        }
    }

    // ---- 静态冲突分析 -------------------------------------------------------------

    fun analyzeConflicts(
        version: RuleSetVersion,
        registeredExceptions: List<Exception>,
        today: LocalDate = clock(),
    ): ConflictReport {
        val rules = version.rules.sortedBy { it.id }
        val exceptions = if (version.status == VersionStatus.PUBLISHED) version.exceptions else registeredExceptions
        val pairs = mutableListOf<ConflictPair>()
        for (i in rules.indices) {
            for (j in (i + 1) until rules.size) {
                val a = rules[i]; val b = rules[j]
                if (a.conclusion == b.conclusion) continue
                val window = a.effective.intersectionOrNull(b.effective) ?: continue
                val solved = ConflictSolver.canConflict(a, b)
                val exc = exceptions.firstOrNull { it.pairKey(a.id, b.id) && it.activeOn(today) }
                if (solved.satisfiable || exc != null) {
                    pairs.add(ConflictPair(
                        ruleA = a.id,
                        ruleB = b.id,
                        overlapWindow = window,
                        minimalFact = solved.fact,
                        minimalEffectiveAt = solved.effectiveAt,
                        satisfiable = solved.satisfiable,
                        explanation = if (solved.satisfiable)
                            "规则 ${a.id} 与 ${b.id} 可在生效重叠区间 $window 同时命中并给出不同结论" +
                                "（“${a.conclusion}” vs “${b.conclusion}”）；附上的最小事实可触发该冲突。"
                        else "当前条件约束下无法构造同时命中的事实，但仍登记了并存例外。",
                        coveredByException = exc,
                    ))
                }
            }
        }
        return ConflictReport(version.id, pairs)
    }

    /** 发布闸门：存在可触发且没有有效例外覆盖的冲突则禁止发布。 */
    fun blockingConflicts(
        version: RuleSetVersion,
        registeredExceptions: List<Exception>,
        today: LocalDate = clock(),
    ): List<ConflictPair> =
        analyzeConflicts(version, registeredExceptions, today).pairs.filter {
            it.satisfiable && it.coveredByException == null
        }

    // ---- 版本影响预览 -------------------------------------------------------------

    fun previewImpact(
        before: RuleSetVersion,
        after: RuleSetVersion,
        savedFacts: List<SavedFact>,
        effectiveAt: LocalDate = clock(),
    ): ImpactPreview {
        val impacts = savedFacts.map { sf ->
            val rb = runRules(before.rules, sf.fact, effectiveAt)
            val ra = runRules(after.rules, sf.fact, effectiveAt)
            val caused = linkedSetOf<String>()
            val afterById = after.rules.associateBy { it.id }
            val beforeById = before.rules.associateBy { it.id }
            for (r in after.rules) {
                val old = beforeById[r.id]
                if (old == null || old != r) caused += r.id
            }
            for (r in before.rules) {
                if (r.id !in afterById) caused += r.id
            }
            // 只保留与两边决胜实际相关的变更规则
            val involved = rb.winners.toSet() + ra.winners.toSet() +
                rb.ruleResults.filter { it.kind == RuleOutcomeKind.OVERRIDDEN }.map { it.ruleId } +
                ra.ruleResults.filter { it.kind == RuleOutcomeKind.OVERRIDDEN }.map { it.ruleId }
            val relevantCaused = caused.filter { it in involved }
            RuleImpact(
                factId = sf.id,
                factName = sf.name,
                beforeConclusion = rb.finalConclusion,
                afterConclusion = ra.finalConclusion,
                changed = rb.finalConclusion != ra.finalConclusion,
                beforeWinner = rb.winners,
                afterWinner = ra.winners,
                causedByRuleIds = relevantCaused,
            )
        }
        return ImpactPreview(before.id, after.id, impacts)
    }
}


