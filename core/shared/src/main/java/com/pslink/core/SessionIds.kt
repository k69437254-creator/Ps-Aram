package com.pslink.core

object SessionIds {
    const val MAX_LENGTH = 80

    fun validate(value: String): String? =
        value.takeIf { it.isNotBlank() && it.length <= MAX_LENGTH }
}
