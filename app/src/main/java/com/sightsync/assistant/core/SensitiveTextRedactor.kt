package com.sightsync.assistant.core

object SensitiveTextRedactor {
    private val verificationCode = Regex("""(?<!\d)\d{6}(?!\d)""")
    private val cardLikeNumber = Regex("""(?:\d[\s-]?){13,19}""")
    private val phoneNumber = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    private val idCardNumber = Regex("""(?<!\d)[1-9]\d{5}(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)""")
    private val emailAddress = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")

    fun redact(value: String?, role: String, isPassword: Boolean = false): String? {
        if (value == null) return null

        val roleLower = role.lowercase()
        if (isPassword || roleLower.contains("password")) {
            return "[已隐藏]"
        }

        if (emailAddress.containsMatchIn(value)) {
            return "[邮箱已隐藏]"
        }

        if (idCardNumber.containsMatchIn(value)) {
            return "[身份证号已隐藏]"
        }

        if (phoneNumber.containsMatchIn(value)) {
            return "[手机号已隐藏]"
        }

        if (verificationCode.containsMatchIn(value) && looksLikeVerificationCode(value)) {
            return "[验证码已隐藏]"
        }

        val compactDigits = value.filter(Char::isDigit)
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
