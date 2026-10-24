package dev.klerkframework.rest

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The absolute URL of the API root as the client sees it. A `Forwarded` header (RFC 7239) from a gateway wins over the
 * request itself; its `prefix` replaces [basePath].
 */
internal fun ApplicationCall.apiUrl(basePath: String): String {
    val forwarded =
        request.headers[HttpHeaders.Forwarded]
            ?.substringBefore(',')
            ?.split(';')
            ?.mapNotNull { part ->
                part.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k.trim().lowercase() to v.trim().trim('"') }
            }?.toMap()
            .orEmpty()
    val scheme = forwarded["proto"] ?: request.origin.scheme
    val host = forwarded["host"] ?: request.origin.serverHost
    val port = forwarded["port"]?.toIntOrNull() ?: if ("host" in forwarded) null else request.origin.serverPort
    val defaultPort = if (scheme == "https") 443 else 80
    val authority = if (port == null || port == defaultPort) host else "$host:$port"
    return "$scheme://$authority${forwarded["prefix"] ?: basePath}"
}

/** A link description object. [rel] is the link relation, [title] a human-readable name and [params] the schema URI. */
internal fun link(
    rel: String,
    href: String,
    method: String,
    title: String? = null,
    params: String? = null,
): JsonObject =
    buildJsonObject {
        put("rel", rel)
        put("href", href)
        put("method", method)
        title?.let { put("title", it) }
        params?.let { put("params", it) }
    }

internal fun queryString(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (k, v) ->
        "${java.net.URLEncoder.encode(k, Charsets.UTF_8)}=${java.net.URLEncoder.encode(v, Charsets.UTF_8)}"
    }
