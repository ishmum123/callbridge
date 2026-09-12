package bd.callbridge.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import bd.callbridge.CallBridgeApp
import bd.callbridge.audio.NativeBridge
import bd.callbridge.Config
import bd.callbridge.databinding.ActivityStatusBinding
import bd.callbridge.service.BridgeForegroundService
import kotlinx.coroutines.launch

/**
 * Status screen (spec §4.6): state, caller number, socket status, injector route, calls today,
 * est. cost today, plus hang up / test injection / register caller buttons.
 *
 * M0 wires the layout, permission/role requests, and static fields. Live state (current call,
 * socket status, calls-today/cost-today counters) is TODO for M3 once [bd.callbridge.call.CallController]
 * exposes a Flow of state and [bd.callbridge.store.CallBridgeDatabase] has real call rows.
 */
class StatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatusBinding

    private val runtimePermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* TODO: surface denials on the status screen */ }

    private val requestRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { /* TODO: react to default-dialer grant/denial */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        renderStaticStatus()
        requestDefaultDialerRoleIfNeeded()
        requestMissingRuntimePermissions()
        BridgeForegroundService.start(this)
        observeBridgeStatus()

        binding.btnHangup.setOnClickListener {
            (application as CallBridgeApp).callController.hangUp()
        }
        binding.btnTestInjection.setOnClickListener {
            // TODO(M1b): route a 1 kHz test tone through the active Injector.
        }
        binding.btnRegisterCaller.setOnClickListener {
            startActivity(Intent(this, RegisterCallerActivity::class.java))
        }
    }

    private fun renderStaticStatus() {
        val app = application as CallBridgeApp
        binding.textState.text = "State: ${app.callController.state}"
        binding.textCaller.text = "Caller: -"
        binding.textSocket.text = "Socket: not connected"
        binding.textRoute.text = "Injector route: ${Config.injectorRoute} (native: ${runCatching { NativeBridge.nativeVersion() }.getOrDefault("n/a")})"
        binding.textCallsToday.text = "Calls today: 0"
        binding.textCostToday.text = "Est. cost today: $0.00"
    }

    /** M3: live socket/route/caller status from [bd.callbridge.service.BridgeSessionManager],
     *  replacing the static placeholders set in [renderStaticStatus]. Minimal by design (brief:
     *  "a StateFlow on the app singleton is fine") — calls-today/cost-today stay TODO. */
    private fun observeBridgeStatus() {
        val app = application as CallBridgeApp
        lifecycleScope.launch {
            app.bridgeSessionManager.status.collect { status ->
                binding.textCaller.text = "Caller: ${status.callerNumber ?: "-"}"
                binding.textSocket.text = "Socket: ${if (status.socketOpen) "open" else "not connected"} (${status.phase})"
                binding.textRoute.text = "Injector route: ${status.injectorRoute ?: Config.injectorRoute} (native: ${runCatching { NativeBridge.nativeVersion() }.getOrDefault("n/a")})"
                status.lastTranscriptLine?.let { binding.textState.text = "State: ${app.callController.state} — $it" }
            }
        }
    }

    private fun requestDefaultDialerRoleIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                requestRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
            }
        }
    }

    private fun requestMissingRuntimePermissions() {
        val missing = runtimePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }
}
