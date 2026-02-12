package com.hiennv.flutter_callkit_incoming

import android.content.Intent
import android.os.Build
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.M)
class CallkitConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: android.telecom.PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d("CallkitIncoming", "onCreateIncomingConnection called")

        val callUuid = request?.extras?.getString(InAppCallManager.EXTRA_CALL_UUID) ?: ""
        val callData = request?.extras?.getBundle(InAppCallManager.EXTRA_CALL_DATA)
        val isOutgoing =
            request?.extras?.getBoolean(InAppCallManager.EXTRA_CALL_IS_OUTGOING, false) ?: false
        
        Log.d("CallkitIncoming", "Creating connection for call UUID: $callUuid")

        val connection = CallkitConnection(applicationContext, callUuid, callData)
        
        // Enable hold capability so Android can coordinate with GSM calls
        connection.setConnectionCapabilities(
            Connection.CAPABILITY_HOLD or Connection.CAPABILITY_SUPPORT_HOLD or Connection.CAPABILITY_MUTE
        )
        connection.connectionProperties = connection.connectionProperties or Connection.PROPERTY_SELF_MANAGED
        connection.setAudioModeIsVoip(true)
        
        connection.setInitializing()
        if (isOutgoing) {
            connection.setDialing()
        } else {
            connection.setRinging()
        }
        connection.setInitialized()
        
        // Register connection so we can control it from Dart
        CallkitConnectionManager.addConnection(callUuid, connection)
        if (callUuid.isNotEmpty()) {
            CallkitTelecomRegistry.markRegistered(callUuid)
        }
        
        return connection
    }
}
