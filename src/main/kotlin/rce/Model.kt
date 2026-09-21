package rce

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
sealed class Condition {
    @Serializable
    @SerialName("and")
    data class And(val items: List<Condition>) : Condition()

    @Serializable
    @SerialName("or")
    data class Or(val items: List<Condition>) : Condition()

    @Serializable
    @SerialName("not")
    data class Not(val item: Condition) : Condition()

    /** 数字区间；min/max 为 null 表示该端开口 */
    @Serializable
    @SerialName("numRange")
    data class NumRange(
        val field: String,
        val min: Double? = null,
        val minInclusive: Boolean = true,
        val max: Double? = null,
        val maxInclusive: Boolean = true,
    ) : Condition()

    /** 日期区间（ISO-8601，yyyy-MM-dd） */
    @Serializable
    @SerialName("dateRange")
    data class DateRange(
        val field: String,
        val min: String? = null,
        val minInclusive: Boolean = true,
        val max: String? = null,
        val maxInclusive: Boolean = true,
    ) : Condition()

    /** 字符串集合 */
    @Serializable
    @SerialName("strIn")
    data class StrIn(val field: String, val values: Set<String>) : Condition()

    /** 字段存在性：键存在即视为存在（显式 null 也算存在） */
    @Serializable
    @SerialName("exists")
    data class Exists(val field: String) : Condition()

    /** 显式 null：键存在且值为 null 才命中；缺失不算 */
    @Serializable
    @SerialName("isNull")
    data class IsNull(val field: String) : Condition()

    /** 跨字段数值比较 */
    @Serializable
    @SerialName("cmp")
    data class Compare(val leftField: String, val op: CmpOp, val rightField: String) : Condition()
}

@Serializable
enum class CmpOp { LT, LE, EQ, GE, GT }

@Serializable
data class Rule(
    val id: String,
    val description: String = "",
    val condition: Condition,
    val effectiveFrom: String,
    val effectiveTo: String? = null,
    val priority: Int,
    val conclusion: String,
    val source: String = "",
)

@Serializable
data class CoexistException(
    val id: String,
    val ruleA: String,
    val ruleB: String,
    val reason: String,
    val expiresOn: String,
)

@Serializable
enum class VersionStatus { DRAFT, PUBLISHED }

@Serializable
data class RuleSetVersion(
    val id: String,
    val seq: Int,
    val status: VersionStatus,
    val baseSeq: Int? = null,
    val rules: List<Rule>,
    val exceptions: List<CoexistException> = emptyList(),
    val createdAt: String,
    val fingerprint: String = "",
)

@Serializable
data class SavedFact(
    val id: String,
    val name: String,
    val payload: Map<String, JsonElement>,
)

@Serializable
data class ExplainNode(
    val kind: String,
    val text: String,
    val result: Boolean,
    val children: List<ExplainNode> = emptyList(),
)

@Serializable
data class SuppressedRule(val ruleId: String, val reason: String)

@Serializable
data class IrrelevantRule(val ruleId: String, val reason: String)

@Serializable
data class EvalResult(
    val conclusion: String?,
    val winnerRuleId: String?,
    val hitRules: List<String>,
    val suppressed: List<SuppressedRule>,
    val irrelevant: List<IrrelevantRule>,
    val steps: List<String>,
    val explanations: Map<String, ExplainNode>,
)

@Serializable
data class RunRecord(
    val id: String,
    val versionSeq: Int,
    val versionFingerprint: String,
    val factName: String?,
    val fact: Map<String, JsonElement>,
    val asOf: String,
    val result: EvalResult,
    val createdAt: String,
)

@Serializable
data class ConflictPair(
    val ruleA: String,
    val ruleB: String,
    val conclusionA: String,
    val conclusionB: String,
    val overlapFrom: String,
    val overlapTo: String?,
    val witness: Map<String, JsonElement>,
    val witnessAsOf: String,
    val coveredByException: String? = null,
    val expiredException: String? = null,
)

@Serializable
data class ImpactEntry(
    val factId: String,
    val factName: String,
    val fromConclusion: String?,
    val toConclusion: String?,
    val fromWinner: String?,
    val toWinner: String?,
    val cause: String,
)
