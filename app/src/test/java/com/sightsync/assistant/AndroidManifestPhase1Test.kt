package com.sightsync.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AndroidManifestPhase1Test {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test
    fun declaresRuntimePermissionsNeededByThePermissionEntry() {
        val manifest = parseManifest()
        val permissionNames = manifest.getElementsByTagName("uses-permission")
            .asSequence()
            .map { it.getAttributeNS(androidNamespace, "name") }
            .toSet()

        assertTrue(permissionNames.contains("android.permission.RECORD_AUDIO"))
        assertTrue(permissionNames.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertTrue(permissionNames.contains("android.permission.INTERNET"))
        assertTrue(permissionNames.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(permissionNames.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"))
        assertTrue(permissionNames.contains("android.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun allowsCleartextOnlyForLocalDevelopmentProxy() {
        val application = parseManifest().getElementsByTagName("application").item(0) as org.w3c.dom.Element

        assertEquals("@xml/network_security_config", application.getAttributeNS(androidNamespace, "networkSecurityConfig"))

        val config = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(config.contains("<domain>10.0.2.2</domain>"))
        assertTrue(config.contains("<domain>127.0.0.1</domain>"))
    }

    @Test
    fun declaresAccessibilityServiceEntryPoint() {
        val service = parseManifest().getElementsByTagName("service")
            .asSequence()
            .firstOrNull {
                it.getAttributeNS(androidNamespace, "name") ==
                    ".accessibility.AssistantAccessibilityService"
            }

        assertTrue("AssistantAccessibilityService must be registered", service != null)
        requireNotNull(service)
        assertEquals("false", service.getAttributeNS(androidNamespace, "exported"))
        assertEquals("microphone", service.getAttributeNS(androidNamespace, "foregroundServiceType"))
        assertEquals(
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            service.getAttributeNS(androidNamespace, "permission"),
        )

        val actionNames = service.getElementsByTagName("action")
            .asSequence()
            .map { it.getAttributeNS(androidNamespace, "name") }
            .toSet()
        val metadataResources = service.getElementsByTagName("meta-data")
            .asSequence()
            .associate {
                it.getAttributeNS(androidNamespace, "name") to
                    it.getAttributeNS(androidNamespace, "resource")
            }

        assertTrue(actionNames.contains("android.accessibilityservice.AccessibilityService"))
        assertEquals(
            "@xml/assistant_accessibility_service",
            metadataResources["android.accessibilityservice"],
        )
    }

    private fun parseManifest() = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))

    private fun org.w3c.dom.NodeList.asSequence() = sequence {
        for (index in 0 until length) {
            yield(item(index) as org.w3c.dom.Element)
        }
    }
}
