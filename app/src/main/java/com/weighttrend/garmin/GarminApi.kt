package com.weighttrend.garmin

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Unofficial Garmin Connect API, as used by the Garmin Connect Android app and
 * python-garminconnect 0.3.x (2026):
 *
 *  1. The user signs in on Garmin's own mobile SSO page (in a WebView) and we
 *     catch the one-time service ticket "ST-…".
 *  2. The ticket is exchanged at diauth.garmin.com for a DI OAuth2 access token
 *     (~1 day) and a rotating refresh token (~30 days).
 *  3. FIT files are uploaded to connectapi.garmin.com/upload-service/upload.
 *
 * Garmin can change any of this without notice; errors are reported, not hidden.
 */
object GarminApi {

    const val LOGIN_URL =
        "https://sso.garmin.com/mobile/sso/en_US/sign-in" +
            "?clientId=GCM_ANDROID_DARK&service=https%3A%2F%2Fmobile.integration.garmin.com%2Fgcm%2Fandroid"
    const val SERVICE_URL = "https://mobile.integration.garmin.com/gcm/android"
    const val SERVICE_HOST = "mobile.integration.garmin.com"

    private const val TOKEN_URL = "https://diauth.garmin.com/di-oauth2-service/oauth/token"
    private const val GRANT_SERVICE_TICKET = "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"
    private const val UPLOAD_URL = "https://connectapi.garmin.com/upload-service/upload"

    private val CLIENT_IDS = listOf(
        "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
        "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
        "GARMIN_CONNECT_MOBILE_ANDROID_DI",
    )

    private val NATIVE_HEADERS = mapOf(
        "User-Agent" to "GCM-Android-5.23",
        "X-Garmin-User-Agent" to "com.garmin.android.apps.connectmobile/5.23; ; Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0",
        "X-Garmin-Paired-App-Version" to "10861",
        "X-Garmin-Client-Platform" to "Android",
        "X-App-Ver" to "10861",
        "X-Lang" to "en",
        "X-GCExperience" to "GC5",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val clientId: String,
        val accessExpiresAtMs: Long,
    )

    class AuthException(message: String) : Exception(message)

    sealed interface UploadResult {
        data object Ok : UploadResult
        data object Duplicate : UploadResult
        data object Unauthorized : UploadResult
        data class Failed(val message: String) : UploadResult
    }

    /** Exchanges a service ticket for tokens. Throws [AuthException] or [IOException]. */
    fun exchangeTicket(ticket: String): Tokens {
        var lastError = "нет ответа"
        for (clientId in CLIENT_IDS) {
            val (code, body) = postForm(
                TOKEN_URL, clientId,
                mapOf(
                    "client_id" to clientId,
                    "service_ticket" to ticket,
                    "grant_type" to GRANT_SERVICE_TICKET,
                    "service_url" to SERVICE_URL,
                ),
            )
            if (code == 429) throw AuthException("Garmin временно ограничил вход (429). Попробуйте позже.")
            if (code in 200..299) return parseTokens(body, clientId)
            lastError = "HTTP $code"
        }
        throw AuthException("Garmin не принял вход ($lastError)")
    }

    /** Gets a new access token. The refresh token rotates, so save the result. */
    fun refresh(tokens: Tokens): Tokens {
        val refresh = tokens.refreshToken ?: throw AuthException("Нет refresh-токена")
        val (code, body) = postForm(
            TOKEN_URL, tokens.clientId,
            mapOf("grant_type" to "refresh_token", "client_id" to tokens.clientId, "refresh_token" to refresh),
        )
        if (code !in 200..299) throw AuthException("Не удалось продлить вход в Garmin (HTTP $code)")
        return parseTokens(body, tokens.clientId).let { if (it.refreshToken == null) it.copy(refreshToken = refresh) else it }
    }

    fun upload(tokens: Tokens, fileName: String, fit: ByteArray): UploadResult {
        val boundary = "----WeightTrend${System.currentTimeMillis()}"
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\n".toByteArray())
            write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n".toByteArray())
            write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(fit)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()

        val conn = open(UPLOAD_URL)
        conn.setRequestProperty("Authorization", "Bearer ${tokens.accessToken}")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val text = readBody(conn)
        return when {
            code == 401 || code == 403 -> UploadResult.Unauthorized
            code == 409 -> UploadResult.Duplicate
            code in 200..299 -> {
                // Garmin answers 2xx even when it rejected the file; look inside.
                val failures = runCatching {
                    JSONObject(text).optJSONObject("detailedImportResult")?.optJSONArray("failures")
                }.getOrNull()
                if (failures != null && failures.length() > 0) {
                    val msg = failures.toString()
                    if (msg.contains("Duplicate", ignoreCase = true)) UploadResult.Duplicate
                    else UploadResult.Failed("Garmin отклонил файл: ${msg.take(200)}")
                } else UploadResult.Ok
            }
            else -> UploadResult.Failed("HTTP $code ${text.take(200)}")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun parseTokens(body: String, clientId: String): Tokens {
        val json = runCatching { JSONObject(body) }.getOrElse { throw AuthException("Непонятный ответ Garmin") }
        val access = json.optString("access_token").takeIf { it.isNotEmpty() } ?: throw AuthException("Garmin не выдал токен")
        val expiresIn = json.optLong("expires_in", 3600L)
        val expFromJwt = jwtClaim(access, "exp")?.toLongOrNull()?.times(1000)
        return Tokens(
            accessToken = access,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() },
            clientId = jwtClaim(access, "client_id") ?: clientId,
            accessExpiresAtMs = expFromJwt ?: (System.currentTimeMillis() + expiresIn * 1000),
        )
    }

    private fun jwtClaim(jwt: String, name: String): String? = runCatching {
        val payload = jwt.split('.')[1]
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        JSONObject(json).opt(name)?.toString()
    }.getOrNull()

    private fun postForm(url: String, clientId: String, form: Map<String, String>): Pair<Int, String> {
        val body = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }.toByteArray()
        val conn = open(url)
        conn.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("$clientId:".toByteArray(), Base64.NO_WRAP))
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        return conn.responseCode to readBody(conn)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            NATIVE_HEADERS.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun readBody(conn: HttpURLConnection): String =
        runCatching {
            (if (conn.responseCode in 200..399) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
        }.getOrDefault("")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
