package bd.callbridge

import android.app.Application
import bd.callbridge.call.CallController
import bd.callbridge.profile.ProfileSummarizer
import bd.callbridge.service.BridgeSessionManager
import bd.callbridge.store.CallBridgeDatabase
import bd.callbridge.store.RoomCallerRepository
import bd.callbridge.store.RoomProfileRepository

class CallBridgeApp : Application() {

    val database: CallBridgeDatabase by lazy { CallBridgeDatabase.getInstance(this) }

    val callerRepository by lazy { RoomCallerRepository(database) }

    val profileRepository by lazy { RoomProfileRepository(database) }
    /** M3 wiring: composes capture -> Gemini -> injector per call (spec §4.4). Constructed before
     *  [callController] since the latter takes it as its session coordinator; [attach]ed back to
     *  the controller right after, since the manager also needs to call [CallController.hangUp]. */
    val bridgeSessionManager: BridgeSessionManager by lazy {
        BridgeSessionManager(context = this, database = database, callerRepository = callerRepository)
    }

    val callController: CallController by lazy {
        CallController(
            context = this,
            callerRepository = callerRepository,
            sessionCoordinator = bridgeSessionManager,
        ).also { bridgeSessionManager.attach(it) }
    }

    /**
     * Patient profile summarizer (demo feature). See [ProfileSummarizer]'s doc comment for the
     * exact wiring call the M3 orchestration worker should add once a call's transcript is
     * finished.
     */
    val profileSummarizer: ProfileSummarizer by lazy {
        ProfileSummarizer(
            turnDao = database.turnDao(),
            callDao = database.callDao(),
            profileDao = database.patientProfileDao(),
            profileUpdateDao = database.profileUpdateDao(),
            apiKey = { Config.geminiApiKey },
        )
    }
}
