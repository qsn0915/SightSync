package com.sightsync.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensitiveTextRedactorTest {
    @Test
    fun redactsPasswordFields() {
        assertEquals("[已隐藏]", SensitiveTextRedactor.redact("secret", role = "EditText", isPassword = true))
    }

    @Test
    fun redactsSixDigitVerificationCodes() {
        assertEquals("[验证码已隐藏]", SensitiveTextRedactor.redact("验证码 123456", role = "TextView"))
    }

    @Test
    fun redactsLongCardLikeNumbers() {
        assertEquals("[号码已隐藏]", SensitiveTextRedactor.redact("6222021234567890123", role = "TextView"))
    }

    @Test
    fun redactsMainlandPhoneNumbers() {
        assertEquals("[手机号已隐藏]", SensitiveTextRedactor.redact("联系人 13812345678", role = "TextView"))
    }

    @Test
    fun redactsMainlandIdCardNumbers() {
        assertEquals("[身份证号已隐藏]", SensitiveTextRedactor.redact("110101199001011234", role = "TextView"))
    }

    @Test
    fun redactsEmailAddresses() {
        assertEquals("[邮箱已隐藏]", SensitiveTextRedactor.redact("test@example.com", role = "TextView"))
    }

    @Test
    fun keepsOrdinaryText() {
        assertEquals("点击确定继续", SensitiveTextRedactor.redact("点击确定继续", role = "Button"))
    }

    @Test
    fun keepsNullTextNull() {
        assertNull(SensitiveTextRedactor.redact(null, role = "TextView"))
    }
}
