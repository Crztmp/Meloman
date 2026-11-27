package com.lymsoft.meloman

import android.util.Log
import com.google.gson.annotations.SerializedName
import java.util.UUID

data class Playlist(
    @SerializedName("id")
    val id: String = UUID.randomUUID().toString(),

    @SerializedName("name")
    var name: String,

    @SerializedName("lastModified")
    var lastModified: Long = System.currentTimeMillis(),

    @SerializedName("lastUsed")
    var lastUsed: Long = System.currentTimeMillis(),

    @SerializedName("settings")
    var settings: PlaylistSettings = PlaylistSettings(),
) {
    // Переменная для включения/выключения отладочного логирования
    companion object {
        private val debugLog: Boolean get() = DebugConfig.isEnabled
    }

    // Вспомогательные методы для удобства
    fun updateLastModified() {
        if (debugLog) Log.d("[CCM]", "[Playlist.updateLastModified] In")
        lastModified = System.currentTimeMillis()
        if (debugLog) Log.d("[CCM]", "[Playlist.updateLastModified] lastModified обновлен на: $lastModified")
    }

    fun updateLastUsed() {
        if (debugLog) Log.d("[CCM]", "[Playlist.updateLastUsed] In")
        lastUsed = System.currentTimeMillis()
        if (debugLog) Log.d("[CCM]", "[Playlist.updateLastUsed] lastUsed обновлен на: $lastUsed")
    }

//    fun updateLastTrack(trackPath: String, position: Long) {
//        if (debugLog) Log.d("[CCM]", "[Playlist.updateLastTrack] In, trackPath=$trackPath, position=$position")
//        lastTrackPath = trackPath
//        lastTrackPosition = position
//        if (debugLog) Log.d("[CCM]", "[Playlist.updateLastTrack] lastTrackPath и lastTrackPosition обновлены")
//        updateLastModified()
//    }

    fun getFormattedLastModified(): String {
        if (debugLog) Log.d("[CCM]", "[Playlist.getFormattedLastModified] In")
        return android.text.format.DateFormat.format("yyyy.MM.dd", lastModified).toString()
    }

    fun getFormattedLastUsed(): String {
        if (debugLog) Log.d("[CCM]", "[Playlist.getFormattedLastUsed] In")
        return android.text.format.DateFormat.format("yyyy.MM.dd", lastUsed).toString()

    }
}

data class PlaylistSettings(
    @SerializedName("musicDirectoryPath")
    var musicDirectoryPath: String = "/storage/emulated/0/Music",

    @SerializedName("musicDirectoryUri")
    var musicDirectoryUri: String? = null,

    @SerializedName("randomMode")
    var randomMode: String = "none", // none, in_folder, everywhere

    @SerializedName("blacklistItems")
    var blacklistItems: MutableList<BlacklistItem> = mutableListOf(),

    @SerializedName("currentTrackPath")
    var currentTrackPath: String = "",

    @SerializedName("currentPosition")
    var currentPosition: Long = 0,

) {
    // Переменная для включения/выключения отладочного логирования
    companion object {
        private val debugLog: Boolean
            get() = DebugConfig.isEnabled
    }
}