package bd.callbridge.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import bd.callbridge.CallBridgeApp
import bd.callbridge.databinding.ActivityPatientDetailBinding
import bd.callbridge.store.PatientProfileEntity
import bd.callbridge.store.ProfileUpdateEntity
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Patient profile detail screen: all fields of one [PatientProfileEntity] grouped into sections,
 * plus [ProfileUpdateEntity] call history (what changed each call). Opened from
 * [PatientsListActivity] with [EXTRA_NUMBER] set to the caller's phone number.
 */
class PatientDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPatientDetailBinding
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPatientDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val number = intent.getStringExtra(EXTRA_NUMBER)
        if (number.isNullOrBlank()) {
            finish()
            return
        }

        val app = application as CallBridgeApp
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.profileRepository.observe(number).collect { it?.let(::renderProfile) } }
                launch { app.profileRepository.observeUpdates(number).collect(::renderHistory) }
            }
        }
    }

    private fun renderProfile(profile: PatientProfileEntity) {
        val name = profile.displayName?.takeIf { it.isNotBlank() } ?: profile.number
        binding.textHeaderName.text = name

        val metaParts = mutableListOf(profile.number)
        profile.ageYears?.let { metaParts.add("age $it") }
        profile.sex?.let { metaParts.add(it) }
        profile.village?.let { metaParts.add(it) }
        metaParts.add("${profile.callCount} call(s)")
        binding.textHeaderMeta.text = metaParts.joinToString("  •  ")

        binding.textFollowUpBadge.visibility = if (profile.followUpNeeded) View.VISIBLE else View.GONE

        binding.textSummaryBn.text = profile.summaryBn.ifBlank { "-" }
        binding.textSummaryEn.text = profile.summaryEn.ifBlank { "-" }
        if (profile.lastError != null) {
            binding.textSummaryEn.text = "${binding.textSummaryEn.text}\n\n[last update failed: ${profile.lastError}]"
        }

        binding.textChronicConditions.text = "Chronic conditions: " + listOrNone(profile.chronicConditions)
        binding.textCurrentSymptoms.text = "Current symptoms: " + listOrNone(profile.currentSymptoms)
        binding.textMedications.text = "Medications: " + listOrNone(profile.medications)
        binding.textAllergies.text = "Allergies: " + listOrNone(profile.allergies)
        binding.textAdviceGiven.text = "Advice given: " + listOrNone(profile.adviceGiven)
        binding.textFollowUpNote.text = "Follow-up note: " + (profile.followUpNote?.takeIf { it.isNotBlank() } ?: "-")

        binding.chipGroupRiskFlags.removeAllViews()
        for (flag in profile.riskFlags) {
            binding.chipGroupRiskFlags.addView(Chip(this).apply { text = flag })
        }
    }

    private fun renderHistory(updates: List<ProfileUpdateEntity>) {
        binding.callHistoryContainer.removeAllViews()
        if (updates.isEmpty()) {
            binding.callHistoryContainer.addView(bodyText("No calls recorded yet."))
            return
        }
        for (update in updates) {
            binding.callHistoryContainer.addView(
                bodyText("${dateFormat.format(Date(update.timestamp))} — ${update.deltaSummary}")
            )
        }
    }

    private fun bodyText(text: String): TextView =
        (LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_1, binding.callHistoryContainer, false) as TextView)
            .apply { this.text = text; setPadding(0, 8, 0, 8) }

    private fun listOrNone(items: List<String>): String = items.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "-"

    companion object {
        const val EXTRA_NUMBER = "number"
    }
}
