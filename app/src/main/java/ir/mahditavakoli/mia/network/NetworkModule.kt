package ir.mahditavakoli.mia.network

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import ir.mahditavakoli.mia.BuildConfig
import ir.mahditavakoli.mia.data.repository.AgentModelMigrator
import ir.mahditavakoli.mia.data.repository.AgentTeamFiles
import ir.mahditavakoli.mia.data.repository.BootstrapFile
import ir.mahditavakoli.mia.data.repository.GitHubRepository
import ir.mahditavakoli.mia.data.repository.RepoBootstrapper
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
     * The bundled files uploaded to every new repo, mapping each asset to its repo-relative
     * path: the TEC coding agent, the PO/QC advisor workflow + its script, the PO brief
     * decomposer, the QC pull-request gate, the shared provider/key module every role script
     * calls, the token-spend
     * reporter, the add-to-project and CI workflows, the Pages publisher that puts a web
     * product's live URL on the repo, and AGENTS.md — the conventions file every agent prompt
     * injects, which is why it is the one asset that lands at the repo root rather than under
     * .github/. Together they stand up the whole free-model AI team.
     *
     * The four `design-*.kt` assets are the odd ones out: they are not part of the team at all but
     * the design system the team is expected to build UI from, and they land in the app's own
     * source tree under a fixed `mia.design` package — fixed because MIA cannot know what package
     * a repo it just created will end up using.
     */
    private val BOOTSTRAP_ASSETS = listOf(
        "AGENTS.md" to "AGENTS.md",
        "agent-issue-worker.yml" to ".github/workflows/agent-issue-worker.yml",
        "ai-role-review.yml" to ".github/workflows/ai-role-review.yml",
        "decompose-brief.yml" to ".github/workflows/decompose-brief.yml",
        "qc-review.yml" to ".github/workflows/qc-review.yml",
        "add-to-project.yml" to ".github/workflows/add-to-project.yml",
        "ci.yml" to ".github/workflows/ci.yml",
        "preview-web.yml" to ".github/workflows/preview-web.yml",
        "ai-provider.js" to ".github/scripts/ai-provider.js",
        "ai-role-review.js" to ".github/scripts/ai-role-review.js",
        "decompose-brief.js" to ".github/scripts/decompose-brief.js",
        "qc-review.js" to ".github/scripts/qc-review.js",
        "token-usage.js" to ".github/scripts/token-usage.js",
        "design-tokens.kt" to "app/src/main/java/mia/design/Tokens.kt",
        "design-theme.kt" to "app/src/main/java/mia/design/MiaTheme.kt",
        "design-components.kt" to "app/src/main/java/mia/design/MiaComponents.kt",
        "design-example.kt" to "app/src/main/java/mia/design/ExampleScreen.kt"
    )

    /**
     * Reads each bundled asset and pairs it with the path it should live at in a new repo, with
     * the user's per-role model defaults written into it on the way out.
     *
     * The rewrite happens here rather than after the repo exists because the alternative is a
     * repo that is briefly wrong: bootstrap, then a second pass of commits to move four roles
     * onto the models the user already asked for — visible in the history, and a window in which
     * a `@tec` typed straight after creation runs on the wrong model. The assets on disk are
     * never touched; only the copy being uploaded.
     */
    fun readBootstrapFiles(): List<BootstrapFile> {
        val models = secretStore.defaultModels().filterKeys { it.envSuffix != null }
        return BOOTSTRAP_ASSETS.map { (asset, path) ->
            val content = appContext.assets.open(asset).bufferedReader().use { it.readText() }
            val role = AgentTeamFiles.MODEL_BEARING_PATHS[path]
            val withModels = if (role == null) {
                content
            } else {
                AgentTeamFiles.applyRoleModels(content, models, role).text
            }
            BootstrapFile(repoPath = path, content = withModels)
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
