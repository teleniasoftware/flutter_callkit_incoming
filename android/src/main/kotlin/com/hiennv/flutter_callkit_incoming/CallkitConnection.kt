package com.hiennv.flutter_callkit_incoming

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.Connection
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.M)
class CallkitConnection(
    private val context: Context,
    private val callUuid: String = "",
    private val callData: Bundle? = null
) : Connection() {

    override fun onAnswer() {
        super.onAnswer()
        Log.d("CallkitIncoming", "onAnswer called - setting connection to ACTIVE (UUID: $callUuid)")

        // Set connection to active state and put other app calls on hold
        FlutterCallkitIncomingPlugin.activateAndHoldOthers(callUuid)
        if (callData != null) {
            val acceptBroadcast = CallkitIncomingBroadcastReceiver.getIntentAccept(context, callData)
            acceptBroadcast.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(acceptBroadcast)

            val appIntent = AppUtils.getAppIntent(context, CallkitConstants.ACTION_CALL_ACCEPT, callData)
            if (appIntent != null) {
                appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(appIntent)
            } else {
                Log.w("CallkitIncoming", "No launch intent for app; cannot open UI (UUID: $callUuid)")
            }
        } else {
            Log.w("CallkitIncoming", "Call data missing on answer; cannot open UI (UUID: $callUuid)")
        }

        val intent = Intent("com.hiennv.flutter_callkit_incoming.ACTION_ANSWER_CALL")
        intent.putExtra("callUUID", callUuid)
        context.sendBroadcast(intent)
    }
    
    override fun onHold() {
        super.onHold()
        Log.d("CallkitIncoming", "onHold called - Android wants us to hold (UUID: $callUuid)")
        
        setOnHold()
        FlutterCallkitIncomingPlugin.propagateHoldState(callUuid, true)
        
        // Notify Flutter to put call on hold (mute audio, pause video, etc.)
        val intent = Intent("com.hiennv.flutter_callkit_incoming.ACTION_HOLD_CALL")
        intent.putExtra("callUUID", callUuid)
        context.sendBroadcast(intent)
    }
    
    override fun onUnhold() {
        super.onUnhold()
        Log.d("CallkitIncoming", "onUnhold called - Android wants us to unhold (UUID: $callUuid)")
        
        FlutterCallkitIncomingPlugin.activateAndHoldOthers(callUuid)
        FlutterCallkitIncomingPlugin.propagateHoldState(callUuid, false)
        
        // Notify Flutter to resume call (unmute audio, resume video, etc.)
        val intent = Intent("com.hiennv.flutter_callkit_incoming.ACTION_UNHOLD_CALL")
        intent.putExtra("callUUID", callUuid)
        context.sendBroadcast(intent)
    }

    override fun onDisconnect() {
        super.onDisconnect()
        Log.d("CallkitIncoming", "onDisconnect called - sending broadcast to decline call (UUID: $callUuid)")

        val intent = Intent("com.hiennv.flutter_callkit_incoming.ACTION_DECLINE_CALL")
        intent.putExtra("callUUID", callUuid)
        context.sendBroadcast(intent)
        
        // Ensure connection is properly terminated
        setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.LOCAL))
        destroy()
        
        // Remove from connection manager
        CallkitConnectionManager.removeConnection(callUuid)
    }
}
