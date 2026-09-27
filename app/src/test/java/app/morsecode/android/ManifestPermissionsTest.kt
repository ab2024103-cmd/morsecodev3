package app.morsecode.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A16, first clause: "Permission list matches §3.5 exactly".
 *
 * The manifest is parsed as text rather than through the platform, so this
 * test fails on the line that adds a permission, not three stages later on a
 * device. §3.5 says "exactly this set, no additions", so the assertion is set
 * equality in both directions.
 */
class ManifestPermissionsTest {

    private val expected = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.CHANGE_WIFI_MULTICAST_STATE",
        "android.permission.WAKE_LOCK",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.BLUETOOTH",
        "android.permission.BLUETOOTH_ADMIN",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    )

    private fun manifestText(): String {
        // Unit tests run with the module directory as the working directory.
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun declared(): Set<String> =
        Regex("""<uses-permission[^>]*android:name="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifestText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun theDeclaredSetIsExactlyTheSpecifiedSet() {
        val actual = declared()
        assertEquals(
            "permissions added beyond §3.5",
            emptySet<String>(),
            actual - expected,
        )
        assertEquals(
            "permissions from §3.5 that are missing",
            emptySet<String>(),
            expected - actual,
        )
    }

    @Test
    fun theForbiddenOnesAreAbsent() {
        val actual = declared()
        // No QR scanner exists anywhere in the product (§11.5, G12).
        assertTrue("CAMERA must not be declared", "android.permission.CAMERA" !in actual)
        // §3.5 names this one explicitly as a do-not-declare.
        assertTrue(
            "CHANGE_NETWORK_STATE must not be declared",
            "android.permission.CHANGE_NETWORK_STATE" !in actual,
        )
    }

    @Test
    fun theApiCeilingsAreDeclared() {
        val text = manifestText()
        // §3.5 gives three permissions an explicit maxSdkVersion; dropping one
        // silently widens what the app asks for on modern Android.
        assertTrue(text.contains("""android:maxSdkVersion="30""""))
        assertTrue(text.contains("""android:maxSdkVersion="32""""))
        assertTrue(text.contains("""android:maxSdkVersion="28""""))
        // Bluetooth scanning and Wi-Fi discovery must not imply location.
        assertTrue(text.contains("""android:usesPermissionFlags="neverForLocation""""))
    }

    @Test
    fun wifiAndBluetoothHardwareAreOptional() {
        val text = manifestText()
        val required = Regex("""<uses-feature[^>]*android:required="true"""", RegexOption.DOT_MATCHES_ALL)
        assertTrue("no hardware feature may be required (§3.5)", !required.containsMatchIn(text))
        assertTrue(text.contains("android.hardware.wifi"))
        assertTrue(text.contains("android.hardware.bluetooth"))
    }
}
