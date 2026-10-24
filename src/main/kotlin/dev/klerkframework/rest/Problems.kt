package dev.klerkframework.rest

import dev.klerkframework.klerk.AuthorizationProblem
import dev.klerkframework.klerk.BadRequestProblem
import dev.klerkframework.klerk.IdempotenceProblem
import dev.klerkframework.klerk.InternalProblem
import dev.klerkframework.klerk.InvalidPropertyCollectionProblem
import dev.klerkframework.klerk.InvalidPropertyProblem
import dev.klerkframework.klerk.KlerkErrorCode
import dev.klerkframework.klerk.NotFoundProblem
import dev.klerkframework.klerk.PreventedByRuleProblem
import dev.klerkframework.klerk.Problem
import dev.klerkframework.klerk.ServerStateProblem
import dev.klerkframework.klerk.StateProblem
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal val ProblemJson: ContentType = ContentType("application", "problem+json")

internal fun problemType(code: KlerkErrorCode): String = "urn:klerk:error:${code.code}"

internal fun statusOf(problem: Problem): HttpStatusCode =
    when (problem) {
        is NotFoundProblem -> HttpStatusCode.NotFound
        is AuthorizationProblem -> HttpStatusCode.Forbidden
        is BadRequestProblem -> HttpStatusCode.BadRequest
        is InvalidPropertyProblem, is InvalidPropertyCollectionProblem, is PreventedByRuleProblem ->
            HttpStatusCode.UnprocessableEntity
        is StateProblem -> HttpStatusCode.Conflict
        is IdempotenceProblem -> HttpStatusCode.Conflict
        is ServerStateProblem -> HttpStatusCode.ServiceUnavailable
        is InternalProblem -> HttpStatusCode.InternalServerError
    }

/** An RFC 9457 problem document. The status and `type` are those of the first problem. */
internal fun problemDocument(
    problems: List<Problem>,
    instance: String,
): Pair<HttpStatusCode, JsonObject> {
    val first = problems.first()
    val status = statusOf(first)
    return status to
        buildJsonObject {
            put("type", problemType(first.code))
            put("title", status.description)
            put("status", status.value)
            put("detail", first.endUserTranslatedMessage)
            put("instance", instance)
            put("code", first.code.code)
            putJsonArray("problems") {
                for (problem in problems) {
                    add(
                        buildJsonObject {
                            put("type", problemType(problem.code))
                            put("code", problem.code.code)
                            put("detail", problem.endUserTranslatedMessage)
                            if (problem is InvalidPropertyProblem) put("property", problem.propertyName)
                        },
                    )
                }
            }
        }
}

internal suspend fun ApplicationCall.respondProblems(problems: List<Problem>) {
    val (status, document) = problemDocument(problems, request.local.uri)
    respondText(document.toString(), ProblemJson, status)
}

/** A problem that is not caused by a [Problem] from Klerk, e.g. a malformed request. */
internal suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    detail: String,
) {
    val document =
        buildJsonObject {
            put("type", "about:blank")
            put("title", status.description)
            put("status", status.value)
            put("detail", detail)
            put("instance", request.local.uri)
        }
    respondText(document.toString(), ProblemJson, status)
}
