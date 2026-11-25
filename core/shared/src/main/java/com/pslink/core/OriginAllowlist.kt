package com.pslink.core

import java.net.URI
import java.util.Locale

class OriginAllowlist(configuredOrigins: String) {
    private val allowedOrigins = configuredOrigins
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { normalizeOrigin(it) ?: throw IllegalArgumentException("Invalid configured Origin: $it") }
        .toSet()

    fun allows(origin: String?): Boolean {
        if (origin == null) return true
        val normalized = normalizeOrigin(origin) ?: return false
        return normalized in allowedOrigins
    }

    private fun normalizeOrigin(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val path = uri.rawPath.orEmpty()
        if (!uri.isAbsolute || uri.rawUserInfo != null ||
            (path.isNotEmpty() && path != "/") ||
            uri.rawQuery != null || uri.rawFragment != null) return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val port = uri.port
        if (port !in -1..65535 || port == 0) return null
        val defaultPort = (scheme == "http" && port == 80) ||
            (scheme == "https" && port == 443)
        val authority = if (port == -1 || defaultPort) host else "$host:$port"
        return "$scheme://$authority"
    }
}
