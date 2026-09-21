package rci.domain

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 事实字段值。JSON null 与「字段缺失」在模型层就是两种东西：
 *  - [VNull]  对应显式 null（map 中存在键，值为 null）
 *  - map 中没有键，才是「缺失」，[Fact.get] 返回 null Kotlin 引用
 */
sealed interface VValue {
    val asAny: Any?
}

data class VNum(val value: Double) : VValue {
    constructor(n: Number) : this(n.toDouble())
    override val asAny: Any get() = if (value % 1.0 == 0.0 && value.isFinite()) value.toLong() else value
}

data class VStr(val value: String) : VValue {
    override val asAny: Any get() = value
}

data class VBool(val value: Boolean) : VValue {
    override val asAny: Any get() = value
}

/** 显式 null。 */
data object VNull : VValue {
    override val asAny: Any? get() = null
}

class Fact(initial: Map<String, VValue> = emptyMap()) {
    private val map: Map<String, VValue> = LinkedHashMap(initial)

    /** 返回 null 表示字段缺失；返回 [VNull] 表示显式 null。 */
    fun get(field: String): VValue? = map[field]
    operator fun contains(field: String): Boolean = map.containsKey(field)
    fun fields(): Set<String> = map.keys
    fun toMap(): Map<String, VValue> = map
    override fun equals(other: Any?): Boolean = other is Fact && other.map == map
    override fun hashCode(): Int = map.hashCode()
    override fun toString(): String = map.toString()

    companion object {
        fun of(vararg pairs: Pair<String, Any?>): Fact =
            Fact(pairs.associate { (k, v) -> k to wrap(v) })

        fun fromAny(map: Map<String, Any?>): Fact =
            Fact(map.mapValues { wrap(it.value) })

        fun wrap(v: Any?): VValue = when (v) {
            null -> VNull
            is VValue -> v
            is Number -> VNum(v.toDouble())
            is Boolean -> VBool(v)
            is String -> VStr(v)
            else -> VStr(v.toString())
        }
    }
}

/**
 * 条件。叶子条件都携带字段名（跨字段比较带右字段名）。
 * 区间均为闭区间；端点是否包含在解释文本里明确写出。
 */
sealed interface Condition {
    data class And(val parts: List<Condition>) : Condition {
        constructor(vararg parts: Condition) : this(parts.toList())
    }
    data class Or(val parts: List<Condition>) : Condition {
        constructor(vararg parts: Condition) : this(parts.toList())
    }
    data class Not(val inner: Condition) : Condition

    data class NumBetween(val field: String, val min: Double, val max: Double) : Condition
    data class DateBetween(val field: String, val from: LocalDate, val to: LocalDate) : Condition
    data class StrIn(val field: String, val values: Set<String>) : Condition
    data class FieldPresent(val field: String) : Condition
    data class FieldNotNull(val field: String) : Condition
    enum class Op(val symbol: String) { GT(">"), GTE(">="), LT("<"), LTE("<="), EQ("=="), NEQ("!=") }
    data class Compare(val left: String, val op: Op, val right: String) : Condition
}

/** 生效区间，两端均包含；null 端表示不限。 */
data class DateRange(val from: LocalDate?, val to: LocalDate?) {
    init {
        if (from != null && to != null) require(!from.isAfter(to)) { "生效区间起点不能晚于终点" }
    }
    fun contains(d: LocalDate): Boolean =
        (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to))
    fun overlaps(other: DateRange): Boolean {
        if (from != null && other.to != null && from.isAfter(other.to)) return false
        if (to != null && other.from != null && to.isBefore(other.from)) return false
        return true
    }
    fun intersectionOrNull(other: DateRange): DateRange? =
        if (!overlaps(other)) null
        else DateRange(
            maxNullable(from, other.from) { a, b -> if (a.isAfter(b)) a else b },
            minNullable(to, other.to) { a, b -> if (a.isBefore(b)) a else b },
        )
    override fun toString(): String = "${from ?: "−∞"} ~ ${to ?: "+∞"}"

    companion object {
        val UNBOUNDED = DateRange(null, null)
        private fun maxNullable(a: LocalDate?, b: LocalDate?, pick: (LocalDate, LocalDate) -> LocalDate) =
            when { a == null -> b; b == null -> a; else -> pick(a, b) }
        private fun minNullable(a: LocalDate?, b: LocalDate?, pick: (LocalDate, LocalDate) -> LocalDate) =
            when { a == null -> b; b == null -> a; else -> pick(a, b) }
    }
}

data class Rule(
    val id: String,
    val condition: Condition,
    /** 生效区间（按运行日期 effectiveAt 判定），端点包含。 */
    val effective: DateRange,
    val priority: Int,
    val conclusion: String,
    val source: String,
)

/** 并存例外：允许 ruleA 与 ruleB 在 [DateRange] 内同时命中给出不同结论。 */
data class Exception(
    val id: String,
    val ruleA: String,
    val ruleB: String,
    val reason: String,
    val expiresOn: LocalDate?,
    val createdAt: LocalDate,
) {
    fun pairKey(a: String, b: String): Boolean {
        val (x, y) = ordered(a, b)
        val (p, q) = ordered(ruleA, ruleB)
        return x == p && y == q
    }
    fun activeOn(d: LocalDate): Boolean = expiresOn == null || !d.isAfter(expiresOn)
    private fun ordered(a: String, b: String) = if (a <= b) a to b else b to a
}

enum class VersionStatus { DRAFT, PUBLISHED }

data class RuleSetVersion(
    val id: String,
    val parentId: String?,
    val name: String,
    val status: VersionStatus,
    val rules: List<Rule>,
    /** 发布时快照进去的例外；草稿里为空（冲突分析实时查登记的例外）。 */
    val exceptions: List<Exception>,
    val createdAt: String,
    val publishedAt: String?,
    /** 乐观锁：每次保存/发布递增。 */
    val revision: Long,
    /** 发布版本的内容指纹（SHA-256），草稿为 null。 */
    val fingerprint: String?,
)

data class SavedFact(
    val id: String,
    val name: String,
    val fact: Fact,
    val createdAt: String,
)

/** 一条候选规则在决胜过程中的处置说明。 */
data class ComparisonStep(
    val winner: String,
    val loser: String,
    val basis: String,
    val detail: String,
)

enum class RuleOutcomeKind { WINNER, OVERRIDDEN, COWINNER, SUPPRESSED_BY_EXCEPTION }

data class RuleResult(
    val ruleId: String,
    val matched: Boolean,
    val priority: Int,
    val conclusion: String,
    val effectiveStatus: String,
    val kind: RuleOutcomeKind?,
    val explanation: ExplainNode,
)

data class RunResult(
    val runId: String,
    val versionId: String,
    val versionFingerprint: String?,
    val factId: String?,
    val fact: Fact,
    val effectiveAt: LocalDate,
    val finalConclusion: String?,
    val winnerRuleIds: List<String>,
    val ruleResults: List<RuleResult>,
    val comparisonSteps: List<ComparisonStep>,
    val ranAt: String,
)

data class ConflictReport(
    val versionId: String,
    val pairs: List<ConflictPair>,
)

data class ConflictPair(
    val ruleA: String,
    val ruleB: String,
    val overlapWindow: DateRange,
    val minimalFact: Fact?,
    val minimalEffectiveAt: LocalDate?,
    val satisfiable: Boolean,
    val explanation: String,
    val coveredByException: Exception?,
)

data class RuleImpact(
    val factId: String?,
    val factName: String,
    val beforeConclusion: String?,
    val afterConclusion: String?,
    val changed: Boolean,
    val beforeWinner: List<String>,
    val afterWinner: List<String>,
    val causedByRuleIds: List<String>,
)

data class ImpactPreview(
    val fromVersionId: String,
    val toVersionId: String,
    val impacts: List<RuleImpact>,
)

object Dates {
    fun iso(d: LocalDate): String = d.toString()
    fun parse(s: String): LocalDate = try {
        LocalDate.parse(s)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("日期必须是 yyyy-MM-dd 格式：$s", e)
    }
}
