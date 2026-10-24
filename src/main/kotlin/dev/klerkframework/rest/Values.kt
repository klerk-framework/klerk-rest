@file:OptIn(ExperimentalSerializationApi::class)

package dev.klerkframework.rest

import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.AttachedDataID
import dev.klerkframework.klerk.AttachedStringID
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.datatypes.GeoPosition
import dev.klerkframework.klerk.job.JobID
import dev.klerkframework.klerk.misc.ObjectSchema
import kotlinx.datetime.LocalDate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Numbers and booleans keep their JSON type, except decimals, which are strings to stay exact. Times, durations and
 * positions use RFC 3339, ISO 8601 and ISO 6709 formats.
 */
internal fun valueToJson(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is DataContainer<*> -> containerToJson(value)
        is ModelID<*>, is JobID, is AttachedBlobID, is AttachedStringID, is AttachedDataID ->
            JsonPrimitive(
                value.toString(),
            )
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is BigDecimal -> JsonPrimitive(value.toPlainString())
        is Number -> JsonPrimitive(value)
        is UInt -> JsonPrimitive(value)
        is ULong -> JsonPrimitive(value)
        is UShort -> JsonPrimitive(value)
        is UByte -> JsonPrimitive(value)
        is Enum<*> -> JsonPrimitive(value.name)
        is Instant -> JsonPrimitive(value.toString())
        is Duration -> JsonPrimitive(value.toIsoString())
        is GeoPosition -> JsonPrimitive(value.toISO6709())
        is LocalDate -> JsonPrimitive(value.toString())
        is Iterable<*> -> JsonArray(value.map(::valueToJson))
        else ->
            if (value::class.isData) {
                JsonObject(ObjectSchema.of(value::class).fields.associate { it.name to valueToJson(it.get(value)) })
            } else {
                JsonPrimitive(value.toString())
            }
    }

/** A property the client may not read is shown as the container's masked text. */
private fun containerToJson(container: DataContainer<*>): JsonElement {
    val value = container.valueOrNullIfNotAuthorized ?: return JsonPrimitive(container.toString())
    return valueToJson(value)
}
