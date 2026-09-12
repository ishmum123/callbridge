package bd.callbridge.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import bd.callbridge.CallBridgeApp
import bd.callbridge.databinding.ActivityRegisterCallerBinding
import kotlinx.coroutines.launch

/**
 * Hidden shopkeeper screen (spec §4.5): register a caller's number, name, village, occupation
 * into the `callers` table so [bd.callbridge.call.CallStateMachine]'s policy answers them
 * immediately instead of routing through the reject+callback path.
 */
class RegisterCallerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterCallerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterCallerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSave.setOnClickListener {
            val number = binding.editNumber.text.toString().trim()
            if (number.isEmpty()) return@setOnClickListener

            val name = binding.editName.text.toString().trim().ifEmpty { null }
            val village = binding.editVillage.text.toString().trim().ifEmpty { null }
            val occupation = binding.editOccupation.text.toString().trim().ifEmpty { null }

            lifecycleScope.launch {
                (application as CallBridgeApp).callerRepository.register(
                    number = number,
                    name = name,
                    village = village,
                    occupation = occupation,
                )
                finish()
            }
        }
    }
}
