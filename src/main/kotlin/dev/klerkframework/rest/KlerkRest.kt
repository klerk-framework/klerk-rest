package dev.klerkframework.rest

import dev.klerkframework.klerk.CommandResult
import dev.klerkframework.klerk.DefaultTranslation
import dev.klerkframework.klerk.Event
import dev.klerkframework.klerk.InstanceEvent
import dev.klerkframework.klerk.InstanceEventWithParameters
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkErrorCode
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.view.ModelView
import dev.klerkframework.klerk.view.QueryListCursor
import dev.klerkframework.klerk.view.QueryOptions
import dev.klerkframework.klerk.view.query
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fromHttpToGmtDate
import io.ktor.http.toHttpDate
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.date.GMTDate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * Decides who the client of a request is, typically from its `Authorization` header. Returns `null` to reject the
 * request with `401 Unauthorized`.
 */
public typealias ContextProvider<C> = suspend (ApplicationCall) -> C?

private val logger = LoggerFactory.getLogger("dev.klerkframework.rest.KlerkRest")

private val SEMVER = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""")

/**
 * Serves [klerk] as a REST API under [basePath]. See the klerk-rest README for the endpoints.
 *
 * [contextProvider] receives every request, so it decides who the client is, e.g. from an `Authorization` header.
 * Return `null` to reject the request with `401 Unauthorized`, e.g. when the header is missing or its token is invalid.
 * Klerk's authorization rules apply to everything the client does.
 *
 * The API is described by the OpenAPI document at `$basePath/openapi.json`, which does not require authentication.
 * [title] and [version] are shown in it. [version] must be a semantic version (`MAJOR.MINOR.PATCH`), and the major
 * version of an [ApiStatus.Alpha] or [ApiStatus.Beta] API must be 0.
 *
 * `GET $basePath/api-info` tells the client the API's name ([title]), [version], [released] date, [status] and the URL
 * of the OpenAPI document. It does not require authentication. [released] is today by default.
 *
 * URLs in responses are absolute. Behind a gateway, send a `Forwarded` header (RFC 7239) with `proto`, `host`, and
 * optionally `port` and `prefix` (the gateway's base URL of the API, replacing [basePath]).
 *
 * If [requireIdempotencyKey] is true, a request that executes an event without an `Idempotency-Key` header is rejected
 * with `400 Bad Request`. Only the first successful request with a key is applied, so the client can retry safely.
 *
 * Can be called several times with different [basePath]s, e.g. to serve several Klerk instances.
 */
public fun <C : KlerkContext, V> Application.klerkRest(
    klerk: Klerk<C, V>,
    contextProvider: ContextProvider<C>,
    title: String,
    version: String,
    basePath: String = "/api/v1",
    requireIdempotencyKey: Boolean = true,
    released: LocalDate = LocalDate.now(),
    status: ApiStatus = ApiStatus.Active,
) {
    require(SEMVER.matches(version)) { "version must be a semantic version like 1.0.0" }
    require(status !in setOf(ApiStatus.Alpha, ApiStatus.Beta) || version.startsWith("0.")) {
        "The major version of an alpha or beta API must be 0"
    }
    require(basePath.startsWith("/") && !basePath.endsWith("/")) { "basePath must start with / and not end with /" }
    val resources = resources(klerk)
    val openApi by lazy {
        openApiDocument(
            klerk,
            resources,
            DefaultTranslation,
            title,
            version,
            basePath,
            requireIdempotencyKey,
        ).toString()
    }
    val rest = Rest(klerk, contextProvider, basePath, requireIdempotencyKey)

    routing {
        route(basePath) {
            get("/openapi.json") {
                call.respondText(openApi, ContentType.parse("application/vnd.oai.openapi+json"))
            }
            get("/api-info") {
                val info =
                    buildJsonObject {
                        put("apiName", title)
                        put("apiVersion", version)
                        put("apiReleased", released.toString())
                        put("apiDocumentation", "${call.apiUrl(basePath)}/openapi.json")
                        put("apiStatus", status.value)
                    }
                call.respondText(info.toString(), ContentType.Application.Json)
            }
            for (resource in resources) {
                route("/${resource.collection}") {
                    get { rest.authenticated(call) { rest.list(call, resource, it) } }
                    resource.creatingEvent?.let { event ->
                        post { rest.authenticated(call) { rest.createModel(call, resource, event, it) } }
                    }
                    post("/events/{event}") {
                        rest.authenticated(call) { rest.createModel(call, resource, null, it) }
                    }
                    get("/{id}") { rest.authenticated(call) { rest.get(call, resource, it) } }
                    post("/{id}/events/{event}") { rest.authenticated(call) { rest.execute(call, resource, it) } }
                }
            }
        }
    }
}

private class Rest<C : KlerkContext, V>(
    val klerk: Klerk<C, V>,
    val contextProvider: ContextProvider<C>,
    val basePath: String,
    val requireIdempotencyKey: Boolean,
) {
    suspend fun authenticated(
        call: RoutingCall,
        block: suspend (C) -> Unit,
    ) {
        val context = contextProvider(call)
        if (context == null) {
            call.response.header(HttpHeaders.WWWAuthenticate, """Bearer realm="api", error="invalid_token"""")
            call.respondProblem(HttpStatusCode.Unauthorized, "Missing or invalid credentials")
            return
        }
        try {
            block(context)
        } catch (e: RequestException) {
            call.respondProblem(HttpStatusCode.BadRequest, e.message ?: "Bad request")
        } catch (_: AlreadyResponded) {
        }
    }

    private suspend fun ApplicationCall.respondModel(
        status: HttpStatusCode,
        model: Model<*>,
        json: JsonObject,
    ) {
        response.header(HttpHeaders.ETag, etag(model))
        response.header(HttpHeaders.LastModified, GMTDate(model.lastModifiedAt.toEpochMilliseconds()).toHttpDate())
        response.header(HttpHeaders.CacheControl, "private, no-cache")
        response.header(HttpHeaders.Vary, HttpHeaders.Authorization)
        respondText(json.toString(), ContentType.Application.Json, status)
    }

    private fun modelID(call: RoutingCall): ModelID<Any>? = call.parameters["id"]?.toIntOrNull()?.let { ModelID(it) }

    suspend fun list(
        call: RoutingCall,
        resource: Resource<C>,
        context: C,
    ) {
        val query = call.request.queryParameters
        val viewName = query["view"] ?: "all"
        val view =
            resource.views[viewName]
                ?: throw RequestException("Unknown view '$viewName'. Available: ${resource.views.keys.sorted()}")
        val limit =
            query["limit"]?.let { it.toIntOrNull() ?: throw RequestException("limit is not a number: $it") }
                ?: DEFAULT_LIMIT
        if (limit !in 1..MAX_LIMIT) throw RequestException("limit must be between 1 and $MAX_LIMIT")
        val cursor =
            query["cursor"]?.let {
                try {
                    QueryListCursor.parse(it)
                } catch (_: IllegalArgumentException) {
                    throw RequestException("Invalid cursor")
                }
            }
        val referenced =
            query["referencing"]?.let { value ->
                value.toIntOrNull()?.let { ModelID<Any>(it) } ?: throw RequestException("Not a model ID: $value")
            }
        val withActions = query["actions"]?.toBooleanStrictOrNull() ?: false
        val base = call.apiUrl(basePath)

        var next: QueryListCursor? = null
        val items =
            klerk.read(context) {
                val filter = referenced?.let { referencingIds(it) }?.let { ids -> { model: Model<Any> -> model.id in ids } }

                @Suppress("UNCHECKED_CAST")
                val page = (view as ModelView<Any, C>).query(QueryOptions(maxItems = limit, cursor = cursor), filter = filter)
                next = page.cursorNextPage
                page.items.map { modelToJson(it, resource, base, if (withActions) possibleActions(it) else null) }
            }
        val collectionUrl = "$base/${resource.collection}"
        val given = query.entries().flatMap { (k, vs) -> vs.map { k to it } }
        val withoutCursor = given.filter { it.first != "cursor" }

        fun url(params: List<Pair<String, String>>) = if (params.isEmpty()) collectionUrl else "$collectionUrl?${queryString(params)}"
        val nextUrl = next?.let { url(withoutCursor + ("cursor" to it.toString())) }
        nextUrl?.let { call.response.header(HttpHeaders.Link, "<$it>; rel=\"next\"") }
        val body =
            buildJsonObject {
                putJsonObject("_meta") {
                    put("count", items.size)
                    put("limit", limit)
                }
                putJsonArray("_links") {
                    add(link("self", url(given), "GET"))
                    if (cursor != null) add(link("first", url(withoutCursor), "GET"))
                    nextUrl?.let { add(link("next", it, "GET")) }
                }
                putJsonArray("items") { items.forEach { add(it) } }
            }
        call.response.header(HttpHeaders.Vary, HttpHeaders.Authorization)
        call.respondText(body.toString(), ContentType.Application.Json)
    }

    suspend fun get(
        call: RoutingCall,
        resource: Resource<C>,
        context: C,
    ) {
        val id = modelID(call) ?: return call.respondProblem(HttpStatusCode.NotFound, "No such ${resource.modelName}")
        val found =
            klerk.read(context) {
                getOrNull(id)?.takeIf { it.props::class == resource.kClass }?.let {
                    it to modelToJson(it, resource, call.apiUrl(basePath), possibleActions(it))
                }
            } ?: return call.respondProblem(HttpStatusCode.NotFound, "No ${resource.modelName} with ID $id")
        val (model, json) = found
        val tag = etag(model)
        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
        if (ifNoneMatch != null && matches(ifNoneMatch, tag)) {
            call.response.header(HttpHeaders.ETag, tag)
            return call.respond(HttpStatusCode.NotModified)
        }
        val ifModifiedSince = call.request.headers[HttpHeaders.IfModifiedSince]?.let { runCatching { it.fromHttpToGmtDate() }.getOrNull() }
        if (ifNoneMatch == null &&
            ifModifiedSince != null &&
            model.lastModifiedAt.toEpochMilliseconds() / 1000 <= ifModifiedSince.timestamp / 1000
        ) {
            call.response.header(HttpHeaders.ETag, tag)
            return call.respond(HttpStatusCode.NotModified)
        }
        call.respondModel(HttpStatusCode.OK, model, json)
    }

    /** [creating] is the event of `POST /collection`; null for `POST /collection/events/{event}`. */
    suspend fun createModel(
        call: RoutingCall,
        resource: Resource<C>,
        creating: Event<Any, Any?>?,
        context: C,
    ) {
        val event =
            creating ?: resource.voidEvents[call.parameters["event"]]
                ?: return call.respondProblem(HttpStatusCode.NotFound, "No such action")
        execute(call, resource, event, null, context)
    }

    suspend fun execute(
        call: RoutingCall,
        resource: Resource<C>,
        context: C,
    ) {
        val event =
            resource.instanceEvents[call.parameters["event"]]
                ?: return call.respondProblem(HttpStatusCode.NotFound, "No such event")
        val id = modelID(call) ?: return call.respondProblem(HttpStatusCode.NotFound, "No such ${resource.modelName}")
        execute(call, resource, event, id, context)
    }

    private suspend fun execute(
        call: RoutingCall,
        resource: Resource<C>,
        event: Event<Any, Any?>,
        id: ModelID<Any>?,
        context: C,
    ) {
        val body = readBody(call)
        val params = createCommandParams(event, body)

        if (requireIdempotencyKey && call.request.headers[IDEMPOTENCY_KEY] == null) {
            throw RequestException("The header $IDEMPOTENCY_KEY is required")
        }
        val ifMatch = call.request.headers[HttpHeaders.IfMatch]
        val idempotencyKey =
            call.request.headers[IDEMPOTENCY_KEY]?.also {
                if (it.isBlank() || it.length > MAX_IDEMPOTENCY_KEY_LENGTH) {
                    throw RequestException("$IDEMPOTENCY_KEY must be 1-$MAX_IDEMPOTENCY_KEY_LENGTH characters")
                }
            }
        val models = if (id != null && ifMatch != null) setOf(id) else emptySet()
        var token =
            idempotencyKey?.let { CommandToken.forKey(tokenKey(context, call, it), models) }
                ?: CommandToken.simple()
        if (id != null && ifMatch != null) {
            val current =
                klerk.read(context) { getOrNull(id)?.takeIf { it.props::class == resource.kClass } }
                    ?: return call.respondProblem(HttpStatusCode.NotFound, "No ${resource.modelName} with ID $id")
            if (!matches(ifMatch, etag(current), strong = true)) {
                return call.respondProblem(HttpStatusCode.PreconditionFailed, "The model has changed")
            }
            if (idempotencyKey == null) token = CommandToken.requireUnmodifiedModel(id)
        }

        val command = Command.dynamic(event, id, params)
        when (val result = klerk.handle(command, context, ProcessingOptions(token = token))) {
            is CommandResult.Failure -> {
                logger.debug("Command failed: {}", result.problems)
                if (result.problems.any { it.code == KlerkErrorCode.ModelModifiedSinceTokenCreation }) {
                    return call.respondProblem(HttpStatusCode.PreconditionFailed, "The model has changed")
                }
                call.respondProblems(result.problems)
            }
            is CommandResult.Success -> {
                val modelId = result.primaryModel
                val created = event !is InstanceEvent<*, *>
                val shown =
                    if (modelId == null || modelId in result.deletedModels) {
                        null
                    } else {
                        // The command is committed even if the actor may not read the result.
                        klerk.read(context) {
                            getOrNull(modelId)?.let { it to modelToJson(it, resource, call.apiUrl(basePath), possibleActions(it)) }
                        }
                    }
                if (shown == null) return call.respond(HttpStatusCode.NoContent)
                val (model, json) = shown
                if (created) call.response.header(HttpHeaders.Location, model.id.href(call.apiUrl(basePath), resource))
                call.respondModel(if (created) HttpStatusCode.Created else HttpStatusCode.OK, model, json)
            }
        }
    }

    private suspend fun readBody(call: RoutingCall): JsonObject? {
        val text = call.receiveText()
        if (text.isBlank()) return null
        val contentType = call.request.contentType()
        if (!contentType.match(ContentType.Application.Json)) {
            call.respondProblem(HttpStatusCode.UnsupportedMediaType, "The body must be application/json")
            throw AlreadyResponded()
        }
        return try {
            Json.parseToJsonElement(text) as? JsonObject ?: throw RequestException("The body must be a JSON object")
        } catch (_: SerializationException) {
            throw RequestException("The body is not valid JSON")
        }
    }
}

private const val IDEMPOTENCY_KEY = "Idempotency-Key"
private const val MAX_IDEMPOTENCY_KEY_LENGTH = 255

/** Keys are scoped to the actor and the URI, so clients and endpoints cannot use up each other's keys. */
private fun tokenKey(
    context: KlerkContext,
    call: ApplicationCall,
    key: String,
): String {
    val actor = context.actor
    return "${actor.type}|${actor.id}|${actor.externalId}|${call.request.local.uri.substringBefore('?')}|$key"
}

/** Thrown when the response has already been sent. */
private class AlreadyResponded : RuntimeException()

private fun createCommandParams(
    event: Event<Any, Any?>,
    body: JsonObject?,
): Any? {
    val parametersClass =
        when (event) {
            is VoidEventWithParameters -> event.parametersClass
            is InstanceEventWithParameters -> event.parametersClass
            else -> return null
        }
    return createParameters(ObjectSchema.of(parametersClass as kotlin.reflect.KClass<*>), body)
}

/** True if the entity-tag list [header] (`If-Match`, `If-None-Match`) contains [tag]. Weak tags only match if not [strong]. */
private fun matches(
    header: String,
    tag: String,
    strong: Boolean = false,
): Boolean {
    if (header.trim() == "*") return true
    return header.split(",").map { it.trim() }.any { candidate ->
        if (candidate.startsWith("W/")) !strong && candidate.removePrefix("W/") == tag else candidate == tag
    }
}
