package bd.callbridge.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import bd.callbridge.CallBridgeApp
import bd.callbridge.databinding.ActivityPatientsListBinding
import bd.callbridge.databinding.ItemPatientCardBinding
import bd.callbridge.store.PatientProfileEntity
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Patient profile list (demo "Patient profile" feature, not in the original spec). Reachable from
 * [StatusActivity]'s "Patients" button. Shows every caller with a profile: name/number, last call
 * time, risk-flag chips, and a follow-up badge; tapping a card opens [PatientDetailActivity].
 *
 * Populated by [bd.callbridge.profile.ProfileSummarizer] after each call finishes (or via the
 * debug `DEBUG_SEED_CALL` + `DEBUG_SUMMARIZE` broadcasts for a demo with no live call).
 */
class PatientsListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPatientsListBinding
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPatientsListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as CallBridgeApp
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.profileRepository.observeAll().collect { profiles -> render(profiles) }
            }
        }
    }

    private fun render(profiles: List<PatientProfileEntity>) {
        binding.textEmpty.visibility = if (profiles.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.listContainer.removeAllViews()
        for (profile in profiles) {
            val itemBinding = ItemPatientCardBinding.inflate(LayoutInflater.from(this), binding.listContainer, false)
            bindCard(itemBinding, profile)
            binding.listContainer.addView(itemBinding.root)
        }
    }

    private fun bindCard(itemBinding: ItemPatientCardBinding, profile: PatientProfileEntity) {
        val name = profile.displayName?.takeIf { it.isNotBlank() } ?: profile.number
        itemBinding.textName.text = "$name (${profile.number})"
        itemBinding.textLastCall.text = "Last call: ${formatTime(profile.lastUpdated)}  •  ${profile.callCount} call(s)" +
            (profile.lastError?.let { "  •  last update failed" } ?: "")
        itemBinding.textFollowUpBadge.visibility =
            if (profile.followUpNeeded) android.view.View.VISIBLE else android.view.View.GONE

        itemBinding.chipGroupRiskFlags.removeAllViews()
        for (flag in profile.riskFlags) {
            val chip = Chip(itemBinding.root.context).apply {
                text = flag
                isClickable = false
                isCheckable = false
            }
            itemBinding.chipGroupRiskFlags.addView(chip)
        }

        itemBinding.root.setOnClickListener {
            val ctx: Context = itemBinding.root.context
            ctx.startActivity(
                Intent(ctx, PatientDetailActivity::class.java)
                    .putExtra(PatientDetailActivity.EXTRA_NUMBER, profile.number)
            )
        }
    }

    private fun formatTime(epochMs: Long): String =
        if (epochMs <= 0L) "-" else dateFormat.format(Date(epochMs))
}
