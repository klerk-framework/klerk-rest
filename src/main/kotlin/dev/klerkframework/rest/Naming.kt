package dev.klerkframework.rest

internal fun toKebabCase(camelCase: String): String =
    camelCase
        .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
        .replace(Regex("([A-Z])([A-Z][a-z])"), "$1-$2")
        .lowercase()

/** The collection name of a model: `Book` is `books`, `Category` is `categories`, `Address` is `addresses`. */
internal fun collectionName(modelName: String): String {
    val name = toKebabCase(modelName)
    return when {
        name.length > 1 && name.endsWith("y") && name[name.length - 2] !in "aeiou" -> name.dropLast(1) + "ies"
        name.endsWith("s") || name.endsWith("x") || name.endsWith("ch") || name.endsWith("sh") -> name + "es"
        else -> name + "s"
    }
}
