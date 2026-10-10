package com.achappell.hermesrelay

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * `ANDROID-REL-03`: no app data leaves the device through backup or transfer.
 *
 * Reads the source manifest and rules file (a JVM test runs in the module
 * directory). The built APK is checked again by `scripts/check-apk-metadata.sh`.
 */
class DataExtractionRulesTest {
    private fun parse(path: String): Document {
        val file = File("src/main/$path")
        assertTrue("${file.absolutePath} is missing", file.isFile)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    }

    private val domains = setOf(
        "root",
        "file",
        "database",
        "sharedpref",
        "external",
        "device_root",
        "device_file",
        "device_database",
        "device_sharedpref",
    )

    @Test
    fun backup_stays_off_and_the_extraction_rules_are_referenced() {
        val application = parse("AndroidManifest.xml").getElementsByTagName("application").item(0) as Element

        assertEquals("false", application.getAttribute("android:allowBackup"))
        assertEquals("false", application.getAttribute("android:fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules"))
    }

    @Test
    fun cloud_backup_and_device_transfer_exclude_every_data_domain_and_include_nothing() {
        val rules = parse("res/xml/data_extraction_rules.xml")

        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = rules.getElementsByTagName(section).item(0) as Element
            val excluded = element.getElementsByTagName("exclude").let { nodes ->
                (0 until nodes.length).map { nodes.item(it) as Element }
            }
            assertEquals("$section excludes", domains, excluded.map { it.getAttribute("domain") }.toSet())
            assertTrue("$section excludes whole domains", excluded.all { it.getAttribute("path") == "." })
            assertEquals("$section includes nothing", 0, element.getElementsByTagName("include").length)
        }
    }
}
