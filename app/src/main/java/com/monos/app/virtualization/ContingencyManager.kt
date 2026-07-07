package com.monos.app.virtualization

import android.content.Context
import android.content.SharedPreferences

class ContingencyManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "monos_contingency_prefs",
        Context.MODE_PRIVATE
    )

    companion object {
        const val KEY_PPK_STRATEGY = "ppk_strategy"
        const val KEY_DISPLAY_STRATEGY = "display_strategy"
        const val KEY_KEYBOARD_STRATEGY = "keyboard_strategy"
        const val KEY_AUTOMATION_STRATEGY = "automation_strategy"

        // Strategy Constants
        const val STRATEGY_PPK_ADB = "ADB_CLIENT"
        const val STRATEGY_PPK_SCRIPT = "SCRIPT_EXPORT"
        const val STRATEGY_PPK_WAKELOCK = "WAKELOCK"

        const val STRATEGY_DISPLAY_NDK = "NDK_X11"
        const val STRATEGY_DISPLAY_VNC = "VNC"

        const val STRATEGY_KEYBOARD_BLOCK = "FOCUS_BLOCK"
        const val STRATEGY_KEYBOARD_IM_FLAG = "ALT_FOCUS_IM"

        const val STRATEGY_AUTO_SOCKET = "TELEMETRY_SOCKET"
        const val STRATEGY_AUTO_MANUAL = "MANUAL_TOGGLE"
    }

    fun getPpkStrategy(): String =
        prefs.getString(KEY_PPK_STRATEGY, STRATEGY_PPK_ADB) ?: STRATEGY_PPK_ADB

    fun setPpkStrategy(strategy: String) =
        prefs.edit().putString(KEY_PPK_STRATEGY, strategy).apply()

    fun getDisplayStrategy(): String =
        prefs.getString(KEY_DISPLAY_STRATEGY, STRATEGY_DISPLAY_NDK) ?: STRATEGY_DISPLAY_NDK

    fun setDisplayStrategy(strategy: String) =
        prefs.edit().putString(KEY_DISPLAY_STRATEGY, strategy).apply()

    fun getKeyboardStrategy(): String =
        prefs.getString(KEY_KEYBOARD_STRATEGY, STRATEGY_KEYBOARD_BLOCK) ?: STRATEGY_KEYBOARD_BLOCK

    fun setKeyboardStrategy(strategy: String) =
        prefs.edit().putString(KEY_KEYBOARD_STRATEGY, strategy).apply()

    fun getAutomationStrategy(): String =
        prefs.getString(KEY_AUTOMATION_STRATEGY, STRATEGY_AUTO_SOCKET) ?: STRATEGY_AUTO_SOCKET

    fun setAutomationStrategy(strategy: String) =
        prefs.edit().putString(KEY_AUTOMATION_STRATEGY, strategy).apply()
}
