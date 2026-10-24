package dev.klerkframework.rest

import dev.klerkframework.klerk.Event
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Translation
import dev.klerkframework.klerk.misc.ObjectSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal const val DEFAULT_LIMIT = 20
internal const val MAX_LIMIT = 100

private fun ref(name: String) = buildJsonObject { put("\$ref", "#/components/schemas/$name") }

private fun problemResponse(description: String) =
    buildJsonObject {
        put("description", description)
        putJsonObject("content") {
            putJsonObject("application/problem+json") { put("schema", ref("Problem")) }
        }
    }

/** The OpenAPI 3.1 description of the API served for [resources]. Paths are relative to [basePath]. */
internal fun <C : KlerkContext, V> openApiDocument(
    klerk: Klerk<C, V>,
    resources: List<Resource<C>>,
    translation: Translation,
    title: String,
    version: String,
    basePath: String,
    requireIdempotencyKey: Boolean,
): JsonObject =
    buildJsonObject {
        put("openapi", "3.1.0")
        putJsonObject("info") {
            put("title", title)
            put("version", version)
        }
        putJsonArray("servers") { add(buildJsonObject { put("url", basePath) }) }
        putJsonArray("security") { add(buildJsonObject { putJsonArray("bearerAuth") {} }) }
        putJsonObject("paths") {
            putJsonObject("/api-info") {
                putJsonObject("get") {
                    put("operationId", "getApiInfo")
                    put("summary", "Name, version, release date and status of the API")
                    putJsonArray("security") {}
                    putJsonObject("responses") {
                        putJsonObject("200") {
                            put("description", "The API")
                            putJsonObject("content") {
                                putJsonObject("application/json") { put("schema", ref("ApiInfo")) }
                            }
                        }
                    }
                }
            }
            for (resource in resources) {
                val collection = "/${resource.collection}"
                putJsonObject(collection) {
                    putJsonObject("get") { listOperation(resource) }
                    resource.creatingEvent?.let { event ->
                        putJsonObject("post") { eventOperation(resource, event, null, requireIdempotencyKey) }
                    }
                }
                putJsonObject("$collection/{id}") {
                    putJsonObject("get") { getOperation(resource) }
                }
                for ((name, event) in resource.voidEvents) {
                    putJsonObject("$collection/events/$name") {
                        putJsonObject("post") { eventOperation(resource, event, null, requireIdempotencyKey) }
                    }
                }
                for ((name, event) in resource.instanceEvents) {
                    putJsonObject("$collection/{id}/events/$name") {
                        putJsonObject("post") { eventOperation(resource, event, true, requireIdempotencyKey) }
                    }
                }
            }
        }
        putJsonObject("components") {
            putJsonObject("securitySchemes") {
                putJsonObject("bearerAuth") {
                    put("type", "http")
                    put("scheme", "bearer")
                    put("bearerFormat", "JWT")
                }
            }
            putJsonObject("schemas") {
                put("Problem", problemSchema())
                put("Link", linkSchema())
                put("ApiInfo", apiInfoSchema())
                for (resource in resources) {
                    val props = ObjectSchema.of(resource.kClass)
                    put("${resource.modelName}Props", objectSchema(props, translation)?.allRequired() ?: genericObject())
                    put(resource.modelName, modelSchema(resource))
                    put("${resource.modelName}List", listSchema(resource))
                    for (event in resource.voidEvents.values + resource.instanceEvents.values) {
                        val parameters = klerk.specification.parametersSchema(event.id) ?: continue
                        put(resource.schemaName(event), objectSchema(parameters, translation) ?: genericObject())
                    }
                }
            }
        }
    }

private fun genericObject() = buildJsonObject { put("type", "object") }

private fun JsonObject.allRequired(): JsonObject {
    val properties = this["properties"] as? JsonObject ?: return this
    return JsonObject(this + ("required" to JsonArray(properties.keys.map(::JsonPrimitive))))
}

private fun problemSchema() =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("type") { put("type", "string") }
            putJsonObject("title") { put("type", "string") }
            putJsonObject("status") { put("type", "integer") }
            putJsonObject("detail") { put("type", "string") }
            putJsonObject("instance") { put("type", "string") }
            putJsonObject("code") { put("type", "string") }
            putJsonObject("problems") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("type") { put("type", "string") }
                        putJsonObject("code") { put("type", "string") }
                        putJsonObject("detail") { put("type", "string") }
                        putJsonObject("property") { put("type", "string") }
                    }
                }
            }
        }
        putJsonArray("required") {
            add("type")
            add("title")
            add("status")
        }
    }

private fun linkSchema() =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("rel") {
                put("type", "string")
                put("description", "self, first, next, or the kebab-case name of an event")
            }
            putJsonObject("href") {
                put("type", "string")
                put("format", "uri")
            }
            putJsonObject("method") { put("type", "string") }
            putJsonObject("title") {
                put("type", "string")
                put("description", "The name of the event")
            }
            putJsonObject("params") {
                put("type", "string")
                put("description", "URI of the schema of the request body. Left out if the event takes no body")
            }
        }
        putJsonArray("required") {
            add("rel")
            add("href")
            add("method")
        }
    }

private fun apiInfoSchema() =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("apiName") { put("type", "string") }
            putJsonObject("apiVersion") { put("type", "string") }
            putJsonObject("apiReleased") {
                put("type", "string")
                put("format", "date")
            }
            putJsonObject("apiDocumentation") {
                put("type", "string")
                put("format", "uri")
            }
            putJsonObject("apiStatus") {
                put("type", "string")
                putJsonArray("enum") { ApiStatus.entries.forEach { add(it.value) } }
            }
        }
        putJsonArray("required") {
            for (name in listOf("apiName", "apiVersion", "apiReleased", "apiDocumentation", "apiStatus")) add(name)
        }
    }

private fun modelSchema(resource: Resource<*>) =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for (name in listOf("id", "type", "state")) putJsonObject(name) { put("type", "string") }
            for (name in listOf("createdAt", "lastModifiedAt")) {
                putJsonObject(name) {
                    put("type", "string")
                    put("format", "date-time")
                }
            }
            put("props", ref("${resource.modelName}Props"))
            putJsonObject("_links") {
                put("type", "array")
                put("items", ref("Link"))
            }
        }
        putJsonArray("required") {
            for (name in listOf("id", "type", "state", "createdAt", "lastModifiedAt", "props", "_links")) add(name)
        }
    }

private fun listSchema(resource: Resource<*>) =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("_meta") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("count") { put("type", "integer") }
                    putJsonObject("limit") { put("type", "integer") }
                }
            }
            putJsonObject("_links") {
                put("type", "array")
                put("items", ref("Link"))
            }
            putJsonObject("items") {
                put("type", "array")
                put("items", ref(resource.modelName))
            }
        }
        putJsonArray("required") {
            add("_meta")
            add("_links")
            add("items")
        }
    }

private fun parameter(
    name: String,
    location: String,
    schema: JsonObject,
    description: String? = null,
    required: Boolean = location == "path",
) = buildJsonObject {
    put("name", name)
    put("in", location)
    description?.let { put("description", it) }
    put("required", required)
    put("schema", schema)
}

private val idParameter = parameter("id", "path", buildJsonObject { put("type", "string") })

private fun kotlinx.serialization.json.JsonObjectBuilder.errorResponses(vararg statuses: Int) {
    val descriptions =
        mapOf(
            400 to "Malformed request",
            401 to "Not authenticated",
            403 to "Not allowed",
            404 to "Not found",
            409 to "The request conflicts with the current state, or the Idempotency-Key has been used",
            412 to "The model has changed since the ETag in If-Match",
            415 to "The body is not application/json",
            422 to "The values are invalid",
        )
    for (status in statuses) put(status.toString(), problemResponse(descriptions.getValue(status)))
}

private fun kotlinx.serialization.json.JsonObjectBuilder.listOperation(resource: Resource<*>) {
    put("operationId", "list${resource.modelName}")
    put("summary", "Lists ${resource.collection}, one page at a time")
    putJsonArray("parameters") {
        add(
            parameter(
                "view",
                "query",
                buildJsonObject {
                    put("type", "string")
                    putJsonArray("enum") {
                        resource.views.keys
                            .sorted()
                            .forEach { add(it) }
                    }
                    put("default", "all")
                },
            ),
        )
        add(
            parameter(
                "limit",
                "query",
                buildJsonObject {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", MAX_LIMIT)
                    put("default", DEFAULT_LIMIT)
                },
            ),
        )
        add(
            parameter(
                "cursor",
                "query",
                buildJsonObject { put("type", "string") },
                "The cursor of the next link of the previous page",
            ),
        )
        add(
            parameter(
                "referencing",
                "query",
                buildJsonObject { put("type", "string") },
                "Only models that reference the model with this ID",
            ),
        )
        add(
            parameter(
                "actions",
                "query",
                buildJsonObject { put("type", "boolean") },
                "Include a link for each event the client may execute on each model. Slower",
            ),
        )
    }
    putJsonObject("responses") {
        putJsonObject("200") {
            put("description", "A page of ${resource.collection}. _links has rel=next when there is more, and so does the Link header")
            putJsonObject("content") {
                putJsonObject("application/json") { put("schema", ref("${resource.modelName}List")) }
            }
        }
        errorResponses(400, 401)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.getOperation(resource: Resource<*>) {
    put("operationId", "get${resource.modelName}")
    put("summary", "Gets a ${resource.modelName}, with links to the events the client may execute on it now")
    putJsonArray("parameters") { add(idParameter) }
    putJsonObject("responses") {
        putJsonObject("200") {
            put("description", resource.modelName)
            putJsonObject("headers") {
                putJsonObject("ETag") {
                    putJsonObject("schema") { put("type", "string") }
                }
            }
            putJsonObject("content") {
                putJsonObject("application/json") { put("schema", ref(resource.modelName)) }
            }
        }
        putJsonObject("304") { put("description", "Not modified") }
        errorResponses(401, 404)
    }
}

/** [instance] is true for an event on an existing model. */
private fun kotlinx.serialization.json.JsonObjectBuilder.eventOperation(
    resource: Resource<*>,
    event: Event<*, *>,
    instance: Boolean?,
    requireIdempotencyKey: Boolean,
) {
    put("operationId", "${resource.modelName}${event.name}")
    put("summary", event.name)
    putJsonArray("parameters") {
        if (instance == true) {
            add(idParameter)
            add(
                parameter(
                    "If-Match",
                    "header",
                    buildJsonObject { put("type", "string") },
                    "ETag of the model. The event fails with 412 if the model has changed",
                ),
            )
        }
        add(
            parameter(
                "Idempotency-Key",
                "header",
                buildJsonObject {
                    put("type", "string")
                    put("minLength", 1)
                    put("maxLength", 255)
                },
                "Makes a retry safe. Only the first successful request with the key is applied. A retry gets 409",
                required = requireIdempotencyKey,
            ),
        )
    }
    if (hasParameters(event)) {
        putJsonObject("requestBody") {
            put("required", true)
            putJsonObject("content") {
                putJsonObject("application/json") { put("schema", ref(resource.schemaName(event))) }
            }
        }
    }
    putJsonObject("responses") {
        val modelContent =
            buildJsonObject {
                putJsonObject("application/json") { put("schema", ref(resource.modelName)) }
            }
        if (instance == true) {
            putJsonObject("200") {
                put("description", "The model after the event")
                put("content", modelContent)
            }
        } else {
            putJsonObject("201") {
                put("description", "The created ${resource.modelName}. Location tells where it is")
                put("content", modelContent)
            }
        }
        putJsonObject("204") {
            put("description", "Done. There is no model to show, or the client may not read it")
        }
        errorResponses(400, 401, 403, 404, 409, 415, 422)
        if (instance == true) put("412", problemResponse("The model has changed since the ETag in If-Match"))
    }
}
