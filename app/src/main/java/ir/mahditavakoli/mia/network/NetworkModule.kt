package ir.mahditavakoli.mia.network

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import ir.mahditavakoli.mia.BuildConfig
import ir.mahditavakoli.mia.network.openrouter.OpenRouterApi
import ir.mahditavakoli.mia.network.supabase.SupabaseApi
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

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val converterFactory = json.asConverterFactory("application/json".toMediaType())

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
            .addHeader("Authorization", "Bearer ${BuildConfig.OPENROUTER_API_KEY}")
            // Optional, recommended by OpenRouter for attribution/rate-limit analytics.
            .addHeader("HTTP-Referer", "https://github.com/mahditavakoli/mia")
            .addHeader("X-Title", "MIA Voice Task Manager")
            .build()
        chain.proceed(request)
    }

    private val supabaseAuthInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            .addHeader("Prefer", "return=representation")
            .build()
        chain.proceed(request)
    }

    val openRouterApi: OpenRouterApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://openrouter.ai/")
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
