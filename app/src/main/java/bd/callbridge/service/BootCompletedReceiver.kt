package bd.callbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts [BridgeForegroundService] at boot (spec §5: "runs unattended ... for days"). */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            BridgeForegroundService.start(context)
        }
    }
}
