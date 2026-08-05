package ir.mahditavakoli.mia.network

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import ir.mahditavakoli.mia.BuildConfig
import ir.mahditavakoli.mia.data.repository.BootstrapFile
import ir.mahditavakoli.mia.data.repository.RepoBootstrapper
import ir.mahditavakoli.mia.data.session.SessionManager
import ir.mahditavakoli.mia.network.gemini.GeminiApi
import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.security.AndroidBase64Encoder
import ir.mahditavakoli.mia.security.LibsodiumSecretEncryptor
import ir.mahditavakoli.mia.security.SecretStore
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

/** Manual, lightweight DI — no framework needed for an app this size. */
object NetworkModule {

    // Set once from MIAApplication.onCreate(), well before any ViewModel/Retrofit client
    // is ever touched — needed for the Chucker HTTP inspector below.
    private lateinit var appContext: Context

    /** Holds the signed-in user's session; read live by the Supabase REST interceptor. */
    lateinit var sessionManager: SessionManager
        private set

    /** Encrypted store for the runtime Gemini API key + the agent-handled default toggle. */
    lateinit var secretStore: SecretStore
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        sessionManager = SessionManager(appContext)
        secretStore = SecretStore(appContext)
    }

    /**
     * The bundled files uploaded to every new repo, mapping each asset to its repo-relative
     * path: the TEC coding agent, the PO/QC advisor workflow + its script, the token-spend
     * reporter, and the add-to-project and CI workflows. Together they stand up the whole
     * free-model AI team.
     */
    private val BOOTSTRAP_ASSETS = listOf(
        "agent-issue-worker.yml" to ".github/workflows/agent-issue-worker.yml",
        "ai-role-review.yml" to ".github/workflows/ai-role-review.yml",
        "add-to-project.yml" to ".github/workflows/add-to-project.yml",
        "ci.yml" to ".github/workflows/ci.yml",
        "ai-role-review.js" to ".github/scripts/ai-role-review.js",
        "token-usage.js" to ".github/scripts/token-usage.js"
    )

    /** Reads each bundled asset and pairs it with the path it should live at in a new repo. */
    fun readBootstrapFiles(): List<BootstrapFile> = BOOTSTRAP_ASSETS.map { (asset, path) ->
        val content = appContext.assets.open(asset).bufferedReader().use { it.readText() }
        BootstrapFile(repoPath = path, content = content)
    }

    /** Wires new repos up to the AI team (workflows + script, labels, secret). */
    val repoBootstrapper: RepoBootstrapper by lazy {
        RepoBootstrapper(
            api = gitHubApi,
            base64 = AndroidBase64Encoder,
            encryptor = LibsodiumSecretEncryptor,
            files = readBootstrapFiles()
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

    // Shows every request/response (headers, body, timing) in a notification + in-app UI.
    // library-no-op is swapped in for release builds, so this is a complete no-op in production.
    private val chuckerInterceptor by lazy { ChuckerInterceptor.Builder(appContext).build() }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    private val openRouterAuthInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer ${BuildConfig.GAPGPT_API_KEY}")
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

    val openRouterApi: OpenRouterApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.gapgpt.app/")
            .client(
                OkHttpClient.Builder()
                    .addInterceptor(openRouterAuthInterceptor)
                    .addInterceptor(loggingInterceptor)
                    .addInterceptor(chuckerInterceptor)
                    .build()
            )
            .addConverterFactory(converterFactory)
            .build()
            .create()
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
