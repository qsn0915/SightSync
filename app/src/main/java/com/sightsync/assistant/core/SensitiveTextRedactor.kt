package com.sightsync.assistant.core

object SensitiveTextRedactor {
    private val verificationCode = Regex("""(?<!\d)\d{6}(?!\d)""")
    private val cardLikeNumber = Regex("""(?:\d[\s-]?){13,19}""")

    fun redact(value: String?, role: String, isPassword: Boolean = false): String? {
        if (value == null) return null

        val roleLower = role.lowercase()
        if (isPassword || roleLower.contains("password")) {
            return "[已隐藏]"
        }

        val compactDigits = value.filter(Char::isDigit)
        if (verificationCode.containsMatchIn(value) && looksLikeVerificationCode(value)) {
            return "[验证码已隐藏]"
        }

        if (compactDigits.length in 13..19 && cardLikeNumber.containsMatchIn(value)) {
            return "[号码已隐藏]"
        }

        return value
    }

    private fun looksLikeVerificationCode(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains("验证码") ||
            lower.contains("校验码") ||
            lower.contains("code") ||
            lower.contains("verification")
    }
}
