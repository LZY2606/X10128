package rce

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

val AppJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** 键排序后的规范化 JSON，保证指纹与导出内容确定性。 */
fun canonical(e: JsonElement): String = when (e) {
    is JsonObject -> e.entries.sortedBy { it.key }
        .joinToString(",", "{", "}") { JsonPrimitive(it.key).toString() + ":" + canonical(it.value) }
    is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
    else -> e.toString()
}

fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

fun fingerprintOf(rules: List<Rule>, exceptions: List<CoexistException>): String {
    val el = JsonObject(
        mapOf(
            "rules" to JsonArray(rules.sortedBy { it.id }.map { AppJson.encodeToJsonElement(Rule.serializer(), it) }),
            "exceptions" to JsonArray(exceptions.sortedBy { it.id }.map { AppJson.encodeToJsonElement(CoexistException.serializer(), it) }),
        )
    )
    return sha256Hex(canonical(el))
}
