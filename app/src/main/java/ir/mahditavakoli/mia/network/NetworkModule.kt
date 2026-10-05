package ir.mahditavakoli.mia.network

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import ir.mahditavakoli.mia.BuildConfig
import ir.mahditavakoli.mia.data.repository.AgentModelMigrator
import ir.mahditavakoli.mia.data.repository.AgentTeamFiles
import ir.mahditavakoli.mia.data.repository.BootstrapFile
import ir.mahditavakoli.mia.data.repository.GitHubRepository
import ir.mahditavakoli.mia.data.repository.RepoBootstrapper
import ir.mahditavakoli.mia.data.model.ProjectType
import ir.mahditavakoli.mia.data.repository.TeamFilesUpdater
import ir.mahditavakoli.mia.data.session.SessionManager
import ir.mahditavakoli.mia.network.gemini.GeminiApi
import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.security.AndroidBase64Decoder
import ir.mahditavakoli.mia.security.AndroidBase64Encoder
import ir.mahditavakoli.mia.security.LibsodiumSecretEncryptor
import ir.mahditavakoli.mia.security.SecretStore
import ir.mahditavakoli.mia.network.minimax.MiniMaxApi
import ir.mahditavakoli.mia.network.openrouter.AgentProvider
import ir.mahditavakoli.mia.network.openrouter.ChatCompleter
import ir.mahditavakoli.mia.network.openrouter.OpenRouterApi
import ir.mahditavakoli.mia.network.supabase.SupabaseApi
import ir.mahditavakoli.mia.network.supabase.SupabaseAuthApi
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit
import retrofit2.create
import java.util.concurrent.TimeUnit

/** Manual, lightweight DI — no framework needed for an app this size. */
object NetworkModule {

    // Set once from MIAApplication.onCreate(), well before any ViewModel/Retrofit client
    // is ever touched — needed for the Chucker HTTP inspector below.
    private lateinit var appContext: Context

    /** Holds the signed-in user's session; read live by the Supabase REST interceptor. */
    lateinit var sessionManager: SessionManager
        private set

    /** Encrypted store for the runtime Gemini/OpenRouter/MiniMax keys + the agent-handled toggle. */
    lateinit var secretStore: SecretStore
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        sessionManager = SessionManager(appContext)
        secretStore = SecretStore(appContext)
    }

    /**
     * One bundled asset: the file in `assets/`, where it lands in a new repo, and which kinds of
     * project get it.
     *
     * [type] is what makes the bootstrap type-aware. `null` means "every project" — that is every
     * file under `.github/`, and it is null rather than "all three types" on purpose: those
     * workflows already detect what they are looking at (`ci.yml` checks for `gradlew` before it
     * builds, `preview-web.yml` for a web entry point before it publishes), so they are not three
     * variants that happen to be identical, they are one file that does not care.
     *
     * A non-null [type] means the file is only correct for that kind of project. Two things fall
     * in there, and both used to be committed into every repo regardless:
     *
     * - `AGENTS.md`, injected into every PO/QC/TEC prompt. All three variants land at the same
     *   repo path, which is exactly why the type has to be recorded here — the path alone can no
     *   longer say which file belongs where.
     * - the design system: four Compose files for Android, four CSS/HTML files for the web, and
     *   deliberately none for [ProjectType.PLAIN].
     *
     * The readable twin of each file under `docs/github/` follows from these three fields, and
     * `scripts/check-assets-sync.sh` derives it by parsing this list: a shared file mirrors the
     * repo layout minus the `.github/` prefix, a typed one sits under its type's directory. That
     * second half is what keeps the three `AGENTS.md` variants — identical repo paths, different
     * contents — from overwriting each other in one folder. Registering an asset here is all it
     * takes for the sync check to cover it, so the rule lives in the checker rather than being
     * restated in Kotlin where nothing would read it.
     */
    private data class Bundled(
        val asset: String,
        val repoPath: String,
        val type: ProjectType? = null
    )

    /**
     * Everything MIA can commit into a repo it creates, and who gets it.
     *
     * The team files — the TEC coding agent, the PO/QC advisor workflow + its script, the PO brief
     * decomposer, the PO re-scoper, the QC pull-request gate, the shared provider/key module every
     * role script calls, the token-spend reporter, the add-to-project and CI workflows, and the
     * Pages publisher that puts a web product's live URL on the repo — stand up the whole
     * free-model AI team and are identical on every kind of project.
     *
     * After them come the typed files. `AGENTS.md` is the one that matters most: it is short and it
     * is injected into every agent prompt, so the Android copy telling an agent to run `./gradlew`
     * is not a harmless extra file on a website — it is the first instruction the agent reads. The
     * design systems follow the same logic; the Android one lands in a fixed `mia.design` package
     * because MIA cannot know what package a repo it just created will end up using, and the web
     * one lands in `src/styles/` as plain CSS custom properties so it is still correct after TEC
     * scaffolds Vite, React, or nothing at all.
     */
    private val BOOTSTRAP_ASSETS = listOf(
        Bundled("agent-issue-worker.yml", ".github/workflows/agent-issue-worker.yml"),
        Bundled("ai-role-review.yml", ".github/workflows/ai-role-review.yml"),
        Bundled("decompose-brief.yml", ".github/workflows/decompose-brief.yml"),
        Bundled("qc-review.yml", ".github/workflows/qc-review.yml"),
        Bundled("add-to-project.yml", ".github/workflows/add-to-project.yml"),
        Bundled("unblock-dependents.yml", ".github/workflows/unblock-dependents.yml"),
        Bundled("shepherd.yml", ".github/workflows/shepherd.yml"),
        Bundled("ci.yml", ".github/workflows/ci.yml"),
        Bundled("preview-web.yml", ".github/workflows/preview-web.yml"),
        Bundled("agent-voice.js", ".github/scripts/agent-voice.js"),
        Bundled("ai-provider.js", ".github/scripts/ai-provider.js"),
        Bundled("ledger.js", ".github/scripts/ledger.js"),
        Bundled("say.js", ".github/scripts/say.js"),
        Bundled("unblock.js", ".github/scripts/unblock.js"),
        Bundled("triage-failure.js", ".github/scripts/triage-failure.js"),
        Bundled("shepherd.js", ".github/scripts/shepherd.js"),
        Bundled("brief-close.js", ".github/scripts/brief-close.js"),
        Bundled("ai-role-review.js", ".github/scripts/ai-role-review.js"),
        Bundled("po-rebrief.js", ".github/scripts/po-rebrief.js"),
        Bundled("decompose-brief.js", ".github/scripts/decompose-brief.js"),
        Bundled("qc-review.js", ".github/scripts/qc-review.js"),
        Bundled("token-usage.js", ".github/scripts/token-usage.js"),
        Bundled("skills.js", ".github/scripts/skills.js"),
        Bundled("glm-vision.js", ".github/scripts/glm-vision.js"),

        Bundled("skill-mia-po-brief.md", ".github/skills/mia-po-brief/SKILL.md"),
        Bundled("skill-mia-brief-decomposition.md", ".github/skills/mia-brief-decomposition/SKILL.md"),
        Bundled("skill-mia-qc-review.md", ".github/skills/mia-qc-review/SKILL.md"),
        Bundled("skill-mia-tec-implementation.md", ".github/skills/mia-tec-implementation/SKILL.md"),
        Bundled("skill-mia-failure-triage.md", ".github/skills/mia-failure-triage/SKILL.md"),
        Bundled("skill-glmv-visual-brief.md", ".github/skills/glmv-visual-brief/SKILL.md"),
        Bundled("skill-glmocr-doc-intake.md", ".github/skills/glmocr-doc-intake/SKILL.md"),
        Bundled("skill-glm-asset-gen.md", ".github/skills/glm-asset-gen/SKILL.md"),

        Bundled("agents-android.md", "AGENTS.md", ProjectType.ANDROID),
        Bundled("skill-mia-android-compose.md", "skills/mia-android-compose/SKILL.md", ProjectType.ANDROID),
        Bundled("design-tokens.kt", "app/src/main/java/mia/design/Tokens.kt", ProjectType.ANDROID),
        Bundled("design-theme.kt", "app/src/main/java/mia/design/MiaTheme.kt", ProjectType.ANDROID),
        Bundled("design-components.kt", "app/src/main/java/mia/design/MiaComponents.kt", ProjectType.ANDROID),
        Bundled("design-example.kt", "app/src/main/java/mia/design/ExampleScreen.kt", ProjectType.ANDROID),

        Bundled("agents-web.md", "AGENTS.md", ProjectType.WEB),
        Bundled("skill-mia-web-frontend.md", "skills/mia-web-frontend/SKILL.md", ProjectType.WEB),
        Bundled("web-tokens.css", "src/styles/tokens.css", ProjectType.WEB),
        Bundled("web-theme.css", "src/styles/theme.css", ProjectType.WEB),
        Bundled("web-components.css", "src/styles/components.css", ProjectType.WEB),
        Bundled("web-example.html", "src/example.html", ProjectType.WEB),

        Bundled("agents-plain.md", "AGENTS.md", ProjectType.PLAIN),
        Bundled("skill-mia-plain-stack.md", "skills/mia-plain-stack/SKILL.md", ProjectType.PLAIN)
    )

    /**
     * The `.github/` machinery, which is the same on every kind of project.
     *
     * Split out from [readBootstrapFiles] for [teamFilesUpdater]: it refreshes the team files in a
     * repo that already exists, and the type of that repo is not something MIA recorded when it
     * was created. Asking the registry for the untyped files answers the question without having
     * to guess, and means the updater can no longer reach a typed file even by accident.
     */
    fun readTeamFiles(): List<BootstrapFile> = read(BOOTSTRAP_ASSETS.filter { it.type == null })

    /**
     * Everything a new [type] repo gets: the shared team files plus that type's own conventions
     * and design system.
     *
     * The per-role model rewrite happens here rather than after the repo exists because the
     * alternative is a repo that is briefly wrong: bootstrap, then a second pass of commits to
     * move four roles onto the models the user already asked for — visible in the history, and a
     * window in which a `@tec` typed straight after creation runs on the wrong model. The assets
     * on disk are never touched; only the copy being uploaded.
     */
    fun readBootstrapFiles(type: ProjectType): List<BootstrapFile> =
        read(BOOTSTRAP_ASSETS.filter { it.type == null || it.type == type })

    private fun read(bundled: List<Bundled>): List<BootstrapFile> {
        val models = secretStore.defaultModels().filterKeys { it.envSuffix != null }
        return bundled.map { file ->
            val content = appContext.assets.open(file.asset).bufferedReader().use { it.readText() }
            val role = AgentTeamFiles.MODEL_BEARING_PATHS[file.repoPath]
            val withModels = if (role == null) {
                content
            } else {
                AgentTeamFiles.applyRoleModels(content, models, role).text
            }
            BootstrapFile(repoPath = file.repoPath, content = withModels)
        }
    }

    /**
     * Repoints an existing repo's AI team at another model — the counterpart to
     * [repoBootstrapper], which can only ever set the model on a repo it is creating.
     */
    val agentModelMigrator: AgentModelMigrator by lazy {
        AgentModelMigrator(
            api = gitHubApi,
            base64 = AndroidBase64Encoder,
            base64Decoder = AndroidBase64Decoder
        )
    }

    /**
     * Brings an existing repo's `.github` files up to this build's — the counterpart to
     * [repoBootstrapper], which can only ever install them into a repo it is creating.
     */
    val teamFilesUpdater: TeamFilesUpdater by lazy {
        TeamFilesUpdater(
            api = gitHubApi,
            base64 = AndroidBase64Encoder,
            base64Decoder = AndroidBase64Decoder,
            currentFiles = ::readTeamFiles,
            migrator = agentModelMigrator
        )
    }

    /** Wires new repos up to the AI team (workflows + script, labels, secret). */
    val repoBootstrapper: RepoBootstrapper by lazy {
        RepoBootstrapper(
            api = gitHubApi,
            base64 = AndroidBase64Encoder,
            encryptor = LibsodiumSecretEncryptor,
            files = ::readBootstrapFiles
        )
    }

    /**
     * The one GitHub facade the whole app shares — project mirroring, the agent-model picker and
     * the issues screens all go through it.
     *
     * Shared rather than constructed per ViewModel because it caches the authenticated user's
     * login: a fresh instance per screen would spend an extra `GET /user` before its first real
     * call, on a rate limit that repo bootstrapping also draws on.
     */
    val gitHubRepository: GitHubRepository by lazy {
        GitHubRepository(
            api = gitHubApi,
            isConfigured = isGitHubConfigured,
            bootstrapper = repoBootstrapper,
            agentModelMigrator = agentModelMigrator,
            teamFilesUpdater = teamFilesUpdater,
            agentApiKeyProvider = { secretStore.agentApiKey },
            agentFallbackApiKeyProvider = { secretStore.agentFallbackApiKey },
            miniMaxApiKeyProvider = { secretStore.miniMaxApiKey }
        )
    }

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val converterFactory = json.asConverterFactory("application/json".toMediaType())

    // Gemini rejects request parts that carry an explicit null field (e.g. a text part with
    // "inline_data": null), so this instance drops nulls instead of emitting them.
    private val geminiJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val geminiConverterFactory = geminiJson.asConverterFactory("application/json".toMediaType())

    // Same reason as Gemini's: `response_format` is only sent for the step that needs it, and
    // OpenAI-compatible endpoints reject an explicit "response_format": null.
    private val openRouterJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val openRouterConverterFactory = openRouterJson.asConverterFactory("application/json".toMediaType())

    // Shows every request/response (headers, body, timing) in a notification + in-app UI.
    // library-no-op is swapped in for release builds, so this is a complete no-op in production.
    private val chuckerInterceptor by lazy { ChuckerInterceptor.Builder(appContext).build() }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        // BODY logging otherwise prints every request header verbatim, which puts the live
        // OpenRouter/Gemini/GitHub/Supabase keys into logcat in plain text.
        redactHeader("Authorization")
        redactHeader("apikey")
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    // OpenRouter asks callers to identify the app; both headers are optional and public, and
    // only affect how the traffic shows up on the OpenRouter dashboard/leaderboards.
    private val openRouterAttributionInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("HTTP-Referer", "https://github.com/mahditavakoli/mia")
            .addHeader("X-Title", "MIA")
            .build()
        chain.proceed(request)
    }

    private val gitHubAuthInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer ${BuildConfig.GITHUB_TOKEN}")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .build()
        chain.proceed(request)
    }

    // REST calls: authenticate as the signed-in user when there's a session, so RLS
    // scopes every row to them. Falls back to the anon key when signed out (e.g. before
    // login), which RLS will reject for protected tables — that's the intended behavior.
    private val supabaseRestInterceptor = Interceptor { chain ->
        val bearer = sessionManager.accessToken ?: BuildConfig.SUPABASE_ANON_KEY
        val request = chain.request().newBuilder()
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer $bearer")
            .addHeader("Prefer", "return=representation")
            .build()
        chain.proceed(request)
    }

    // Auth (GoTrue) calls happen before the user has a token, so they always use the anon key.
    private val supabaseAuthInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            .build()
        chain.proceed(request)
    }

    /**
     * Typed commands: prompt pre-processing then intent extraction on `minimax/minimax-m3:free`.
     * The key is not in an interceptor — it is passed per call from [secretStore], so a key the
     * user edits in Settings takes effect immediately instead of on the next app start.
     */
    val openRouterApi: OpenRouterApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://openrouter.ai/api/")
            .client(
                OkHttpClient.Builder()
                    // Uncapped output (see ChatCompletionRequest.maxTokens) means one intent
                    // extraction can spend minutes thinking at full reasoning effort (see
                    // ChatCompletionRequest.reasoning) and then writing Markdown briefs for
                    // several tasks. OkHttp's 10s default read timeout kills that mid-answer,
                    // so the command fails with a SocketTimeoutException after the model
                    // already did the work — and a timeout is the one failure that costs the
                    // user the whole wait and gives nothing back.
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.MINUTES)
                    .callTimeout(6, TimeUnit.MINUTES)
                    .addInterceptor(openRouterAttributionInterceptor)
                    .addInterceptor(loggingInterceptor)
                    .addInterceptor(chuckerInterceptor)
                    .build()
            )
            .addConverterFactory(openRouterConverterFactory)
            .build()
            .create()
    }

    /**
     * The same two typed-command calls, but on MiniMax's own platform — the paid alternative to
     * the free OpenRouter tier, selectable in Settings. Timeouts match OpenRouter's for exactly
     * the same reason: the answer is uncapped and full-effort thinking can run for minutes.
     */
    val miniMaxApi: MiniMaxApi by lazy {
        Retrofit.Builder()
            .baseUrl(AgentProvider.MINIMAX.apiBaseUrl)
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.MINUTES)
                    .callTimeout(6, TimeUnit.MINUTES)
                    .addInterceptor(loggingInterceptor)
                    .addInterceptor(chuckerInterceptor)
                    .build()
            )
            .addConverterFactory(openRouterConverterFactory)
            .build()
            .create()
    }

    /**
     * The chat client for [provider], so callers pick a model and get the right host for free.
     * Both sides speak the same OpenAI-compatible bodies — see
     * [ir.mahditavakoli.mia.network.openrouter.ChatCompleter].
     */
    fun chatCompleterFor(provider: AgentProvider): ChatCompleter = when (provider) {
        AgentProvider.OPENROUTER -> ChatCompleter(openRouterApi::chatCompletion)
        AgentProvider.MINIMAX -> ChatCompleter(miniMaxApi::chatCompletion)
    }

    /** Multimodal voice→intent: takes recorded audio and returns the intent JSON directly. */
    val geminiApi: GeminiApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(
                OkHttpClient.Builder()
                    .addInterceptor(loggingInterceptor)
                    .addInterceptor(chuckerInterceptor)
                    .build()
            )
            .addConverterFactory(geminiConverterFactory)
            .build()
            .create()
    }

    /** True only when a GitHub token is configured; callers skip GitHub mirroring otherwise. */
    val isGitHubConfigured: Boolean get() = BuildConfig.GITHUB_TOKEN.isNotBlank()

    val gitHubApi: GitHubApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(
                OkHttpClient.Builder()
                    .addInterceptor(gitHubAuthInterceptor)
                    .addInterceptor(loggingInterceptor)
                    .addInterceptor(chuckerInterceptor)
                    .build()
            )
            .addConverterFactory(converterFactory)
            .build()
            .create()
    }

    val supabaseApi: SupabaseApi by lazy {
        // Retrofit validates the base URL eagerly (needs a real scheme://host) — if
        // SUPABASE_URL isn't set in local.properties yet, fall back to a syntactically
        // valid placeholder so this doesn't crash the whole app at startup. Calls will
        // still fail, but as a normal network error caught by the repositories' runCatching.
        val supabaseUrl = BuildConfig.SUPABASE_URL.ifBlank { "https://supabase-not-configured.invalid" }
        Retrofit.Builder()
            .baseUrl("$supabaseUrl/rest/v1/")
            .client(
                OkHttpClient.Builder()
                    .addInterceptor(supabaseRestInterceptor)
                    .addInterceptor(loggingInterceptor)
                    .addInterceptor(chuckerInterceptor)
                    .build()
            )
            .addConverterFactory(converterFactory)
            .build()
            .create()
    }

    val supabaseAuthApi: SupabaseAuthApi by lazy {
        val supabaseUrl = BuildConfig.SUPABASE_URL.ifBlank { "https://supabase-not-configured.invalid" }
        Retrofit.Builder()
            .baseUrl("$supabaseUrl/auth/v1/")
            .client(
                OkHttpClient.Builder()
                    .addInterceptor(supabaseAuthInterceptor)
                    .addInterceptor(loggingInterceptor)
                    .addInterceptor(chuckerInterceptor)
                    .build()
            )
            .addConverterFactory(converterFactory)
            .build()
            .create()
    }
}
