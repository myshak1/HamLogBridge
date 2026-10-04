package pl.hamlogbridge.upload

import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Maps transport/HTTP failures onto retryable vs fatal outcomes. */
    inline fun call(request: okhttp3.Request, onBody: (Response, String) -> UploadResult): UploadResult =
        try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 || resp.code == 403 ->
                        UploadResult.Fatal("HTTP ${resp.code} - check credentials")
                    resp.code in 500..599 ->
                        UploadResult.Retry("HTTP ${resp.code} - server error")
                    resp.code == 429 -> UploadResult.Retry("HTTP 429 - rate limited")
                    else -> onBody(resp, body)
                }
            }
        } catch (e: IOException) {
            UploadResult.Retry(e.message ?: "network error")
        } catch (e: Exception) {
            UploadResult.Fatal(e.message ?: "unexpected error")
        }

    fun trim(s: String, max: Int = 240): String =
        s.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(max)
}
