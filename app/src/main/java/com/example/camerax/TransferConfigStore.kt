package com.example.camerax

import android.content.Context

class TransferConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPcIp(): String = prefs.getString(KEY_PC_IP, "") ?: ""
    fun getPcPort(): Int = prefs.getInt(KEY_PC_PORT, 8080)

    fun setPcIp(ip: String) { prefs.edit().putString(KEY_PC_IP, ip.trim()).apply() }
    fun setPcPort(port: Int) { prefs.edit().putInt(KEY_PC_PORT, port).apply() }

    companion object {
        private const val PREFS_NAME = "phase2_transfer_prefs"
        private const val KEY_PC_IP = "pc_ip"
        private const val KEY_PC_PORT = "pc_port"
    }
}
