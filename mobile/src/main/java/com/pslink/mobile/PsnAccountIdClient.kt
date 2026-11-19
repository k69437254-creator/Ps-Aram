// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PSN Account-ID exchange compatible with the pinned Chiaki-NG Android/desktop flow.
 * The user authenticates on Sony's website in the system browser and pastes the final
 * Sony Remote Play redirect URL. Passwords and OAuth tokens are never persisted.
 *
 * Sony's OAuth endpoints/client credentials are not a documented public API contract;
 * they are mirrored from the pinned upstream Chiaki-NG implementation and may change.
 */
object PsnAccountIdClient {
    private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
    private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
    private const val AUTHORIZATION_ENDPOINT =
        "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize"
    private const val TOKEN_ENDPOINT =
        "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
    private const val REDIRECT_URI =
        "https://remoteplay.dl.playstation.net/remoteplay/redirect"
    private const val SCOPE =
        "psn:clientapp referenceDataService:countryConfig.read " +
            "pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"
    private const val MAX_REDIRECT_LENGTH = 8_192
    private const val MAX_RESPONSE_BYTES = 512 * 1_024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    enum class Failure {
        INVALID_REDIRECT,
        AUTHORIZATION_DENIED,
        TOKEN_EXCHANGE,
        MISSING_ACCESS_TOKEN,
        ACCOUNT_LOOKUP,
        MISSING_USER_ID,
        INVALID_RESPONSE
    }

    class PsnAccountIdException(
        val failure: Failure,
        val httpStatus: Int? = null
    ) : Exception("PSN account ID lookup failed: $failure")

    /** Opens the same Sony Remote Play OAuth authorization page used by Chiaki-NG. */
    fun loginUri(): Uri = Uri.parse(AUTHORIZATION_ENDPOINT).buildUpon()
        .appendQueryParameter("service_entity", "urn:service-entity:psn")
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("client_id", CLIENT_ID)
        .appendQueryParameter("redirect_uri", REDIRECT_URI)
        .appendQueryParameter("scope", SCOPE)
        .appendQueryParameter("request_locale", "en_US")
        .appendQueryParameter("ui", "pr")
        .appendQueryParameter("service_logo", "ps")
        .appendQueryParameter("layout_type", "popup")
        .appendQueryParameter("smcid", "remoteplay")
        .appendQueryParameter("prompt", "always")
        .appendQueryParameter("PlatformPrivacyWs1", "minimal")
        .build()

    /**
     * Exchanges a copied Sony redirect URL for the user's PSN Account-ID.
     * Call from Dispatchers.IO; this method performs blocking HTTPS requests.
     */
    fun accountIdFromRedirect(redirectUrl: String): String {
        val authorizationCode = extractAuthorizationCode(redirectUrl)
        val tokenJson = requestJson(
            url = URL(TOKEN_ENDPOINT),
            method = "POST",
            body = formEncode(
                linkedMapOf(
                    "grant_type" to "authorization_code",
                    "code" to authorizationCode,
                    "scope" to SCOPE,
                    "redirect_uri" to REDIRECT_URI
                )
            ).toByteArray(Charsets.UTF_8),
            failure = Failure.TOKEN_EXCHANGE
        )

        val accessToken = tokenJson.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw PsnAccountIdException(Failure.MISSING_ACCESS_TOKEN)

        // This token-info request and Basic authentication match the pinned upstream flow.
        val accountUrl = URL("$TOKEN_ENDPOINT/${Uri.encode(accessToken)}")
        val accountJson = requestJson(
            url = accountUrl,
            method = "GET",
            body = null,
            failure = Failure.ACCOUNT_LOOKUP
        )

        val userIdText = accountJson.optString("user_id")
        if (userIdText.isBlank() || !userIdText.all { it in '0'..'9' }) {
            throw PsnAccountIdException(Failure.MISSING_USER_ID)
        }
        val userId = userIdText.toLongOrNull()
            ?: throw PsnAccountIdException(Failure.MISSING_USER_ID)
        if (userId <= 0L) throw PsnAccountIdException(Failure.MISSING_USER_ID)

        // Chiaki-NG serializes the numeric PSN user_id as an unsigned-looking 8-byte
        // little-endian value, then Base64-encodes those exact bytes.
        val accountIdBytes = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(userId)
            .array()
        return Base64.encodeToString(accountIdBytes, Base64.NO_WRAP)
    }

    private fun extractAuthorizationCode(rawUrl: String): String {
        if (rawUrl.isBlank() || rawUrl.length > MAX_REDIRECT_LENGTH) {
            throw PsnAccountIdException(Failure.INVALID_REDIRECT)
        }

        val parsed = try {
            URI(rawUrl.trim())
        } catch (_: Exception) {
            throw PsnAccountIdException(Failure.INVALID_REDIRECT)
        }
        val validHost = parsed.host?.equals("remoteplay.dl.playstation.net", ignoreCase = true) == true
        val validPort = parsed.port == -1 || parsed.port == 443
        if (!parsed.scheme.equals("https", ignoreCase = true) || !validHost ||
            parsed.path != "/remoteplay/redirect" || !validPort || parsed.userInfo != null ||
            parsed.rawFragment != null) {
            throw PsnAccountIdException(Failure.INVALID_REDIRECT)
        }

        val redirect = Uri.parse(rawUrl.trim())
        if (!redirect.getQueryParameter("error").isNullOrBlank()) {
            throw PsnAccountIdException(Failure.AUTHORIZATION_DENIED)
        }
        val codes = redirect.getQueryParameters("code")
        if (codes.size != 1) throw PsnAccountIdException(Failure.INVALID_REDIRECT)
        return codes.single().takeIf { it.isNotBlank() && it.length <= 4_096 }
            ?: throw PsnAccountIdException(Failure.INVALID_REDIRECT)
    }

    private fun requestJson(
        url: URL,
        method: String,
        body: ByteArray?,
        failure: Failure
    ): JSONObject {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", basicAuthorization())
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Connection", "close")
        }

        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8"
                )
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { output -> output.write(body) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.use(::readBoundedResponse).orEmpty()
            val json = try {
                JSONObject(responseText)
            } catch (_: Exception) {
                if (status !in 200..299) throw PsnAccountIdException(failure, status)
                throw PsnAccountIdException(Failure.INVALID_RESPONSE, status)
            }
            if (status !in 200..299) throw PsnAccountIdException(failure, status)
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun readBoundedResponse(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) {
                throw PsnAccountIdException(Failure.INVALID_RESPONSE)
            }
            output.write(buffer, 0, count)
        }
        return output.toString("UTF-8")
    }

    private fun basicAuthorization(): String {
        val credentials = "$CLIENT_ID:$CLIENT_SECRET".toByteArray(Charsets.US_ASCII)
        return "Basic ${Base64.encodeToString(credentials, Base64.NO_WRAP)}"
    }

    private fun formEncode(values: Map<String, String>): String = values.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }
}
