package com.lymsoft.meloman

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

object DebugConfig {
    private const val KEY = "debug_log"

    @Volatile
    var isEnabled: Boolean = false
        private set

    fun init(context: Context) {
        isEnabled = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        isEnabled = enabled
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putBoolean(KEY, enabled)
        }
    }
}