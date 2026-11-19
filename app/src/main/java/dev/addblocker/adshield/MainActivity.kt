package dev.addblocker.adshield

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.addblocker.adshield.data.Prefs
import dev.addblocker.adshield.data.RulesRepository
import dev.addblocker.adshield.data.StatsRepository
import dev.addblocker.adshield.ui.AdShieldApp
import dev.addblocker.adshield.ui.theme.AdShieldTheme
import dev.addblocker.adshield.vpn.AdBlockVpnService
import dev.addblocker.adshield.vpn.VpnStatus

class MainActivity : ComponentActivity() {

    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            AdBlockVpnService.start(this)
        } else {
            VpnStatus.setError("VPN permission is required to filter DNS traffic.")
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The tunnel runs either way; the notification is just hidden if denied. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Prefs.init(this)
        StatsRepository.init()
        RulesRepository.init(this)

        setContent {
            AdShieldTheme {
                AdShieldApp(
                    onStartProtection = ::startProtection,
                    onStopProtection = ::stopProtection,
                    onRequestNotificationPermission = ::requestNotificationPermission
                )
            }
        }
    }

    override fun onPause() {
        StatsRepository.flush()
        super.onPause()
    }

    private fun startProtection() {
        requestNotificationPermission()
        val consent = VpnService.prepare(this)
        if (consent != null) {
            vpnConsent.launch(consent)
        } else {
            AdBlockVpnService.start(this)
        }
    }

    private fun stopProtection() {
        AdBlockVpnService.stop(this)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
