package com.pslink.core

object PairingCode {
    fun normalize(input: String): String? {
        val result = StringBuilder()
        for (c in input) {
            val digit = when (c) {
                in '0'..'9' -> c
                in '\u0660'..'\u0669' -> ('0'.code + c.code - 0x0660).toChar()
                in '\u06F0'..'\u06F9' -> ('0'.code + c.code - 0x06F0).toChar()
                ' ', '\t', '\n', '\r', '-' -> continue
                else -> return null
            }
            result.append(digit)
        }
        return result.toString().takeIf {
            it.length == 6 && it.all { c -> c in '0'..'9' }
        }
    }
}
