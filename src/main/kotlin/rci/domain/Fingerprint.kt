package rci.domain

import java.security.MessageDigest

/**
 * 版本内容指纹：只依赖规则（条件/生效区间/优先级/结论/来源）与发布时快照的例外。
 * 与 id、revision、createdAt 等无关，因此导出再导入后指纹保持一致。
 * 序列化字段按固定顺序输出，保证跨进程稳定。
 */
object Fingerprint {
    fun of(version: RuleSetVersion): String {
        val payload = mapOf(
            "rules" to version.rules.sortedBy { it.id }.map {
                linkedMapOf<String, Any?>(
                    "id" to it.id,
                    "condition" to Codec.writeCondition(it.condition),
                    "effective" to Codec.writeRange(it.effective),
                    "priority" to it.priority,
                    "conclusion" to it.conclusion,
                    "source" to it.source,
                )
            },
            "exceptions" to version.exceptions.sortedBy { it.id }.map { Codec.writeException(it) },
        )
        val canonical = rci.json.Json.stringify(payload, indent = false)
        val md = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return md.joinToString("") { "%02x".format(it) }
    }
}
