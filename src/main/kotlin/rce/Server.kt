package rce

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.LocalDate
import java.util.concurrent.Executors

class Server(private val store: Store, host: String, port: Int) {

    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)

    init {
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/") { ex -> handle(ex) }
    }

    fun start() {
        server.start()
        println("规则冲突解释器已启动: http://${server.address.hostString}:${server.address.port}")
    }

    fun stop() = server.stop(0)

    val actualPort: Int get() = server.address.port

    private fun handle(ex: HttpExchange) {
        try {
            route(ex)
        } catch (e: OptimisticLockException) {
            respond(ex, 409, err(e.message ?: "乐观锁冲突"))
        } catch (e: PublishConflictException) {
            respond(ex, 409, JsonObject(mapOf(
                "error" to JsonPrimitive(e.message ?: "存在冲突"),
                "conflicts" to AppJson.encodeToJsonElement(ListSerializer(ConflictPair.serializer()), e.conflicts),
            )))
        } catch (e: NotFoundException) {
            respond(ex, 404, err(e.message ?: "未找到"))
        } catch (e: IllegalArgumentException) {
            respond(ex, 400, err(e.message ?: "请求非法"))
        } catch (e: IllegalStateException) {
            respond(ex, 400, err(e.message ?: "状态非法"))
        } catch (e: Exception) {
            respond(ex, 500, err("服务器错误：${e.message}"))
        } finally {
            ex.close()
        }
    }

    private fun err(msg: String) = JsonObject(mapOf("error" to JsonPrimitive(msg)))

    private fun body(ex: HttpExchange): JsonObject {
        val text = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        if (text.isBlank()) return JsonObject(emptyMap())
        return AppJson.parseToJsonElement(text).jsonObject
    }

    private fun query(ex: HttpExchange, key: String): String? =
        ex.requestURI.rawQuery?.split("&")?.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")?.let { URLDecoder.decode(it, Charsets.UTF_8) }

    private fun route(ex: HttpExchange) {
        val method = ex.requestMethod
        val path = ex.requestURI.path.trimEnd('/').ifEmpty { "/" }
        val seg = path.split("/").filter { it.isNotEmpty() }

        if (path == "/" && method == "GET") {
            val html = Server::class.java.getResource("/web/index.html")!!.readBytes()
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, html.size.toLong())
            ex.responseBody.write(html)
            return
        }
        if (seg.firstOrNull() != "api") throw NotFoundException("未知路径：$path")

        val out: JsonElement = when {
            seg == listOf("api", "state") && method == "GET" ->
                AppJson.encodeToJsonElement(Store.State.serializer(), store.snapshot())

            seg == listOf("api", "versions") && method == "POST" -> {
                val base = body(ex)["baseSeq"]?.jsonPrimitive?.int
                AppJson.encodeToJsonElement(RuleSetVersion.serializer(), store.createDraft(base))
            }

            seg.size >= 3 && seg[1] == "versions" -> versionRoutes(ex, seg)

            seg == listOf("api", "facts") && method == "POST" -> {
                val b = body(ex)
                val payload = b["payload"]?.jsonObject ?: throw IllegalArgumentException("缺少 payload")
                AppJson.encodeToJsonElement(SavedFact.serializer(),
                    store.saveFact(b["name"]?.jsonPrimitive?.content ?: "未命名", payload.toMap()))
            }

            seg.size == 3 && seg[1] == "facts" && method == "DELETE" -> {
                store.deleteFact(seg[2]); err("ok")
            }

            seg == listOf("api", "run") && method == "POST" -> {
                val b = body(ex)
                val versionRef = b["version"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("缺少 version")
                val asOf = b["asOf"]?.jsonPrimitive?.content?.let { LocalDate.parse(it) } ?: LocalDate.now()
                val factId = b["factId"]?.jsonPrimitive?.content
                val (name, payload) = if (factId != null) {
                    val f = store.snapshot().facts.firstOrNull { it.id == factId }
                        ?: throw NotFoundException("事实不存在：$factId")
                    f.name to f.payload
                } else {
                    val p = b["fact"]?.jsonObject ?: throw IllegalArgumentException("缺少 fact 或 factId")
                    null to p.toMap()
                }
                AppJson.encodeToJsonElement(RunRecord.serializer(), store.run(versionRef, name, payload, asOf))
            }

            seg.size == 3 && seg[1] == "runs" && method == "GET" ->
                AppJson.encodeToJsonElement(RunRecord.serializer(), store.replay(seg[2]))

            seg == listOf("api", "impact") && method == "GET" -> {
                val from = query(ex, "from")?.toInt() ?: throw IllegalArgumentException("缺少 from")
                val to = query(ex, "to")?.toInt() ?: throw IllegalArgumentException("缺少 to")
                val asOf = query(ex, "asOf")?.let { LocalDate.parse(it) } ?: LocalDate.now()
                AppJson.encodeToJsonElement(ListSerializer(ImpactEntry.serializer()), store.impact(from, to, asOf))
            }

            seg == listOf("api", "export") && method == "GET" ->
                AppJson.encodeToJsonElement(Store.ExportBundle.serializer(), store.export())

            seg == listOf("api", "import") && method == "POST" -> {
                store.import(AppJson.decodeFromJsonElement<Store.ExportBundle>(body(ex)))
                err("ok")
            }

            else -> throw NotFoundException("未知接口：$method $path")
        }
        respond(ex, 200, out)
    }

    private fun versionRoutes(ex: HttpExchange, seg: List<String>): JsonElement {
        val id = seg[2]
        return when {
            seg.size == 3 && ex.requestMethod == "GET" ->
                AppJson.encodeToJsonElement(RuleSetVersion.serializer(), store.getVersion(id))

            seg.size == 4 && seg[3] == "rules" && ex.requestMethod == "PUT" -> {
                val rules = AppJson.decodeFromJsonElement<List<Rule>>(
                    body(ex)["rules"] ?: throw IllegalArgumentException("缺少 rules"))
                AppJson.encodeToJsonElement(RuleSetVersion.serializer(), store.updateRules(id, rules))
            }

            seg.size == 4 && seg[3] == "exceptions" && ex.requestMethod == "POST" -> {
                val b = body(ex)
                AppJson.encodeToJsonElement(RuleSetVersion.serializer(), store.addException(
                    id,
                    b["ruleA"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("缺少 ruleA"),
                    b["ruleB"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("缺少 ruleB"),
                    b["reason"]?.jsonPrimitive?.content ?: "",
                    b["expiresOn"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("缺少 expiresOn"),
                ))
            }

            seg.size == 5 && seg[3] == "exceptions" && ex.requestMethod == "DELETE" ->
                AppJson.encodeToJsonElement(RuleSetVersion.serializer(), store.removeException(id, seg[4]))

            seg.size == 4 && seg[3] == "publish" && ex.requestMethod == "POST" -> {
                val expected = body(ex)["expectedSeq"]?.jsonPrimitive?.int
                    ?: throw IllegalArgumentException("缺少 expectedSeq")
                AppJson.encodeToJsonElement(RuleSetVersion.serializer(), store.publish(id, expected))
            }

            seg.size == 4 && seg[3] == "conflicts" && ex.requestMethod == "GET" -> {
                val asOf = query(ex, "asOf")?.let { LocalDate.parse(it) } ?: LocalDate.now()
                AppJson.encodeToJsonElement(ListSerializer(ConflictPair.serializer()),
                    store.conflictsOf(id, asOf))
            }

            else -> throw NotFoundException("未知接口：${ex.requestMethod} /${seg.joinToString("/")}")
        }
    }

    private fun respond(ex: HttpExchange, code: Int, body: JsonElement) {
        val bytes = AppJson.encodeToString(JsonElement.serializer(), body).toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }
}
