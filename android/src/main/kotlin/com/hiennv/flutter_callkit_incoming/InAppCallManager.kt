package com.hiennv.flutter_callkit_incoming

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.M)
class InAppCallManager(private val context: Context) {

    companion object {
        private const val ACCOUNT_ID = "flutter_callkit_incoming_in_app_call_account"
        private const val TAG = "InAppCallManager"
        const val EXTRA_CALL_UUID = "com.hiennv.flutter_callkit_incoming.CALL_UUID"
        const val EXTRA_CALL_DATA = "com.hiennv.flutter_callkit_incoming.CALL_DATA"
        
        @RequiresApi(Build.VERSION_CODES.M)
        fun registerIncomingCall(context: Context, data: Bundle) {
            try {
                Log.d(TAG, "registerIncomingCall called")
                
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    Log.d(TAG, "Returning early: SDK < M")
                    return
                }
                
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                val componentName = ComponentName(context, CallkitConnectionService::class.java)
                val phoneAccountHandle = PhoneAccountHandle(componentName, ACCOUNT_ID)
                
                // Use CallkitConstants to get the correct keys
                val callUuid = data.getString(CallkitConstants.EXTRA_CALLKIT_ID)
                if (callUuid == null || callUuid.isEmpty()) {
                    Log.e(TAG, "Returning early: callUuid is null or empty!")
                    return
                }
                
                val nameCaller = data.getString(CallkitConstants.EXTRA_CALLKIT_NAME_CALLER) ?: "Unknown"
                val number = data.getString(CallkitConstants.EXTRA_CALLKIT_HANDLE) ?: ""
                
                Log.d(TAG, "Call data: UUID=$callUuid, Caller=$nameCaller, Number=$number")
                
                // Create extras bundle with call info
                val extras = Bundle().apply {
                    putString(EXTRA_CALL_UUID, callUuid)
                    putBundle(EXTRA_CALL_DATA, data)
                    putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.parse("tel:$number"))
                }
                
                Log.d(TAG, "Registering incoming call with TelecomManager: UUID=$callUuid, Caller=$nameCaller")
                
                // Register the incoming call with Android's telecom framework
                telecomManager.addNewIncomingCall(phoneAccountHandle, extras)
                
                Log.d(TAG, "Successfully registered incoming call with TelecomManager")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register incoming call with TelecomManager", e)
            }
        }
    }

    fun registerPhoneAccount() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val componentName = ComponentName(context, CallkitConnectionService::class.java)
        val handle = PhoneAccountHandle(componentName, ACCOUNT_ID)

        val phoneAccount = PhoneAccount.builder(handle, "Callkit Incoming In-App Call")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()

        telecomManager.registerPhoneAccount(phoneAccount)
        Log.d(TAG, "PhoneAccount registered.")
    }

    fun unregisterPhoneAccount() {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val componentName = ComponentName(context, CallkitConnectionService::class.java)
        val handle = PhoneAccountHandle(componentName, ACCOUNT_ID)

        telecomManager.unregisterPhoneAccount(handle)
        Log.d(TAG, "PhoneAccount unregistered.")
    }

    fun getPhoneAccountHandle(): PhoneAccountHandle {
        return PhoneAccountHandle(
            ComponentName(context, CallkitConnectionService::class.java),
            ACCOUNT_ID
        )
    }
}
