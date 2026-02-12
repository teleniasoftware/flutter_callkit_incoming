package com.hiennv.flutter_callkit_incoming

import java.util.concurrent.ConcurrentHashMap

object CallkitTelecomRegistry {
    private val registering = ConcurrentHashMap.newKeySet<String>()
    private val registered = ConcurrentHashMap.newKeySet<String>()

    fun isRegisteringOrRegistered(uuid: String): Boolean {
        return registering.contains(uuid) || registered.contains(uuid)
    }

    fun markRegistering(uuid: String) {
        registering.add(uuid)
    }

    fun markRegistered(uuid: String) {
        registering.remove(uuid)
        registered.add(uuid)
    }

    fun clear(uuid: String) {
        registering.remove(uuid)
        registered.remove(uuid)
    }
}
