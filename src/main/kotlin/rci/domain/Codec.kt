package rci.domain

import rci.json.Json
import java.time.LocalDate

/** 领域对象 ↔ 纯 JSON 数据（Map/List/String/Double/Boolean/null）。 */
object Codec {

    // ---- 写入 --------------------------------------------------------------------

    fun write(v: VValue): Any? = when (v) {
        is VNum -> if (v.value % 1.0 == 0.0 && v.value.isFinite()) v.value.toLong() else v.value
        is VStr -> v.value
        is VBool -> v.value
        VNull -> null
    }

    fun writeFact(f: Fact): Map<String, Any?> = f.toMap().mapValues { write(it.value) }

    fun writeCondition(c: Condition): Map<String, Any?> = when (c) {
        is Condition.And -> mapOf("type" to "and", "parts" to c.parts.map { writeCondition(it) })
        is Condition.Or -> mapOf("type" to "or", "parts" to c.parts.map { writeCondition(it) })
        is Condition.Not -> mapOf("type" to "not", "inner" to writeCondition(c.inner))
        is Condition.NumBetween -> mapOf("type" to "numBetween", "field" to c.field, "min" to c.min, "max" to c.max)
        is Condition.DateBetween -> mapOf("type" to "dateBetween", "field" to c.field, "from" to c.from.toString(), "to" to c.to.toString())
        is Condition.StrIn -> mapOf("type" to "strIn", "field" to c.field, "values" to c.values.toList())
        is Condition.FieldPresent -> mapOf("type" to "present", "field" to c.field)
        is Condition.FieldNotNull -> mapOf("type" to "notNull", "field" to c.field)
        is Condition.Compare -> mapOf("type" to "compare", "left" to c.left, "op" to c.op.symbol, "right" to c.right)
    }

    fun writeRange(r: DateRange) = mapOf("from" to r.from?.toString(), "to" to r.to?.toString())

    fun writeRule(r: Rule) = linkedMapOf(
        "id" to r.id,
        "condition" to writeCondition(r.condition),
        "effective" to writeRange(r.effective),
        "priority" to r.priority,
        "conclusion" to r.conclusion,
        "source" to r.source,
    )

    fun writeException(e: Exception) = linkedMapOf(
        "id" to e.id, "ruleA" to e.ruleA, "ruleB" to e.ruleB,
        "reason" to e.reason, "expiresOn" to e.expiresOn?.toString(),
        "createdAt" to e.createdAt.toString(),
    )

    fun writeVersion(v: RuleSetVersion) = linkedMapOf(
        "id" to v.id, "parentId" to v.parentId, "name" to v.name,
        "status" to v.status.name.lowercase(),
        "rules" to v.rules.map { writeRule(it) },
        "exceptions" to v.exceptions.map { writeException(it) },
        "createdAt" to v.createdAt, "publishedAt" to v.publishedAt,
        "revision" to v.revision, "fingerprint" to v.fingerprint,
    )

    fun writeSavedFact(f: SavedFact) = linkedMapOf(
        "id" to f.id, "name" to f.name, "fact" to writeFact(f.fact), "createdAt" to f.createdAt,
    )

    fun writeExplain(n: ExplainNode): Map<String, Any?> = linkedMapOf(
        "label" to n.label,
        "terminal" to n.terminal?.name?.lowercase(),
        "children" to n.children.map { writeExplain(it) },
    )

    fun writeRun(r: RunResult) = linkedMapOf(
        "runId" to r.runId, "versionId" to r.versionId, "versionFingerprint" to r.versionFingerprint,
        "factId" to r.factId, "fact" to writeFact(r.fact), "effectiveAt" to r.effectiveAt.toString(),
        "finalConclusion" to r.finalConclusion, "winnerRuleIds" to r.winnerRuleIds,
        "ruleResults" to r.ruleResults.map {
            linkedMapOf(
                "ruleId" to it.ruleId, "matched" to it.matched, "priority" to it.priority,
                "conclusion" to it.conclusion, "effectiveStatus" to it.effectiveStatus,
                "kind" to it.kind?.name?.lowercase(),
                "explanation" to writeExplain(it.explanation),
            )
        },
        "comparisonSteps" to r.comparisonSteps.map {
            mapOf("winner" to it.winner, "loser" to it.loser, "basis" to it.basis, "detail" to it.detail)
        },
        "ranAt" to r.ranAt,
    )

    fun writeConflictPair(p: ConflictPair) = linkedMapOf(
        "ruleA" to p.ruleA, "ruleB" to p.ruleB,
        "overlapWindow" to writeRange(p.overlapWindow),
        "minimalFact" to (p.minimalFact?.let { writeFact(it) }),
        "minimalEffectiveAt" to p.minimalEffectiveAt?.toString(),
        "satisfiable" to p.satisfiable,
        "explanation" to p.explanation,
        "coveredByException" to p.coveredByException?.let { writeException(it) },
    )

    // ---- 读取 --------------------------------------------------------------------

    private fun Map<String, Any?>.s(key: String) = this[key] as? String
        ?: error("字段 $key 必须是字符串")
    private fun Map<String, Any?>.d(key: String) = (this[key] as? Number)?.toDouble()
        ?: error("字段 $key 必须是数字")
    private fun Map<String, Any?>.i(key: String) = (this[key] as? Number)?.toInt()
        ?: error("字段 $key 必须是整数")

    @Suppress("UNCHECKED_CAST")
    fun readCondition(m: Map<String, Any?>): Condition = when (m["type"]) {
        "and" -> Condition.And((m["parts"] as List<Any?>).map { readCondition(it as Map<String, Any?>) })
        "or" -> Condition.Or((m["parts"] as List<Any?>).map { readCondition(it as Map<String, Any?>) })
        "not" -> Condition.Not(readCondition(m["inner"] as Map<String, Any?>))
        "numBetween" -> Condition.NumBetween(m.s("field"), m.d("min"), m.d("max"))
        "dateBetween" -> Condition.DateBetween(m.s("field"), Dates.parse(m.s("from")), Dates.parse(m.s("to")))
        "strIn" -> Condition.StrIn(m.s("field"), (m["values"] as List<Any?>).map { it as String }.toSet())
        "present" -> Condition.FieldPresent(m.s("field"))
        "notNull" -> Condition.FieldNotNull(m.s("field"))
        "compare" -> Condition.Compare(m.s("left"), readOp(m.s("op")), m.s("right"))
        else -> error("未知条件类型：${m["type"]}")
    }

    private fun readOp(s: String): Condition.Op =
        Condition.Op.entries.firstOrNull { it.symbol == s } ?: error("未知比较符：$s")

    fun readRange(m: Map<String, Any?>): DateRange = DateRange(
        (m["from"] as? String)?.let { Dates.parse(it) },
        (m["to"] as? String)?.let { Dates.parse(it) },
    )

    @Suppress("UNCHECKED_CAST")
    fun readFact(m: Map<String, Any?>): Fact = Fact.fromAny(m)

    fun readRule(m: Map<String, Any?>): Rule = Rule(
        id = m.s("id"),
        condition = readCondition(m["condition"] as Map<String, Any?>),
        effective = readRange(m["effective"] as Map<String, Any?>),
        priority = m.i("priority"),
        conclusion = m.s("conclusion"),
        source = m["source"] as? String ?: "",
    )

    @Suppress("UNCHECKED_CAST")
    fun readException(m: Map<String, Any?>): Exception = Exception(
        id = m.s("id"), ruleA = m.s("ruleA"), ruleB = m.s("ruleB"),
        reason = m["reason"] as? String ?: "",
        expiresOn = (m["expiresOn"] as? String)?.let { Dates.parse(it) },
        createdAt = (m["createdAt"] as? String)?.let { Dates.parse(it) } ?: LocalDate.now(),
    )

    @Suppress("UNCHECKED_CAST")
    fun readVersion(m: Map<String, Any?>): RuleSetVersion = RuleSetVersion(
        id = m.s("id"),
        parentId = m["parentId"] as? String,
        name = m["name"] as? String ?: "",
        status = if ((m["status"] as? String) == "published") VersionStatus.PUBLISHED else VersionStatus.DRAFT,
        rules = (m["rules"] as List<Any?>).map { readRule(it as Map<String, Any?>) },
        exceptions = (m["exceptions"] as? List<Any?>)?.map { readException(it as Map<String, Any?>) }.orEmpty(),
        createdAt = m["createdAt"] as? String ?: "",
        publishedAt = m["publishedAt"] as? String,
        revision = (m["revision"] as? Number)?.toLong() ?: 0L,
        fingerprint = m["fingerprint"] as? String,
    )

    @Suppress("UNCHECKED_CAST")
    fun readSavedFact(m: Map<String, Any?>): SavedFact = SavedFact(
        id = m.s("id"), name = m["name"] as? String ?: "",
        fact = readFact(m["fact"] as Map<String, Any?>),
        createdAt = m["createdAt"] as? String ?: "",
    )

    @Suppress("UNCHECKED_CAST")
    fun readRun(m: Map<String, Any?>): RunResult = RunResult(
        runId = m.s("runId"), versionId = m.s("versionId"),
        versionFingerprint = m["versionFingerprint"] as? String,
        factId = m["factId"] as? String,
        fact = readFact(m["fact"] as Map<String, Any?>),
        effectiveAt = Dates.parse(m.s("effectiveAt")),
        finalConclusion = m["finalConclusion"] as? String,
        winnerRuleIds = (m["winnerRuleIds"] as? List<Any?>)?.map { it as String }.orEmpty(),
        ruleResults = (m["ruleResults"] as List<Any?>).map { rr ->
            val x = rr as Map<String, Any?>
            RuleResult(
                ruleId = x.s("ruleId"),
                matched = x["matched"] as? Boolean ?: false,
                priority = (x["priority"] as Number).toInt(),
                conclusion = x["conclusion"] as? String ?: "",
                effectiveStatus = x["effectiveStatus"] as? String ?: "",
                kind = (x["kind"] as? String)?.let { RuleOutcomeKind.valueOf(it.uppercase()) },
                explanation = readExplain(x["explanation"] as Map<String, Any?>),
            )
        },
        comparisonSteps = (m["comparisonSteps"] as List<Any?>).map { ss ->
            val x = ss as Map<String, Any?>
            ComparisonStep(x.s("winner"), x.s("loser"), x.s("basis"), x.s("detail"))
        },
        ranAt = m["ranAt"] as? String ?: "",
    )

    private fun readExplain(m: Map<String, Any?>): ExplainNode = ExplainNode(
        label = m["label"] as? String ?: "",
        terminal = (m["terminal"] as? String)?.let { Ternary.valueOf(it.uppercase()) },
        children = (m["children"] as? List<Any?>)?.map { readExplain(it as Map<String, Any?>) }.orEmpty(),
    )

    // ---- 便捷序列化 --------------------------------------------------------------

    fun encode(v: Any?): String = Json.stringify(v, indent = true)
    fun decode(s: String): Any? = Json.parse(s)
}
