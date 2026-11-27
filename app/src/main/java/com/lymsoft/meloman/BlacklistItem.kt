package com.lymsoft.meloman

import android.util.Log

data class BlacklistItem(
    val path: String,
    val type: ItemType,
    val name: String

) {
    enum class ItemType {
        FILE, DIRECTORY

    }

    private val debugLog: Boolean
        get() = DebugConfig.isEnabled
    fun getDisplayName(): String {
        if (debugLog) Log.d("[CCM]", "[BlacklistItem.getDisplayName] In, type=$type, name=$name")
        val result = when (type) {
            ItemType.FILE -> "📄 $name"
            ItemType.DIRECTORY -> "📁 $name"
        }
        if (debugLog) Log.d("[CCM]", "[BlacklistItem.getDisplayName] Result: $result")
        return result
    }

    companion object {
        private val debugLog: Boolean
            get() = DebugConfig.isEnabled
    }
}