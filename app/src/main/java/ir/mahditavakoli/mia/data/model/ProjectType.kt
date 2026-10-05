package ir.mahditavakoli.mia.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What kind of product a repo MIA creates is going to hold.
 *
 * It exists because the bootstrap is not one set of files. Two of the things MIA commits into a
 * new repo are only correct for one kind of project:
 *
 * - **`AGENTS.md`**, which is injected into *every* PO/QC/TEC prompt. The Android copy opens with
 *   "Android, Kotlin, Jetpack Compose, Min SDK 24" and tells the agent to run `./gradlew`. On a
 *   website that is not merely useless, it is an instruction — the agent is being told to build
 *   the wrong thing before it has read the issue.
 * - **The design system**, which for Android is four Compose files under `mia/design/` and for the
 *   web is a set of CSS custom properties. Committing the Kotlin ones into a web repo leaves 849
 *   lines of dead Compose in a tree that will never compile it.
 *
 * Everything under `.github/` is deliberately NOT typed: `ci.yml` looks for `gradlew` before it
 * builds and `preview-web.yml` looks for a web entry point before it publishes, so the same
 * workflows are already right on every kind of repo. Adding a type there would be inventing a
 * distinction the files themselves do not need.
 *
 * @param id the wire name — the value the intent classifier returns, and the directory each
 *        type's readable copies live in under `docs/github/`.
 * @param persianLabel what the confirmation sheet calls it.
 * @param persianHint one line on what the choice actually changes, shown under the picker,
 *        because "Android / وب / ساده" alone does not tell a reader that it decides the
 *        conventions file every agent will read.
 */
@Serializable
enum class ProjectType(
    val id: String,
    val persianLabel: String,
    val persianHint: String
) {
    @SerialName("android")
    ANDROID(
        id = "android",
        persianLabel = "اندروید",
        persianHint = "کاتلین و Jetpack Compose، به‌همراه دیزاین‌سیستم mia/design"
    ),

    @SerialName("web")
    WEB(
        id = "web",
        persianLabel = "وب",
        persianHint = "HTML/CSS/JS، به‌همراه توکن‌های CSS و انتشار خودکار روی GitHub Pages"
    ),

    /**
     * Anything with no user interface to theme: a CLI, a library, a pile of scripts.
     *
     * It gets a conventions file and no design system at all, which is the honest answer — a
     * theme committed into a repo that renders nothing is a file the agent will eventually try
     * to use, and inventing a Compose or CSS palette for a command-line tool is worse than
     * admitting there is nothing to invent.
     */
    @SerialName("plain")
    PLAIN(
        id = "plain",
        persianLabel = "ساده / سایر",
        persianHint = "بدون دیزاین‌سیستم — برای CLI، کتابخانه، اسکریپت یا هر چیز بدون رابط کاربری"
    );

    companion object {
        /**
         * What a repo becomes when nobody said otherwise.
         *
         * Android, because that is what every repo MIA has created so far already got, and a
         * default that silently changes the shape of existing behaviour is not a default.
         */
        val DEFAULT = ANDROID

        /** Tolerant of case and of a model that answered with the enum name rather than the id. */
        fun fromId(value: String?): ProjectType? =
            entries.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * Reads a project type without letting an unrecognised one destroy the command it arrived in.
 *
 * The classifier is a free model told to answer with one of three words, and sooner or later it
 * answers "ios" or "desktop" or "backend" instead. With the generated enum serializer that is a
 * `SerializationException`, and because the intents arrive as one array, it does not cost the app
 * the `project_type` — it costs the user the whole batch: the create_project AND every add_task
 * that was understood perfectly well alongside it.
 *
 * So an unknown value decodes to null, which is the same thing the field means when the model
 * declines to guess: the confirmation sheet asks. A wrong guess would be silent and permanent; a
 * question is neither.
 *
 * Deliberately NOT solved with `coerceInputValues` on the shared `Json`: that would apply the same
 * forgiveness to every defaulted field in every model the app parses, and this is the one field
 * that needs it.
 */
object LenientProjectTypeSerializer : KSerializer<ProjectType?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ProjectType", PrimitiveKind.STRING).nullable

    /**
     * A nullable descriptor means kotlinx hands the null literal here rather than short-circuiting
     * it, so the null mark has to be read explicitly — and `"project_type": null` is the common
     * case, since every task-level intent in the array carries one.
     */
    override fun deserialize(decoder: Decoder): ProjectType? =
        if (decoder.decodeNotNullMark()) ProjectType.fromId(decoder.decodeString()) else decoder.decodeNull()

    override fun serialize(encoder: Encoder, value: ProjectType?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.id)
    }
}
