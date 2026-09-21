package rci.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import rci.domain.*
import rci.json.Json
import rci.store.*
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.LocalDate

class WebServer(
    private val app: AppService,
    host: String,
    port: Int,
) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(host, port), 0)
    private val clock: () -> LocalDate = { LocalDate.now() }

    init {
        server.createContext("/") { exchange ->
            try {
                route(exchange)
            } catch (e: Throwable) {
                error(exchange, e)
            }
        }
        server.executor = null
    }

    fun start() { server.start() }
    fun stop() { server.stop(0) }
    fun address(): InetSocketAddress = server.address

    private fun route(ex: HttpExchange) {
        val uri: URI = ex.requestURI
        val path = uri.path.trimEnd('/').ifEmpty { "/" }
        if (ex.requestMethod == "GET" && (path == "/" || path == "/index.html")) {
            static(ex, "web/index.html", "text/html; charset=utf-8")
            return
        }
        if (ex.requestMethod == "GET" && path == "/app.js") {
            static(ex, "web/app.js", "application/javascript; charset=utf-8")
            return
        }
        if (path == "/api/health" && ex.requestMethod == "GET") {
            json(ex, 200, mapOf("ok" to true)); return
        }
        val body = if (ex.requestMethod in setOf("POST", "PUT", "PATCH"))
            ex.requestBody.readBytes().toString(StandardCharsets.UTF_8).takeIf { it.isNotBlank() }?.let {
                @Suppress("UNCHECKED_CAST")
                Json.parse(it) as Map<String, Any?>
            } ?: emptyMap()
        else emptyMap()
        val q = queryParams(uri)

        when {
            path == "/api/versions" && ex.requestMethod == "GET" ->
                json(ex, 200, mapOf("versions" to app.listVersions().map { versionJson(it) }))
            path == "/api/versions" && ex.requestMethod == "POST" -> {
                val parent = body["parentId"] as? String
                val v = if (parent == null) app.createInitialVersion(str(body, "name"))
                else app.createDraft(parent, str(body, "name"))
                json(ex, 201, versionJson(v))
            }
            path.startsWith("/api/versions/") -> versionRoutes(ex, path, body)
            path == "/api/facts" && ex.requestMethod == "GET" ->
                json(ex, 200, mapOf("facts" to app.listFacts().map { Codec.writeSavedFact(it) }))
            path == "/api/facts" && ex.requestMethod == "POST" -> {
                @Suppress("UNCHECKED_CAST")
                val fact = Codec.readFact(body["fact"] as Map<String, Any?>)
                val sf = app.saveFact(str(body, "name"), fact, body["id"] as? String)
                json(ex, 201, Codec.writeSavedFact(sf))
            }
            path == "/api/exceptions" && ex.requestMethod == "GET" ->
                json(ex, 200, mapOf("exceptions" to app.listExceptions().map { Codec.writeException(it) }))
            path == "/api/exceptions" && ex.requestMethod == "POST" -> {
                val exp = (body["expiresOn"] as? String)?.let { Dates.parse(it) }
                val e = app.addException(str(body, "ruleA"), str(body, "ruleB"),
                    str(body, "reason"), exp)
                json(ex, 201, Codec.writeException(e))
            }
            path == "/api/runs" && ex.requestMethod == "GET" ->
                json(ex, 200, mapOf("runs" to app.listRuns().map { Codec.writeRun(it) }))
            path == "/api/run" && ex.requestMethod == "POST" -> {
                @Suppress("UNCHECKED_CAST")
                val fact = Codec.readFact(body["fact"] as Map<String, Any?>)
                val at = (body["effectiveAt"] as? String)?.let { Dates.parse(it) } ?: clock()
                val rr = app.run(str(body, "versionId"), fact, body["factId"] as? String, at,
                    persistRun = body["persist"] as? Boolean ?: true)
                json(ex, 200, Codec.writeRun(rr))
            }
            path == "/api/impact" && ex.requestMethod == "POST" -> {
                val at = (body["effectiveAt"] as? String)?.let { Dates.parse(it) } ?: clock()
                val ip = app.previewImpact(str(body, "fromVersionId"), str(body, "toVersionId"), at)
                json(ex, 200, impactJson(ip))
            }
            path == "/api/export" && ex.requestMethod == "GET" ->
                json(ex, 200, Json.parse(app.export()) as Any)
            path == "/api/import" && ex.requestMethod == "POST" -> {
                app.importBundle(Json.stringify(body, indent = false))
                json(ex, 200, mapOf("imported" to true))
            }
            path == "/api/replay" && ex.requestMethod == "GET" -> {
                val rr = app.replay(str(q, "runId"))
                json(ex, 200, Codec.writeRun(rr))
            }
            path == "/api/rerun" && ex.requestMethod == "POST" -> {
                val at = (body["effectiveAt"] as? String)?.let { Dates.parse(it) } ?: clock()
                val rr = app.rerun(str(body, "runId"), str(body, "versionId"), at)
                json(ex, 200, Codec.writeRun(rr))
            }
            else -> json(ex, 404, mapOf("error" to "未知接口：$path"))
        }
    }

    private fun versionRoutes(ex: HttpExchange, path: String, body: Map<String, Any?>) {
        val parts = path.split("/").filter { it.isNotEmpty() } // api, versions, :id, ...
        val vid = parts.getOrNull(2) ?: throw ValidationException("缺少版本 ID")
        when {
            parts.size == 4 && parts[3] == "save" && ex.requestMethod == "POST" -> {
                @Suppress("UNCHECKED_CAST")
                val rules = (body["rules"] as List<Any?>).map { Codec.readRule(it as Map<String, Any?>) }
                val rev = (body["revision"] as Number).toLong()
                val incoming = RuleSetVersion(
                    id = vid, parentId = null, name = str(body, "name"),
                    status = VersionStatus.DRAFT, rules = rules, exceptions = emptyList(),
                    createdAt = "", publishedAt = null, revision = rev, fingerprint = null,
                )
                json(ex, 200, versionJson(app.saveDraft(incoming, rev)))
            }
            parts.size == 4 && parts[3] == "publish" && ex.requestMethod == "POST" -> {
                val rev = (body["revision"] as Number).toLong()
                json(ex, 200, versionJson(app.publish(vid, rev)))
            }
            parts.size == 4 && parts[3] == "conflicts" && ex.requestMethod == "GET" -> {
                val report = app.analyze(vid)
                json(ex, 200, mapOf(
                    "versionId" to report.versionId,
                    "pairs" to report.pairs.map { Codec.writeConflictPair(it) },
                ))
            }
            parts.size == 3 && ex.requestMethod == "GET" ->
                json(ex, 200, versionJson(app.getVersion(vid)))
            else -> json(ex, 404, mapOf("error" to "未知版本操作：$path"))
        }
    }

    private fun str(m: Map<String, Any?>, k: String): String = m[k] as? String
        ?: throw ValidationException("缺少字符串参数：$k")

    private fun versionJson(v: RuleSetVersion): Map<String, Any?> {
        val base = Codec.writeVersion(v).toMutableMap()
        base["rules"] = v.rules.map { Codec.writeRule(it) }
        return base
    }

    private fun impactJson(ip: ImpactPreview): Map<String, Any?> = mapOf(
        "fromVersionId" to ip.fromVersionId,
        "toVersionId" to ip.toVersionId,
        "impacts" to ip.impacts.map {
            linkedMapOf(
                "factId" to it.factId, "factName" to it.factName,
                "beforeConclusion" to it.beforeConclusion, "afterConclusion" to it.afterConclusion,
                "changed" to it.changed,
                "beforeWinner" to it.beforeWinner, "afterWinner" to it.afterWinner,
                "causedByRuleIds" to it.causedByRuleIds,
            )
        },
    )

    private fun queryParams(uri: URI): Map<String, String> =
        uri.query?.split("&")?.mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) null else pair.substring(0, idx) to
                java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
        }?.toMap().orEmpty()

    private fun static(ex: HttpExchange, resource: String, contentType: String) {
        val url = javaClass.classLoader.getResource(resource)
            ?: throw NotFoundException("资源不存在：$resource")
        val bytes = url.openStream().use { it.readBytes() }
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun json(ex: HttpExchange, status: Int, payload: Any) {
        val bytes = Json.stringify(payload, indent = false).toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun error(ex: HttpExchange, e: Throwable) {
        val (status, code) = when (e) {
            is NotFoundException -> 404 to "not_found"
            is ValidationException -> 400 to "validation_error"
            is OptimisticLockException -> 409 to "optimistic_lock"
            is ConflictBlockedException -> 422 to "conflict_blocked"
            else -> 500 to "internal_error"
        }
        val payload = mutableMapOf<String, Any?>("error" to code, "message" to (e.message ?: "内部错误"))
        if (e is ConflictBlockedException) {
            payload["conflicts"] = e.conflicts.map { Codec.writeConflictPair(it) }
        }
        json(ex, status, payload)
    }
}
