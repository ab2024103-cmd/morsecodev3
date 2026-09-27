package app.morsecode.android.core.util

import app.morsecode.android.core.network.PlayServices

/**
 * §6.15 CONNECTION DOCTOR.
 *
 * Each check is "status glyph (green ✓ / amber ! / red ✕ — always with text,
 * never colour alone), bold label, mono detail line, tap for an inline fix".
 *
 * The evaluation is pure: facts in, verdicts out. That is what lets the six
 * §6.15 checks — plus Bluetooth, location services, the hotspot conflict and
 * the three ports — be tested without a radio, and it keeps the screen from
 * inventing a diagnosis of its own (§20.6).
 */
object DoctorChecks {

    enum class Status { OK, WARN, FAIL }

    data class Check(
        val id: String,
        val status: Status,
        val label: String,
        val detail: String,
        /** The label of the inline fix, when there is one to offer. */
        val fix: String? = null,
    ) {
        /** §4.13: the status is always in the text, never colour alone. */
        val glyph: String get() = when (status) {
            Status.OK -> "✓"
            Status.WARN -> "!"
            Status.FAIL -> "✕"
        }

        val spoken: String get() = "$glyph $label. $detail"
    }

    /** Everything the doctor needs to know, gathered by the screen. */
    data class Facts(
        val wifiConnected: Boolean,
        val wifiSsid: String?,
        val wifiRssi: Int?,
        val peersOnSubnet: Int,
        val subnet: String?,
        val multicastLockHeld: Boolean,
        val playServices: PlayServices.Availability,
        val missingPermissions: List<String>,
        val batteryExempt: Boolean,
        val bluetoothOn: Boolean,
        val locationServicesOn: Boolean,
        val hotspotOn: Boolean,
        val busyPorts: List<Int>,
    )

    fun evaluate(facts: Facts): List<Check> {
        val checks = ArrayList<Check>(10)

        checks.add(
            if (facts.wifiConnected) {
                Check(
                    "wifi", Status.OK, "Wi-Fi connected",
                    listOfNotNull(facts.wifiSsid, facts.wifiRssi?.let { "$it dBm" }).joinToString(" · "),
                )
            } else {
                Check("wifi", Status.FAIL, "Wi-Fi off", "LAN transfers need a network", "Open Wi-Fi settings")
            },
        )

        checks.add(
            when {
                !facts.wifiConnected ->
                    Check("subnet", Status.WARN, "Same network as peers", "Not on a network yet")
                facts.peersOnSubnet > 0 ->
                    Check(
                        "subnet", Status.OK, "Same network as peers",
                        "${facts.peersOnSubnet} peers on ${facts.subnet ?: "this network"}",
                    )
                else ->
                    Check(
                        "subnet", Status.WARN, "Same network as peers",
                        "No peers seen on ${facts.subnet ?: "this network"} yet",
                    )
            },
        )

        checks.add(
            if (facts.multicastLockHeld) {
                Check("multicast", Status.OK, "Multicast lock", "Held · beacon every 1200 ms")
            } else {
                Check("multicast", Status.WARN, "Multicast lock", "Not held — discovery may miss peers")
            },
        )

        val (playLabel, playDetail) = PlayServices.doctorLine(facts.playServices)
        checks.add(
            Check(
                "play", when (facts.playServices) {
                    PlayServices.Availability.AVAILABLE -> Status.OK
                    PlayServices.Availability.OUTDATED -> Status.WARN
                    PlayServices.Availability.UNAVAILABLE -> Status.WARN
                },
                playLabel, playDetail,
            ),
        )

        checks.add(
            if (facts.missingPermissions.isEmpty()) {
                Check("permissions", Status.OK, "Permissions granted", "Nearby · Storage · Battery")
            } else {
                Check(
                    "permissions", Status.FAIL, "Permissions missing",
                    facts.missingPermissions.joinToString(" · ") { it.substringAfterLast('.') },
                    "Grant permissions",
                )
            },
        )

        checks.add(
            if (facts.batteryExempt) {
                Check("battery", Status.OK, "Battery optimization off", "Transfers keep running in the background")
            } else {
                Check(
                    "battery", Status.FAIL, "Battery optimization ON",
                    "Transfers may pause in background",
                    "✓ Request battery exemption",
                )
            },
        )

        checks.add(
            if (facts.bluetoothOn) {
                Check("bluetooth", Status.OK, "Bluetooth on", "Nearby can use it when Wi-Fi cannot")
            } else {
                Check("bluetooth", Status.WARN, "Bluetooth off", "Nearby falls back to Wi-Fi Direct")
            },
        )

        // Pre-31 context only: the platform gates radio discovery behind it and
        // no location value is ever read (§17.5).
        checks.add(
            if (facts.locationServicesOn) {
                Check("location", Status.OK, "Location services on", "Required by Android for radio discovery")
            } else {
                Check(
                    "location", Status.WARN, "Location services off",
                    "Android needs them to scan for nearby radios",
                    "Open location settings",
                )
            },
        )

        // §7.1: STA and AP share one radio on this hardware range.
        if (facts.hotspotOn) {
            checks.add(
                Check(
                    "hotspot", Status.WARN, "Hotspot is on",
                    "Your phone is its own network — peers must join it",
                ),
            )
        }

        checks.add(
            if (facts.busyPorts.isEmpty()) {
                Check("ports", Status.OK, "Ports free", "33455 · 33456 · 33457")
            } else {
                Check(
                    "ports", Status.FAIL, "Port in use",
                    facts.busyPorts.joinToString(" · ") { it.toString() },
                )
            },
        )

        return checks
    }

    /** A one-line verdict for the dashboard's "?" entry. */
    fun summarise(checks: List<Check>): Status = when {
        checks.any { it.status == Status.FAIL } -> Status.FAIL
        checks.any { it.status == Status.WARN } -> Status.WARN
        else -> Status.OK
    }
}
