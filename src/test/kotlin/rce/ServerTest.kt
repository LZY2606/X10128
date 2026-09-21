package rce

import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {

    private var server: Server? = null

    @AfterTest fun tearDown() { server?.stop() }

    @Test
    fun `首页展示规则冲突解释器且 API 可用`() {
        val dir = Files.createTempDirectory("rce-server-")
        val store = Store(dir) { LocalDate.of(2026, 6, 1) }
        server = Server(store, "127.0.0.1", 0)
        server!!.start()
        val port = server!!.actualPort
        val client = HttpClient.newHttpClient()

        val home = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/")).build(),
            HttpResponse.BodyHandlers.ofString())
        assertEquals(200, home.statusCode())
        assertTrue(home.body().contains("规则冲突解释器"))

        // 建草稿 → 写规则 → 发布 → 运行
        val draft = client.send(post(port, "/api/versions", "{}"), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, draft.statusCode())
        val draftId = AppJson.parseToJsonElement(draft.body()).jsonObject["id"]!!.toString().trim('"')

        val rules = """{"rules":[{"id":"r1","description":"","condition":{"type":"exists","field":"x"},"effectiveFrom":"2026-01-01","effectiveTo":null,"priority":1,"conclusion":"A","source":"t"}]}"""
        assertEquals(200, client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/versions/$draftId/rules"))
                .PUT(HttpRequest.BodyPublishers.ofString(rules)).header("Content-Type", "application/json").build(),
            HttpResponse.BodyHandlers.ofString()).statusCode())

        assertEquals(200, client.send(post(port, "/api/versions/$draftId/publish", """{"expectedSeq":0}"""),
            HttpResponse.BodyHandlers.ofString()).statusCode())

        val run = client.send(post(port, "/api/run", """{"version":"1","fact":{"x":1},"asOf":"2026-06-01"}"""),
            HttpResponse.BodyHandlers.ofString())
        assertEquals(200, run.statusCode())
        assertTrue(run.body().contains("\"A\""))
    }

    private fun post(port: Int, path: String, body: String): HttpRequest =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json").build()

}
