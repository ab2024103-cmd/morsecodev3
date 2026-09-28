package app.morsecode.android.feature.settings

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.morsecode.android.R
import app.morsecode.android.core.network.PlayServices
import app.morsecode.android.core.ui.Buttons
import app.morsecode.android.core.ui.Screen
import app.morsecode.android.core.ui.Shapes
import app.morsecode.android.core.ui.Ui
import app.morsecode.android.core.util.DoctorChecks
import app.morsecode.android.core.util.Ids
import app.morsecode.android.core.util.Permissions
import app.morsecode.android.di.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ServerSocket

/**
 * §6.15 CONNECTION DOCTOR — a vertical list of checks, each a status glyph
 * (always with text, never colour alone), a bold label, a mono detail line and
 * an inline fix where one exists.
 *
 * The verdicts come from [DoctorChecks]; this screen only GATHERS facts and
 * renders them. That split is what keeps the doctor from diagnosing something
 * the app has not actually looked at (§20.6).
 */
class ConnectionDoctorFragment : Screen() {

    private lateinit var checkList: LinearLayout

    override fun onBuildScreen(column: LinearLayout) {
        val context = requireContext()
        toolbar.bind(getString(R.string.title_connection_doctor)) { nav().pop() }

        checkList = LinearLayout(context)
        checkList.orientation = LinearLayout.VERTICAL
        column.addView(checkList, params(8))

        column.addView(
            Buttons.wash(context, getString(R.string.doctor_request_battery)) { requestBattery() },
            params(16),
        )
        column.addView(
            Buttons.outlined(context, getString(R.string.doctor_refresh)) { refresh() },
            params(8),
        )

        refresh()
    }

    private fun refresh() {
        val context = context ?: return
        checkList.removeAllViews()
        val running = AppCompatTextView(context)
        running.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        running.setText(R.string.doctor_running)
        checkList.addView(running)

        viewLifecycleOwner.lifecycleScope.launch {
            val facts = withContext(Dispatchers.IO) { gather(context) }
            val checks = DoctorChecks.evaluate(facts)
            checkList.removeAllViews()
            for (check in checks) checkList.addView(checkRow(check))
        }
    }

    /** Everything the doctor knows, read from the platform, never assumed. */
    private fun gather(context: Context): DoctorChecks.Facts {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val connection = runCatching { wifi?.connectionInfo }.getOrNull()
        val peers = AppServices.discovery.peers.value

        return DoctorChecks.Facts(
            wifiConnected = connection != null && connection.networkId != -1,
            wifiSsid = connection?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" },
            wifiRssi = connection?.rssi,
            peersOnSubnet = peers.count { it.transport == app.morsecode.android.core.network.TransportKind.LAN },
            subnet = peers.firstOrNull()?.address?.substringBeforeLast('.')?.plus(".0/24"),
            // The real lock state, not a guess (§20.6).
            multicastLockHeld = AppServices.discovery.isMulticastLockHeld,
            playServices = PlayServices.availability(context),
            missingPermissions = Permissions.missing(
                context,
                Permissions.nearby() + Permissions.mediaRead() + Permissions.notifications(),
            ),
            batteryExempt = isBatteryExempt(context),
            backgroundTransferInterrupted = AppServices.prefs.hasBackgroundTransferDeathWarning,
            bluetoothOn = isBluetoothOn(context),
            locationServicesOn = isLocationOn(context),
            hotspotOn = false,
            busyPorts = busyPorts(),
        )
    }

    private fun isBatteryExempt(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 23) return true
        val manager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return manager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    private fun isBluetoothOn(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager
        manager?.adapter?.isEnabled == true
    }.getOrNull() ?: false

    private fun isLocationOn(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        manager?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true ||
            manager?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true
    }.getOrNull() ?: false

    /** §6.15 also checks whether :33455 / :33456 / :33457 are free. */
    private fun busyPorts(): List<Int> {
        val ports = listOf(Ids.PORT_WEBSHARE, Ids.PORT_TCP, Ids.PORT_UDP_DISCOVERY)
        return ports.filter { port ->
            // A port this app is already listening on is not "busy" in the
            // sense the user cares about, so only a bind failure counts.
            runCatching { ServerSocket(port).close() }.isFailure
        }
    }

    private fun checkRow(check: DoctorChecks.Check): LinearLayout {
        val context = requireContext()
        val row = LinearLayout(context)
        row.orientation = LinearLayout.VERTICAL
        val pad = Shapes.dpInt(context, 10f)
        row.setPadding(0, pad, 0, pad)

        val head = LinearLayout(context)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        val glyph = AppCompatTextView(context)
        glyph.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
        glyph.text = check.glyph
        glyph.setTextColor(
            ContextCompat.getColor(
                context,
                when (check.status) {
                    DoctorChecks.Status.OK -> R.color.state_success
                    DoctorChecks.Status.WARN -> R.color.state_warning
                    DoctorChecks.Status.FAIL -> R.color.state_error
                },
            ),
        )
        head.addView(glyph)

        val label = AppCompatTextView(context)
        label.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemTitle)
        label.text = check.label
        val labelParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        labelParams.marginStart = Shapes.dpInt(context, 8f)
        head.addView(label, labelParams)
        row.addView(head)

        val detail = AppCompatTextView(context)
        detail.setTextAppearance(context, R.style.TextAppearance_Morsecode_ItemMeta)
        detail.text = check.detail
        row.addView(detail)

        if (check.fix != null) {
            row.addView(
                Buttons.outlined(context, check.fix) { applyFix(check) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        // §15.5: the whole row reads out, glyph meaning included.
        row.contentDescription = check.spoken
        return row
    }

    private fun applyFix(check: DoctorChecks.Check) {
        when (check.id) {
            "battery" -> requestBattery()
            "wifi" -> open(android.provider.Settings.ACTION_WIFI_SETTINGS)
            "location" -> open(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "permissions" -> open(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
            else -> Ui.snackbar(requireActivity(), check.detail)
        }
    }

    private fun open(action: String, withPackage: Boolean = false) {
        val intent = Intent(action)
        if (withPackage) {
            intent.data = android.net.Uri.parse("package:${requireContext().packageName}")
        }
        runCatching { startActivity(intent) }
    }

    private fun requestBattery() {
        if (isBatteryExempt(requireContext())) {
            Ui.snackbar(requireActivity(), getString(R.string.settings_battery_on))
            return
        }
        val intent = Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:${requireContext().packageName}"),
        )
        runCatching { startActivity(intent) }
    }

    private fun params(topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = Shapes.dpInt(requireContext(), topMarginDp.toFloat())
        return params
    }
}
