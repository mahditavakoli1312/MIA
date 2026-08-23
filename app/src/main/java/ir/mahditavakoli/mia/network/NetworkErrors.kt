package ir.mahditavakoli.mia.network

import ir.mahditavakoli.mia.BuildConfig
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turns a raw network exception into a Persian message that says what to do about it.
 *
 * Without this the snackbar shows Java's own English text — «Unable to resolve host
 * "<ref>.supabase.co": No address associated with hostname» — which reads like a crash
 * even though the real cause is configuration: a Supabase project that was deleted or
 * paused, or a SUPABASE_URL typo. Everything else keeps its own message, since repository
 * failures already carry deliberate Persian text.
 */
fun Throwable.toPersianMessage(fallback: String): String = when (this) {
    is UnknownHostException -> {
        // "Unable to resolve host "abc.supabase.co"" — the host is in the message, but the
        // same exception is also what an offline device throws, so name both causes.
        if (isSupabaseHost()) {
            "سرور Supabase پیدا نشد ($SUPABASE_HOST). یا اینترنت وصل نیست، یا پروژهٔ Supabase " +
                "حذف/متوقف (paused) شده است. مقدار SUPABASE_URL را در local.properties بررسی کنید."
        } else {
            "اتصال به اینترنت برقرار نیست."
        }
    }
    is SocketTimeoutException -> "پاسخی از سرور نرسید؛ دوباره تلاش کنید."
    is IOException -> "ارتباط با سرور برقرار نشد؛ اتصال اینترنت را بررسی کنید."
    else -> message ?: fallback
}

/** Host part of the configured SUPABASE_URL, e.g. "abc123.supabase.co" (empty when unset). */
private val SUPABASE_HOST: String =
    BuildConfig.SUPABASE_URL.substringAfter("://").substringBefore('/').trim()

private fun Throwable.isSupabaseHost(): Boolean =
    SUPABASE_HOST.isNotEmpty() && message?.contains(SUPABASE_HOST) == true
