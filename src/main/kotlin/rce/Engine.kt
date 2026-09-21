package rce

import kotlinx.serialization.json.JsonElement
import java.time.LocalDate

object Engine {

    /** 稳定决胜：优先级降序，其次规则 ID 字典序升序（与存储/返回顺序无关）。 */
    private val winComparator = compareBy<Rule>({ -it.priority }, { it.id })

    fun run(version: RuleSetVersion, fact: Map<String, JsonElement>, asOf: LocalDate): EvalResult {
        val hits = mutableListOf<Rule>()
        val irrelevant = mutableListOf<IrrelevantRule>()
        val explanations = mutableMapOf<String, ExplainNode>()

        for (rule in version.rules.sortedBy { it.id }) {
            val from = LocalDate.parse(rule.effectiveFrom)
            val to = rule.effectiveTo?.let { LocalDate.parse(it) }
            val toStr = rule.effectiveTo ?: "∞"
            if (asOf.isBefore(from) || (to != null && asOf.isAfter(to))) {
                irrelevant += IrrelevantRule(rule.id,
                    "生效区间 [$from, $toStr]（两端含端点）不包含运行日期 $asOf")
                continue
            }
            val (hit, node) = Eval.eval(rule.condition, fact)
            explanations[rule.id] = node
            if (hit) hits += rule
            else irrelevant += IrrelevantRule(rule.id, "适用条件不命中（详见解释树）")
        }

        val sorted = hits.sortedWith(winComparator)
        val steps = mutableListOf<String>()
        if (sorted.isEmpty()) {
            steps += "没有规则命中，最终结论为空"
        } else {
            steps += "命中规则 ${sorted.size} 条，按（优先级降序，规则 ID 字典序升序）稳定排序：" +
                sorted.joinToString(" > ") { "${it.id}(p=${it.priority})" }
            val winner = sorted.first()
            for (i in 1 until sorted.size) {
                val ch = sorted[i]
                steps += if (winner.priority != ch.priority) {
                    "第${i}步：'${winner.id}'(p=${winner.priority}) 对 '${ch.id}'(p=${ch.priority})：" +
                        "优先级 ${winner.priority} > ${ch.priority}，'${winner.id}' 保持领先"
                } else {
                    "第${i}步：'${winner.id}' 与 '${ch.id}' 优先级相同（p=${winner.priority}），" +
                        "按规则 ID 字典序 '${winner.id}' < '${ch.id}' 决胜（不依赖存储顺序），'${winner.id}' 保持领先"
                }
            }
            steps += "最终胜者：'${winner.id}'，结论「${winner.conclusion}」"
        }

        val winner = sorted.firstOrNull()
        val suppressed = sorted.drop(1).map { r ->
            SuppressedRule(r.id,
                if (winner != null && r.priority < winner.priority)
                    "被 '${winner.id}' 压过：优先级 ${r.priority} < ${winner.priority}"
                else "被 '${winner?.id}' 压过：优先级相同（p=${r.priority}），规则 ID 字典序靠后")
        }

        return EvalResult(
            conclusion = winner?.conclusion,
            winnerRuleId = winner?.id,
            hitRules = sorted.map { it.id },
            suppressed = suppressed,
            irrelevant = irrelevant,
            steps = steps,
            explanations = explanations,
        )
    }
}
