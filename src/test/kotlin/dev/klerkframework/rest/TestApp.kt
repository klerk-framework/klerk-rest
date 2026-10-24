package dev.klerkframework.rest

import dev.klerkframework.klerk.ActorIdentity
import dev.klerkframework.klerk.AttachedBlobID
import dev.klerkframework.klerk.CommandRuleArgs
import dev.klerkframework.klerk.DefaultTranslation
import dev.klerkframework.klerk.EventRuleArgs
import dev.klerkframework.klerk.EventVisibility.External
import dev.klerkframework.klerk.InstanceEventArgs
import dev.klerkframework.klerk.InstanceEventWithParameters
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.KlerkSettings
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.ModelReadRuleArgs
import dev.klerkframework.klerk.NegativeAuthorization
import dev.klerkframework.klerk.PositiveAuthorization
import dev.klerkframework.klerk.PropertyReadRuleArgs
import dev.klerkframework.klerk.SpecificationBuilder
import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.Translation
import dev.klerkframework.klerk.Unauthenticated
import dev.klerkframework.klerk.VoidEventArgs
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.datatypes.BooleanContainer
import dev.klerkframework.klerk.datatypes.DateContainer
import dev.klerkframework.klerk.datatypes.DurationContainer
import dev.klerkframework.klerk.datatypes.EnumContainer
import dev.klerkframework.klerk.datatypes.GeoPosition
import dev.klerkframework.klerk.datatypes.GeoPositionContainer
import dev.klerkframework.klerk.datatypes.InstantContainer
import dev.klerkframework.klerk.datatypes.IntContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.storage.RamStorage
import dev.klerkframework.klerk.view.ModelViews
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

suspend fun startKlerk(): Klerk<Ctx, Views> {
    val views = Views(NoteViews())
    val specification =
        SpecificationBuilder<Ctx, Views>(views).build {
            eventLogRetention(afterModelDeletion = null, paramsAndExtra = null)
            managedModels {
                model(Note::class, noteStateMachine(views), views.notes)
            }
            authorization {
                readModels {
                    positive(::everybodyCanRead)
                    negative(::unauthenticatedCannotReadSecrets)
                }
                readProperties {
                    positive(::everybodyCanReadAllProperties)
                }
                commands {
                    positive(::everybodyCanDoEverything)
                    negative(::unauthenticatedCannotAnnotate)
                }
            }
            systemContextProvider { Ctx(SystemIdentity) }
        }
    return Klerk.create(specification, KlerkSettings(persistence = RamStorage())).also { it.meta.start() }
}

data class Ctx(
    override val actor: ActorIdentity,
    override val eventLogExtra: String? = null,
    override val time: Instant = Clock.System.now(),
    override val translation: Translation = DefaultTranslation,
) : KlerkContext

data class Views(
    val notes: NoteViews,
)

class NoteViews : ModelViews<Note, Ctx>() {
    val long = all.filter { it.props.title.value.length > 3 }.register("long", description = "Notes with a long title")
}

data class Note(
    val title: Title,
    val colour: ColourContainer,
    val subtitle: Title?,
    val related: Set<ModelID<Note>>,
)

class Title(
    value: String,
) : StringContainer(value) {
    override val minLength = 1
    override val maxLength = 100
    override val maxLines = 1
}

enum class Colour { White, Red }

class ColourContainer(
    value: Colour,
) : EnumContainer<Colour>(value)

data class CreateNoteParams(
    val title: Title,
    val subtitle: Title?,
    val colour: ColourContainer = ColourContainer(Colour.White),
    val related: Set<ModelID<Note>> = emptySet(),
)

object CreateNote : VoidEventWithParameters<Note, CreateNoteParams>(External)

data class RenameNoteParams(
    val title: Title,
)

object RenameNote : InstanceEventWithParameters<Note, RenameNoteParams>(External)

data class Position(
    val line: Title,
)

data class AnnotateNoteParams(
    val position: Position,
)

object AnnotateNote : InstanceEventWithParameters<Note, AnnotateNoteParams>(External)

enum class NoteStates { Created }

fun noteStateMachine(views: Views) =
    stateMachine<Note, NoteStates, Ctx, Views> {
        event(CreateNote) {
            validReferences(CreateNoteParams::related, views.notes.all)
        }
        event(RenameNote) {}
        event(AnnotateNote) {}

        voidState {
            onEvent(CreateNote) {
                createModel(NoteStates.Created, ::newNote)
            }
        }

        state(NoteStates.Created) {
            onEvent(RenameNote) {
                update(::renamedNote)
            }
            onEvent(AnnotateNote) {}
        }
    }

fun newNote(args: VoidEventArgs<Note, CreateNoteParams, Ctx, Views>): Note {
    val params = args.command.params
    return Note(params.title, params.colour, params.subtitle, params.related)
}

fun renamedNote(args: InstanceEventArgs<Note, RenameNoteParams, Ctx, Views>): Note =
    args.model.props.copy(title = args.command.params.title)

fun everybodyCanRead(args: ModelReadRuleArgs<Ctx, Views>): PositiveAuthorization = PositiveAuthorization.Allow

fun everybodyCanReadAllProperties(args: PropertyReadRuleArgs<Ctx, Views>): PositiveAuthorization = PositiveAuthorization.Allow

fun unauthenticatedCannotReadSecrets(args: ModelReadRuleArgs<Ctx, Views>): NegativeAuthorization {
    val secret = (args.model.props as? Note)?.title?.value == "secret"
    val denied = secret && args.context.actor is Unauthenticated
    return if (denied) NegativeAuthorization.Deny else NegativeAuthorization.Pass
}

fun everybodyCanDoEverything(args: CommandRuleArgs<*, Ctx, Views>): PositiveAuthorization = PositiveAuthorization.Allow

class At(
    value: Instant,
) : InstantContainer(value)

class Day(
    value: LocalDate,
) : DateContainer(value)

class Length(
    value: Duration,
) : DurationContainer(value)

class Place(
    value: GeoPosition,
) : GeoPositionContainer(value)

data class ScheduleParams(
    val at: At,
    val day: Day,
    val length: Length,
    val place: Place,
    val file: AttachedBlobID,
)

class Pages(
    value: Int,
) : IntContainer(value) {
    override val min = 1
    override val max = 10000
}

data class Stats(
    val pages: Pages,
    val done: Done,
    val at: At,
    val length: Length,
    val place: Place,
    val day: Day,
)

class Done(
    value: Boolean,
) : BooleanContainer(value)

fun unauthenticatedCannotAnnotate(args: EventRuleArgs<Ctx, Views>): NegativeAuthorization =
    if (args.event == AnnotateNote && args.context.actor is Unauthenticated) {
        NegativeAuthorization.Deny
    } else {
        NegativeAuthorization.Pass
    }
