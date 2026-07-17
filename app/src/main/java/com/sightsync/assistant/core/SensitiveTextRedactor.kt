package com.sightsync.assistant.core

object SensitiveTextRedactor {
    private val verificationCode = Regex("""(?<!\d)\d{6}(?!\d)""")
    private val cardLikeNumber = Regex("""(?:\d[\s-]?){13,19}""")
    private val phoneNumber = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    private val idCardNumber = Regex("""(?<!\d)[1-9]\d{5}(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)""")
    private val emailAddress = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")
    private val sensitiveKeywords = listOf(
        "密码",
        "验证码",
        "校验码",
        "支付",
        "付款",
        "转账",
        "银行卡",
        "身份证",
        "password",
        "passcode",
        "otp",
        "verification",
        "payment",
        "bank card",
    )

    fun redact(
        value: String?,
        role: String,
        isPassword: Boolean = false,
        context: String? = null,
    ): String? {
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

        if (verificationCode.containsMatchIn(value) && looksLikeVerificationCode(value, context)) {
            return "[验证码已隐藏]"
        }

        val compactDigits = value.filter(Char::isDigit)
        if (compactDigits.length in 13..19 && cardLikeNumber.containsMatchIn(value)) {
            return "[号码已隐藏]"
        }

        return value
    }

    fun isSensitive(
        value: String?,
        role: String,
        isPassword: Boolean = false,
        context: String? = null,
    ): Boolean {
        val combined = listOfNotNull(value, context).joinToString(" ")
        val normalized = combined.lowercase()
        if (isPassword || role.lowercase().contains("password")) return true
        if (sensitiveKeywords.any(normalized::contains)) return true
        if (emailAddress.containsMatchIn(combined)) return true
        if (idCardNumber.containsMatchIn(combined)) return true
        if (phoneNumber.containsMatchIn(combined)) return true
        if (verificationCode.containsMatchIn(combined) && looksLikeVerificationCode(combined, context)) return true

        val compactDigits = combined.filter(Char::isDigit)
        return compactDigits.length in 13..19 && cardLikeNumber.containsMatchIn(combined)
    }

    private fun looksLikeVerificationCode(value: String, context: String?): Boolean {
        val lower = "$value ${context.orEmpty()}".lowercase()
        return lower.contains("验证码") ||
            lower.contains("校验码") ||
            lower.contains("code") ||
            lower.contains("verification")
    }
}
