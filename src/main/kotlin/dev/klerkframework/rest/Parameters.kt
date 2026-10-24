package dev.klerkframework.rest

import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.AttachedStringID
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.Translation
import dev.klerkframework.klerk.datatypes.AttachedStringContainer
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.datatypes.DecimalContainer
import dev.klerkframework.klerk.datatypes.GeoPosition
import dev.klerkframework.klerk.datatypes.NumberContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.misc.PropertyType
import dev.klerkframework.klerk.misc.SchemaField
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.reflect.full.isSubclassOf
import kotlin.time.Duration
import kotlin.time.Instant

/** A request value the client got wrong. The message is shown to the client. */
internal class RequestException(
    message: String,
) : IllegalArgumentException(message)

/** True if a client must pass [field]. A nullable field without a default becomes null when left out. */
internal fun isRequiredArgument(field: SchemaField): Boolean = field.isRequired && !field.isNullable

/** The JSON schema of [field], or null if klerk-rest cannot express it. Names come from [translation]. */
internal fun fieldSchema(
    field: SchemaField,
    translation: Translation,
): JsonObject? {
    val element = elementSchema(field, translation) ?: return null
    var schema: Map<String, JsonElement> =
        if (field.isCollection) mapOf("type" to JsonPrimitive("array"), "items" to element) else element
    if (field.isNullable) {
        val type =
            buildJsonArray {
                add(schema.getValue("type"))
                add("null")
            }
        schema = schema + ("type" to type)
        (schema["enum"] as? JsonArray)?.let { schema = schema + ("enum" to JsonArray(it + JsonNull)) }
    }
    return JsonObject(schema + ("description" to JsonPrimitive(description(field, translation))))
}

/** The JSON schema of the objects [schema] describes, or null if a field cannot be expressed. */
internal fun objectSchema(
    schema: ObjectSchema<*>,
    translation: Translation,
): JsonObject? {
    val properties = schema.fields.associate { it.name to (fieldSchema(it, translation) ?: return null) }
    return buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        putJsonArray("required") { schema.fields.filter(::isRequiredArgument).forEach { add(it.name) } }
    }
}

private fun elementSchema(
    field: SchemaField,
    translation: Translation,
): JsonObject? {
    field.nestedSchema?.let { return objectSchema(it, translation) }
    val elementType = field.elementType ?: return null
    return buildJsonObject {
        put("type", jsonType(elementType))
        if (field.enumConstants.isNotEmpty()) putJsonArray("enum") { field.enumConstants.forEach { add(it.name) } }
        when (val container = dummyContainer(field)) {
            is StringContainer -> {
                put("minLength", container.minLength)
                put("maxLength", container.maxLength)
                container.regexPattern?.let { put("pattern", it) }
            }
            is DecimalContainer -> {
                val decimals = if (container.scale > 0) "(\\.\\d{1,${container.scale}})?" else ""
                put("pattern", "^-?\\d+$decimals$")
            }
            is NumberContainer<*> -> {
                (container.min as? Number)?.let { put("minimum", it) }
                (container.max as? Number)?.let { put("maximum", it) }
            }
            else -> Unit
        }
    }
}

/** A container of the field's class, to read its constraints from, or null if the field is not a container. */
private fun dummyContainer(field: SchemaField): DataContainer<*>? {
    val isContainer = field.valueClass.isSubclassOf(DataContainer::class)
    return if (isContainer && field.elementType != PropertyType.AttachedDataRef) field.dummyContainer() else null
}

private fun jsonType(type: PropertyType): String =
    when (type) {
        PropertyType.Int, PropertyType.Long, PropertyType.Short, PropertyType.Byte,
        PropertyType.UInt, PropertyType.ULong, PropertyType.UShort, PropertyType.UByte,
        -> "integer"
        PropertyType.Float, PropertyType.Double -> "number"
        PropertyType.Boolean -> "boolean"
        // A string, since a JSON number is often read as a double and would not keep the value exact.
        PropertyType.String, PropertyType.Ref, PropertyType.AttachedDataRef, PropertyType.Instant, PropertyType.Date,
        PropertyType.Duration, PropertyType.Geo, PropertyType.Enum, PropertyType.Decimal,
        -> "string"
    }

private fun description(
    field: SchemaField,
    translation: Translation,
): String {
    val model = field.referencedModel?.simpleName
    val maxLines = (dummyContainer(field) as? StringContainer)?.maxLines?.takeIf { it < Int.MAX_VALUE }
    return listOfNotNull(
        translation.klerk.property(field.property),
        translation.klerk.propertyDescription(field.property),
        model?.let { if (field.isCollection) "IDs of $model models" else "ID of ${withArticle(model)}" },
        field.elementType?.let(::format),
        maxLines?.let { "At most $it lines" },
    ).joinToString(". ")
}

private fun format(type: PropertyType): String? =
    when (type) {
        PropertyType.Instant -> "ISO-8601 instant with offset, e.g. 2026-09-27T14:30:00Z"
        PropertyType.Date -> "ISO-8601 date, e.g. 2026-09-27"
        PropertyType.Duration -> "ISO-8601 duration, e.g. PT1H30M"
        PropertyType.Geo -> "ISO 6709 position, e.g. +48.8577+002.2950/"
        PropertyType.Decimal -> "Decimal number as a string, e.g. \"12.50\""
        PropertyType.AttachedDataRef -> "ID of attached data"
        else -> null
    }

/**
 * Creates an instance of [schema]'s class from the request body [arguments]. A left-out field gets its default value,
 * or null if it is nullable and has none.
 *
 * @throws RequestException if an argument is missing or invalid
 */
internal fun createParameters(
    schema: ObjectSchema<*>,
    arguments: JsonObject?,
): Any {
    val values = mutableMapOf<String, Any?>()
    for (field in schema.fields) {
        val argument = arguments?.get(field.name)
        if (argument == null || argument is JsonNull) {
            when {
                argument is JsonNull && field.isNullable -> values[field.name] = null
                !field.isRequired -> {}
                field.isNullable -> values[field.name] = null
                argument == null -> throw RequestException("Missing property: ${field.name}")
                else -> throw RequestException("${field.name} may not be null")
            }
            continue
        }
        values[field.name] =
            if (field.isCollection) {
                val elements =
                    (argument as? JsonArray ?: throw RequestException("${field.name} must be an array"))
                        .map { parseElement(field, it) }
                if (field.isSet) elements.toSet() else elements
            } else {
                parseElement(field, argument)
            }
    }
    return try {
        schema.create(values)
    } catch (e: IllegalArgumentException) {
        throw RequestException(e.message ?: "Invalid request")
    }
}

private fun parseElement(
    field: SchemaField,
    json: JsonElement,
): Any {
    field.nestedSchema?.let { nested ->
        val obj = json as? JsonObject ?: throw RequestException("${field.name} must be an object")
        return try {
            createParameters(nested, obj)
        } catch (e: RequestException) {
            throw RequestException("${field.name}: ${e.message}")
        }
    }
    val content =
        (json as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
            ?: throw RequestException("${field.name} must be ${field.elementType?.let { withArticle(jsonType(it)) }}")

    fun invalid(): Nothing = throw RequestException("${field.name}: '$content' is not a valid ${field.elementType}")

    return when (field.elementType) {
        PropertyType.Ref -> ModelID<Any>(content.toIntOrNull() ?: invalid())
        PropertyType.String -> field.createContainer(content)
        PropertyType.Int -> field.createContainer(content.toIntOrNull() ?: invalid())
        PropertyType.Long -> field.createContainer(content.toLongOrNull() ?: invalid())
        PropertyType.Short -> field.createContainer(content.toShortOrNull() ?: invalid())
        PropertyType.Byte -> field.createContainer(content.toByteOrNull() ?: invalid())
        PropertyType.UInt -> field.createContainer(content.toUIntOrNull() ?: invalid())
        PropertyType.ULong -> field.createContainer(content.toULongOrNull() ?: invalid())
        PropertyType.UShort -> field.createContainer(content.toUShortOrNull() ?: invalid())
        PropertyType.UByte -> field.createContainer(content.toUByteOrNull() ?: invalid())
        PropertyType.Float -> field.createContainer(content.toFloatOrNull() ?: invalid())
        PropertyType.Double -> field.createContainer(content.toDoubleOrNull() ?: invalid())
        PropertyType.Decimal -> field.createContainer(content.toBigDecimalOrNull() ?: invalid())
        PropertyType.Boolean -> field.createContainer(content.toBooleanStrictOrNull() ?: invalid())
        PropertyType.Enum ->
            field.createContainer(
                field.enumConstants.firstOrNull { it.name == content }
                    ?: throw RequestException(
                        "${field.name} must be one of ${field.enumConstants.joinToString(", ") { it.name }}",
                    ),
            )
        PropertyType.Instant -> field.createContainer(parseOrNull { Instant.parse(content) } ?: invalid())
        PropertyType.Date -> field.createContainer(parseOrNull { LocalDate.parse(content) } ?: invalid())
        PropertyType.Duration -> field.createContainer(Duration.parseOrNull(content) ?: invalid())
        PropertyType.Geo -> field.createContainer(GeoPosition.parseOrNull(content) ?: invalid())
        PropertyType.AttachedDataRef -> attachedDataArgument(field, content.toIntOrNull() ?: invalid())
        null -> throw RequestException("klerk-rest does not support the property ${field.name}")
    }
}

private fun attachedDataArgument(
    field: SchemaField,
    id: Int,
): Any =
    when {
        field.valueClass == AttachedBlobID::class -> AttachedBlobID(id)
        field.valueClass == AttachedStringID::class -> AttachedStringID(id)
        field.valueClass.isSubclassOf(AttachedStringContainer::class) -> field.createContainer(AttachedStringID(id))
        else -> field.createContainer(AttachedBlobID(id))
    }

private inline fun <T> parseOrNull(parse: () -> T): T? =
    try {
        parse()
    } catch (_: IllegalArgumentException) {
        null
    }

/** [noun] with its indefinite article: "a Note", "an Issue". */
internal fun withArticle(noun: String): String = if (noun.first().lowercaseChar() in "aeiou") "an $noun" else "a $noun"
