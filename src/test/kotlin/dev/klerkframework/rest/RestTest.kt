package dev.klerkframework.rest

import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.Unauthenticated
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RestTest {
    private fun ApplicationTestBuilder.serve(requireIdempotencyKey: Boolean = false) {
        val klerk = runBlocking { startKlerk() }
        application {
            klerkRest(klerk, { call ->
                when (call.request.headers[HttpHeaders.Authorization]) {
                    "Bearer system" -> Ctx(SystemIdentity)
                    "Bearer anonymous" -> Ctx(Unauthenticated)
                    else -> null
                }
            }, "test", "1", requireIdempotencyKey = requireIdempotencyKey)
        }
    }

    private suspend fun HttpClient.send(
        method: String,
        path: String,
        token: String? = "system",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            headers.forEach { (k, v) -> header(k, v) }
            if (body != null) {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(body)
            }
        }
        return if (method == "GET") get("/api/v1$path", configure) else post("/api/v1$path", configure)
    }

    private fun HttpResponse.json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun HttpClient.createNote(title: String = "hello"): JsonObject {
        val response = send("POST", "/notes", body = """{"title":"$title"}""")
        val text = response.bodyAsText()
        assertEquals(HttpStatusCode.Created, response.status, text)
        assertNotNull(response.headers[HttpHeaders.Location])
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun createGetAndListWithActions() =
        testApplication {
            serve()
            val note = client.createNote()
            val href = note["href"]!!.jsonPrimitive.content
            assertEquals("Created", note["state"]!!.jsonPrimitive.content)
            assertEquals("hello", note["props"]!!.jsonObject["title"]!!.jsonPrimitive.content)
            val actions = note["actions"]!!.jsonArray.map { it.jsonObject["event"]!!.jsonPrimitive.content }
            assertTrue("RenameNote" in actions, actions.toString())

            val get = client.get(href) { header(HttpHeaders.Authorization, "Bearer system") }
            assertEquals(HttpStatusCode.OK, get.status)
            assertEquals(note["id"], get.json(get.bodyAsText())["id"])

            val list = client.send("GET", "/notes?actions=true")
            val items = list.json(list.bodyAsText())["items"] as JsonArray
            assertEquals(1, items.size)
        }

    @Test
    fun instanceEventUpdatesModel() =
        testApplication {
            serve()
            val note = client.createNote()
            val rename = note["actions"]!!.jsonArray.map { it.jsonObject }.first { it["event"]!!.jsonPrimitive.content == "RenameNote" }
            val response = client.send("POST", rename["href"]!!.jsonPrimitive.content.removePrefix("/api/v1"), body = """{"title":"new"}""")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals(
                "new",
                response
                    .json(response.bodyAsText())["props"]!!
                    .jsonObject["title"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun invalidValueGivesProblemDetails() =
        testApplication {
            serve()
            val response = client.send("POST", "/notes", body = """{"title":""}""")
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals("application/problem+json", response.headers[HttpHeaders.ContentType])
            val problem = response.json(response.bodyAsText())
            assertEquals(422, problem["status"]!!.jsonPrimitive.content.toInt())
        }

    @Test
    fun eTagSupportsConditionalGetAndIfMatch() =
        testApplication {
            serve()
            val note = client.createNote()
            val path = note["href"]!!.jsonPrimitive.content.removePrefix("/api/v1")
            val etag = client.send("GET", path).headers[HttpHeaders.ETag]!!
            assertEquals(HttpStatusCode.NotModified, client.send("GET", path, headers = mapOf("If-None-Match" to etag)).status)

            val rename = "$path/events/rename-note"
            assertEquals(
                HttpStatusCode.PreconditionFailed,
                client.send("POST", rename, body = """{"title":"x"}""", headers = mapOf("If-Match" to "\"stale\"")).status,
            )
            val ok = client.send("POST", rename, body = """{"title":"x"}""", headers = mapOf("If-Match" to etag))
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
            assertEquals(
                HttpStatusCode.PreconditionFailed,
                client.send("POST", rename, body = """{"title":"y"}""", headers = mapOf("If-Match" to etag)).status,
            )
        }

    @Test
    fun paginationUsesLinkHeader() =
        testApplication {
            serve()
            repeat(3) { client.createNote("n$it") }
            val first = client.send("GET", "/notes?limit=2")
            val link = first.headers[HttpHeaders.Link]!!
            assertTrue("rel=\"next\"" in link, link)
            val next = link.substringAfter("<").substringBefore(">")
            val second = client.send("GET", next.removePrefix("/api/v1"))
            assertEquals(1, (second.json(second.bodyAsText())["items"] as JsonArray).size)
        }

    @Test
    fun authentication() =
        testApplication {
            serve()
            val response = client.send("GET", "/notes", token = null)
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue("Bearer" in response.headers[HttpHeaders.WWWAuthenticate]!!)
            val note = client.createNote()
            val path = note["href"]!!.jsonPrimitive.content.removePrefix("/api/v1")
            assertEquals(
                HttpStatusCode.Forbidden,
                client.send("POST", "$path/events/annotate-note", token = "anonymous", body = """{"position":{"line":"a"}}""").status,
            )
        }

    @Test
    fun unknownModelIsNotFound() =
        testApplication {
            serve()
            assertEquals(HttpStatusCode.NotFound, client.send("GET", "/notes/999").status)
            assertEquals(HttpStatusCode.NotFound, client.send("GET", "/notes/abc").status)
        }

    @Test
    fun openApiIsServedWithoutAuthentication() =
        testApplication {
            serve()
            val response = client.send("GET", "/openapi.json", token = null)
            assertEquals(HttpStatusCode.OK, response.status)
            val doc = response.json(response.bodyAsText())
            assertEquals("3.1.0", doc["openapi"]!!.jsonPrimitive.content)
            val paths = doc["paths"]!!.jsonObject.keys
            assertTrue("/notes/{id}/events/rename-note" in paths, paths.toString())
            assertTrue("/notes" in paths)
            assertTrue(
                "NoteRenameNoteParams" !in
                    doc["components"]!!
                        .jsonObject["schemas"]!!
                        .jsonObject.keys
                        .toString()
                        .replace("RenameNote", "X"),
            )
        }

    @Test
    fun idempotencyKeyAppliesTheEventOnce() =
        testApplication {
            serve()
            val key = mapOf("Idempotency-Key" to "abc")
            val first = client.send("POST", "/notes", body = """{"title":"a"}""", headers = key)
            assertEquals(HttpStatusCode.Created, first.status)
            val retry = client.send("POST", "/notes", body = """{"title":"a"}""", headers = key)
            assertEquals(HttpStatusCode.Conflict, retry.status)
            assertEquals("urn:klerk:error:ERROR-COMMAND-5", retry.json(retry.bodyAsText())["type"]!!.jsonPrimitive.content)
            assertEquals(1, (client.send("GET", "/notes").let { it.json(it.bodyAsText()) }["items"] as JsonArray).size)

            val otherActor = client.send("POST", "/notes", token = "anonymous", body = """{"title":"b"}""", headers = key)
            assertEquals(HttpStatusCode.Created, otherActor.status)
        }

    @Test
    fun aFailedRequestDoesNotUseUpTheIdempotencyKey() =
        testApplication {
            serve()
            val key = mapOf("Idempotency-Key" to "def")
            assertEquals(HttpStatusCode.UnprocessableEntity, client.send("POST", "/notes", body = """{"title":""}""", headers = key).status)
            assertEquals(HttpStatusCode.Created, client.send("POST", "/notes", body = """{"title":"ok"}""", headers = key).status)
        }

    @Test
    fun theIdempotencyKeyCanBeRequired() =
        testApplication {
            serve(requireIdempotencyKey = true)
            val missing = client.send("POST", "/notes", body = """{"title":"a"}""")
            assertEquals(HttpStatusCode.BadRequest, missing.status)
            assertTrue("Idempotency-Key" in missing.bodyAsText())
            val ok = client.send("POST", "/notes", body = """{"title":"a"}""", headers = mapOf("Idempotency-Key" to "k"))
            assertEquals(HttpStatusCode.Created, ok.status)
            assertEquals(HttpStatusCode.OK, client.send("GET", "/notes").status)
        }

    @Test
    fun voidEventsHaveTheirOwnEndpoint() =
        testApplication {
            serve()
            val response = client.send("POST", "/notes/events/create-note", body = """{"title":"a"}""")
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        }
}
