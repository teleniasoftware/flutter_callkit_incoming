package com.hiennv.flutter_callkit_incoming

import android.os.Build
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.M)
object CallkitConnectionManager {
    private val connections = mutableMapOf<String, CallkitConnection>()
    
    fun addConnection(uuid: String, connection: CallkitConnection) {
        android.util.Log.d("CallkitIncoming", "ConnectionManager: Adding connection $uuid")
        connections[uuid] = connection
    }
    
    fun getAllConnections(): Map<String, CallkitConnection> = connections.toMap()
    
    fun getConnection(uuid: String): CallkitConnection? {
        return connections[uuid]
    }
    
    fun removeConnection(uuid: String) {
        android.util.Log.d("CallkitIncoming", "ConnectionManager: Removing connection $uuid")
        connections.remove(uuid)
    }
    
    fun setConnectionActive(uuid: String) {
        android.util.Log.d("CallkitIncoming", "ConnectionManager: Setting connection $uuid to ACTIVE")
        connections[uuid]?.setActive()
    }
    
    fun setConnectionOnHold(uuid: String) {
        android.util.Log.d("CallkitIncoming", "ConnectionManager: Setting connection $uuid to ON_HOLD")
        connections[uuid]?.setOnHold()
    }
    
    fun setActiveExclusive(uuid: String) {
        connections.forEach { (id, connection) ->
            if (id == uuid) {
                if (connection.state != android.telecom.Connection.STATE_ACTIVE) {
                    android.util.Log.d("CallkitIncoming", "ConnectionManager: Activating $uuid")
                    connection.setActive()
                }
            } else {
                if (connection.state != android.telecom.Connection.STATE_HOLDING) {
                    android.util.Log.d("CallkitIncoming", "ConnectionManager: Holding $id because $uuid became active")
                    connection.setOnHold()
                }
            }
        }
    }
}
