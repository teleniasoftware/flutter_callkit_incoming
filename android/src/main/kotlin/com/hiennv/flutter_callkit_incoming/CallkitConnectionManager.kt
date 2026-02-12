package com.hiennv.flutter_callkit_incoming

import android.os.Build
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.M)
object CallkitConnectionManager {
    private val connections = mutableMapOf<String, MutableList<CallkitConnection>>()
    
    fun addConnection(uuid: String, connection: CallkitConnection) {
        android.util.Log.d("CallkitIncoming", "ConnectionManager: Adding connection $uuid")
        val list = connections.getOrPut(uuid) { mutableListOf() }
        list.add(connection)
    }
    
    fun getAllConnections(): Map<String, List<CallkitConnection>> =
        connections.mapValues { it.value.toList() }
    
    fun getConnection(uuid: String): CallkitConnection? {
        return connections[uuid]?.lastOrNull()
    }
    
    fun removeConnection(uuid: String) {
        android.util.Log.d("CallkitIncoming", "ConnectionManager: Removing connection $uuid")
        connections.remove(uuid)
    }

    fun removeConnection(uuid: String, connection: CallkitConnection) {
        val list = connections[uuid] ?: return
        list.remove(connection)
        if (list.isEmpty()) {
            connections.remove(uuid)
        }
    }

    fun getConnections(uuid: String): List<CallkitConnection> {
        return connections[uuid]?.toList() ?: emptyList()
    }
    
    fun setConnectionActive(uuid: String) {
        android.util.Log.d("CallkitIncoming", "ConnectionManager: Setting connection $uuid to ACTIVE")
        connections[uuid]?.lastOrNull()?.setActive()
    }
    
    fun setConnectionOnHold(uuid: String) {
        android.util.Log.d("CallkitIncoming", "ConnectionManager: Setting connection $uuid to ON_HOLD")
        connections[uuid]?.lastOrNull()?.setOnHold()
    }
    
    fun setActiveExclusive(uuid: String) {
        connections.forEach { (id, connection) ->
            if (id == uuid) {
                connection.forEach { conn ->
                    if (conn.state != android.telecom.Connection.STATE_ACTIVE) {
                        android.util.Log.d("CallkitIncoming", "ConnectionManager: Activating $uuid")
                        conn.setActive()
                    }
                }
            } else {
                connection.forEach { conn ->
                    if (conn.state != android.telecom.Connection.STATE_HOLDING) {
                        android.util.Log.d("CallkitIncoming", "ConnectionManager: Holding $id because $uuid became active")
                        conn.setOnHold()
                    }
                }
            }
        }
    }
}
