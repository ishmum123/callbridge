package bd.callbridge

import android.app.Application
import bd.callbridge.call.CallController
import bd.callbridge.store.CallBridgeDatabase
import bd.callbridge.store.RoomCallerRepository

class CallBridgeApp : Application() {

    val database: CallBridgeDatabase by lazy { CallBridgeDatabase.getInstance(this) }

    val callerRepository by lazy { RoomCallerRepository(database) }

    val callController: CallController by lazy {
        CallController(context = this, callerRepository = callerRepository)
    }
}
