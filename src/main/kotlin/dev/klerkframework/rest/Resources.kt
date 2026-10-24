package dev.klerkframework.rest

import dev.klerkframework.klerk.Event
import dev.klerkframework.klerk.EventReference
import dev.klerkframework.klerk.EventVisibility
import dev.klerkframework.klerk.InstanceEvent
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.VoidEvent
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.view.ModelView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import kotlin.reflect.KClass

private val logger = LoggerFactory.getLogger("dev.klerkframework.rest.Resources")

/** The REST resource of a managed model: its collection, its views and the events that become endpoints. */
internal class Resource<C : KlerkContext>(
    val modelName: String,
    val kClass: KClass<*>,
    val collection: String,
    /** Instance events by their name in the URI. */
    val instanceEvents: Map<String, Event<Any, Any?>>,
    /** Void events by their name in the URI. */
    val voidEvents: Map<String, Event<Any, Any?>>,
    /** Views by the name used in `?view=`. */
    val views: Map<String, ModelView<out Any, C>>,
) {
    private val uriNames: Map<EventReference, String> =
        (instanceEvents + voidEvents).entries.associate { (name, event) -> event.id to name }

    fun uriName(event: Event<*, *>): String? = uriNames[event.id]

    val creatingEvent: Event<Any, Any?>? get() = voidEvents.values.singleOrNull()

    fun schemaName(event: Event<*, *>): String = "$modelName${event.name}Params"
}

/** The resources of every managed model of [klerk]. Events that are not [EventVisibility.External] are left out. */
internal fun <C : KlerkContext, V> resources(klerk: Klerk<C, V>): List<Resource<C>> {
    val collections = klerk.specification.managedModels.groupBy { collectionName(it.kClass.simpleName!!) }
    check(collections.values.all { it.size == 1 }) {
        "Several models would get the same REST collection: ${collections.filterValues { it.size > 1 }.keys}"
    }
    return klerk.specification.managedModels.map { model ->
        val modelName = model.kClass.simpleName!!
        val events =
            model.stateMachine.eventReferences
                .filter { reference ->
                    if (klerk.specification.event(reference).visibility != EventVisibility.External) return@filter false
                    val unsupported =
                        klerk.specification
                            .parametersSchema(reference)
                            ?.fields
                            ?.firstOrNull { fieldSchema(it, dev.klerkframework.klerk.DefaultTranslation) == null }
                    if (unsupported != null) {
                        logger.warn("No REST endpoint for {}: the parameter '{}' is not supported", reference, unsupported.name)
                    }
                    unsupported == null
                }.map { klerk.specification.event(it) }
        Resource(
            modelName = modelName,
            kClass = model.kClass,
            collection = collectionName(modelName),
            instanceEvents = events.filter { it is InstanceEvent<*, *> }.associateBy { toKebabCase(it.name) },
            voidEvents = events.filter { it is VoidEvent<*, *> }.associateBy { toKebabCase(it.name) },
            views =
                klerk.specification.registeredViews
                    .filter { it.modelClass == model.kClass }
                    .associate { it.view.id.shortId to it.view },
        )
    }
}

/** A strong validator that changes whenever the model's properties or state change. */
internal fun etag(model: Model<*>): String {
    val source = "${model.id}:${model.lastPropsUpdatedAt}:${model.lastStateTransitionAt}"
    val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).take(12)
    return "\"${hash.joinToString("") { "%02x".format(it) }}\""
}

internal fun ModelID<*>.href(
    base: String,
    resource: Resource<*>,
): String = "$base/${resource.collection}/$this"

/**
 * The model as JSON. [base] is the absolute URL of the API root. `_links` has `self` and, if [actions] is not null,
 * a link for each event the client may execute on the model now.
 */
internal fun <C : KlerkContext> modelToJson(
    model: Model<*>,
    resource: Resource<C>,
    base: String,
    actions: Collection<Event<*, *>>?,
): JsonObject =
    buildJsonObject {
        val self = model.id.href(base, resource)
        put("id", model.id.toString())
        put("type", resource.modelName)
        put("state", model.state)
        put("createdAt", model.createdAt.toString())
        put("lastModifiedAt", model.lastModifiedAt.toString())
        put("props", valueToJson(model.props))
        putJsonArray("_links") {
            add(link("self", self, "GET"))
            for (event in actions.orEmpty().sortedBy { it.name }) {
                val name = resource.uriName(event) ?: continue
                val params =
                    if (hasParameters(event)) "$base/openapi.json#/components/schemas/${resource.schemaName(event)}" else null
                add(link(name, "$self/events/$name", "POST", event.name, params))
            }
        }
    }

internal fun hasParameters(event: Event<*, *>): Boolean =
    event is dev.klerkframework.klerk.VoidEventWithParameters<*, *> ||
        event is dev.klerkframework.klerk.InstanceEventWithParameters<*, *>

/** The events the actor may execute on [model] now. */
@Suppress("UNCHECKED_CAST")
internal fun Reader<*, *>.possibleActions(model: Model<*>): Set<Event<*, *>> =
    possibleEvents(model.id as ModelID<Any>, EventVisibility.External)
