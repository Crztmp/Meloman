package com.lymsoft.meloman

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import androidx.core.content.edit

class PlaylistManager private constructor(
    private val prefs: SharedPreferences
) {
    private val debugLog: Boolean
        get() = DebugConfig.isEnabled

    companion object {
        private const val PREFS_NAME = "playlists_prefs"
        private const val KEY_PLAYLISTS = "playlists"
        private const val KEY_CURRENT_PLAYLIST_ID = "current_playlist_id"
        private var cachedCurrentPlaylist: Playlist? = null

        // Переменная для включения/выключения отладочного логирования в companion object
        private val debugLog: Boolean
            get() = DebugConfig.isEnabled

        @Volatile
        private var instance: PlaylistManager? = null

        fun getInstance(context: Context): PlaylistManager {
            if (debugLog) Log.d("[CCM]", "[PlaylistManager.getInstance] In")
            return instance ?: synchronized(this) {
                instance ?: PlaylistManager(
                    context.applicationContext.getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
                ).also { instance = it }
            }

        }}

//    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
        .create()

    private var playlists: MutableList<Playlist> = mutableListOf()
    private var currentPlaylistId: String = ""

    init {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.init] Вход в init блок")
        loadPlaylists()
        ensureDefaultPlaylist()
    }

    /**
     * Загружает плейлисты из SharedPreferences
     */
    private fun loadPlaylists() {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.loadPlaylists] In")
        val json = prefs.getString(KEY_PLAYLISTS, null)
        if (!json.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<MutableList<Playlist>>() {}.type
                playlists = gson.fromJson(json, type)
                if (debugLog) Log.d("[CCM]", "[PlaylistManager.loadPlaylists] Loaded ${playlists.size} playlists")
            } catch (e: Exception) {
                if (debugLog) Log.d("[CCM]", "[PlaylistManager.loadPlaylists] Error loading playlists", e)
                playlists = mutableListOf()
            }
        }

        currentPlaylistId = prefs.getString(KEY_CURRENT_PLAYLIST_ID, "") ?: ""
    }

    /**
     * Сохраняет плейлисты в SharedPreferences
     */
    fun savePlaylists() {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.savePlaylists] In")
        val json = gson.toJson(playlists)
        prefs.edit { putString(KEY_PLAYLISTS, json) }
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.savePlaylists] Saved ${playlists.size} playlists")
    }
    /**
     * Обновляет каталог музыки для указанного плейлиста
     */
    fun updatePlaylistMusicDirectory(playlistId: String, musicDirectoryPath: String, musicDirectoryUri: String?) {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.updatePlaylistMusicDirectory] In, playlistId=$playlistId, musicDirectoryPath=$musicDirectoryPath")
        val playlist = getPlaylist(playlistId)
        if (playlist != null) {
            playlist.settings.musicDirectoryPath = musicDirectoryPath
            playlist.settings.musicDirectoryUri = musicDirectoryUri
            playlist.updateLastModified()
            savePlaylists()
            if (debugLog) Log.d("[CCM]", "[PlaylistManager.updatePlaylistMusicDirectory] Updated music directory for playlist '${playlist.name}'")
        }
    }
    /**
     * Сохраняет текущий ID плейлиста
     */
    private fun saveCurrentPlaylistId() {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.saveCurrentPlaylistId] In")
        prefs.edit { putString(KEY_CURRENT_PLAYLIST_ID, currentPlaylistId) }
    }

    /**
     * Создает плейлист по умолчанию, если нет ни одного
     */
    private fun ensureDefaultPlaylist() {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.ensureDefaultPlaylist] In")
        if (playlists.isEmpty()) {
            createPlaylist("Plist 1")
            if (debugLog) Log.d("[CCM]", "[PlaylistManager.ensureDefaultPlaylist] Created default playlist")
        }

        // Если текущий плейлист не установлен или не существует, устанавливаем первый
        if (currentPlaylistId.isEmpty() || playlists.none { it.id == currentPlaylistId }) {
            if (playlists.isNotEmpty()) {
                currentPlaylistId = playlists[0].id
                saveCurrentPlaylistId()
                if (debugLog) Log.d("[CCM]", "[PlaylistManager.ensureDefaultPlaylist] Set current playlist to: ${playlists[0].name}")
            }
        }
    }

    /**
     * Создает новый плейлист
     * @param name Имя плейлиста
     * @return Созданный плейлист или null если имя уже существует
     */
    fun createPlaylist(name: String): Playlist? {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.createPlaylist] In, name=$name")
        // Проверяем уникальность имени
        if (playlists.any { it.name.equals(name, ignoreCase = true) }) {
            if (debugLog) Log.d("[CCM]", "[PlaylistManager.createPlaylist] Playlist with name '$name' already exists")
            return null
        }

        val playlist = Playlist(name = name)
        playlists.add(playlist)
        savePlaylists()
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.createPlaylist] Created playlist: ${playlist.name} (${playlist.id})")
        return playlist
    }

    /**
     * Создает плейлист с автоматическим именем (Plist X)
     */
    fun createAutoNamedPlaylist(): Playlist {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.createAutoNamedPlaylist] In")
        var counter = 1
        var name: String
        do {
            name = "Plist $counter"
            counter++
        } while (playlists.any { it.name.equals(name, ignoreCase = true) })

        return createPlaylist(name) ?: createAutoNamedPlaylist()
    }

    /**
     * Удаляет плейлист
     * @param playlistId ID плейлиста для удаления
     * @return true если удаление успешно
     */
    fun deletePlaylist(playlistId: String): Boolean {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.deletePlaylist] In")
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.deletePlaylist] In, playlistId=$playlistId")
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index == -1) return false

        // Не даем удалить последний плейлист
        if (playlists.size <= 1) {
            if (debugLog) Log.d("[CCM]", "[PlaylistManager.deletePlaylist] Cannot delete the last playlist")
            return false
        }

        playlists.removeAt(index)

        // Если удалили текущий плейлист, переключаем на первый
        if (currentPlaylistId == playlistId && playlists.isNotEmpty()) {
            currentPlaylistId = playlists[0].id
            saveCurrentPlaylistId()
            if (debugLog) Log.d("[CCM]", "[PlaylistManager.deletePlaylist] Switched to playlist: ${playlists[0].name}")
        }

        savePlaylists()
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.deletePlaylist] Deleted playlist with id: $playlistId")
        return true
    }

    /**
     * Переименовывает плейлист
     * @param playlistId ID плейлиста
     * @param newName Новое имя
     * @return true если переименование успешно
     */
    fun renamePlaylist(playlistId: String, newName: String): Boolean {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.renamePlaylist] In")
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.renamePlaylist] In, playlistId=$playlistId, newName=$newName")
        val playlist = getPlaylist(playlistId) ?: return false

        // Проверяем уникальность имени
        if (playlists.any { it.id != playlistId && it.name.equals(newName, ignoreCase = true) }) {
            if (debugLog) Log.d("[CCM]", "[PlaylistManager.renamePlaylist] Playlist with name '$newName' already exists")
            return false
        }

        playlist.name = newName
        playlist.updateLastModified()
        savePlaylists()
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.renamePlaylist] Renamed playlist to: $newName")
        return true
    }

    /**
     * Получает плейлист по ID
     */
    fun getPlaylist(playlistId: String): Playlist? {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.getPlaylist] In")
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.getPlaylist] In, playlistId=$playlistId")
//        if (debugLog) Log.d("[CCM]", "[PlaylistManager.getPlaylist] getPlaylist: looking for id=$playlistId")
        val result = playlists.find { it.id == playlistId }
        if (result != null) {
            if (debugLog) Log.d("[CCM]", "[PlaylistManager.getPlaylist] getPlaylist: found playlist '${result.name}' (id=${result.id})")
        } else {
            if (debugLog) Log.d("[CCM]", "[PlaylistManager.getPlaylist] getPlaylist: playlist not found, available ids: ${playlists.map { it.id }}")
        }
        return result
    }

    /**
     * Получает все плейлисты
     */
    fun getAllPlaylists(): List<Playlist> {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.getAllPlaylists] In")
        return playlists.toList()
    }

    /**
     * Получает текущий плейлист
     */
    fun getCurrentPlaylist(): Playlist? {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.getCurrentPlaylist] In")
        val cached = cachedCurrentPlaylist
        if (cached != null && cached.id == currentPlaylistId) return cached
        val found = getPlaylist(currentPlaylistId)
        cachedCurrentPlaylist = found
        return found
    }

    /**
     * Получает последний трек и позицию из плейлиста
     */
    fun getLastTrackFromPlaylist(playlistId: String): Pair<String, Long> {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.getLastTrackFromPlaylist] In, playlistId=$playlistId")
        return try {
            val playlist = getPlaylist(playlistId)
            if (playlist != null) {
                val trackPath = playlist.settings.currentTrackPath
                val position = playlist.settings.currentPosition
                if (debugLog) Log.d("[CCM]", "[PlaylistManager.getLastTrackFromPlaylist]trackPath = $trackPath, position = $position")
                Pair(trackPath, position)

            } else Pair("", 0L)
        } catch (e: Exception) {
            if (debugLog) Log.d("[CCM]", "[PlaylistManager.getLastTrackFromPlaylist] Error getting last track from playlist", e)
            Pair("", 0L)
        }
    }

//      Переключается на другой плейлист с сохранением состояния
    fun switchToPlaylist(playlistId: String, currentTrackPath: String, currentPosition: Long, updateLastUsed: Boolean = true): Boolean {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.switchToPlaylist] In, playlistId=$playlistId")
        return try {
            val newPlaylist = getPlaylist(playlistId) ?: return false
            val oldPlaylist = getCurrentPlaylist()

            if (oldPlaylist?.id == playlistId) return true

            // Сохраняем последний трек в старый плейлист
            if (oldPlaylist != null && currentTrackPath.isNotEmpty()) {
                oldPlaylist.settings.currentTrackPath = currentTrackPath
                oldPlaylist.settings.currentPosition = currentPosition
                oldPlaylist.updateLastModified()
                if (debugLog) Log.d("[CCM]", "[PlaylistManager.switchToPlaylist] Saved last track  '$currentTrackPath' pos = $currentPosition for old playlist")
            }

            // Переключаемся на новый плейлист
            currentPlaylistId = playlistId
            saveCurrentPlaylistId()
            if (updateLastUsed) {
                newPlaylist.updateLastUsed()
            }

            savePlaylists()
            if (debugLog) Log.d("[CCM]", "[PlaylistManager.switchToPlaylist] Switched from '${oldPlaylist?.name ?: "null"}' to '${newPlaylist.name}'")
            true
        } catch (e: Exception) {
            if (debugLog) Log.d("[CCM]", "[PlaylistManager.switchToPlaylist] Error", e)
            false
        }
    }

    /**
     * Сохраняет настройки текущего состояния в текущий плейлист
     */
    fun saveCurrentStateToPlaylist(
        musicDirectoryPath: String,
        musicDirectoryUri: String?,
        randomMode: String,
        blacklistItems: List<BlacklistItem>,
        currentTrackPath: String,
        currentPosition: Long,
        isPlaying: Boolean,
        wasStoppedByUser: Boolean
    ) {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.saveCurrentStateToPlaylist] In")
        val playlist = getCurrentPlaylist() ?: return

        if (debugLog) Log.d("[CCM]", "[PlaylistManager.saveCurrentStateToPlaylist] In, musicDirectoryPath=$musicDirectoryPath, randomMode=$randomMode, blacklistItems.size=${blacklistItems.size}, currentTrackPath=$currentTrackPath, currentPosition=$currentPosition, isPlaying=$isPlaying, wasStoppedByUser=$wasStoppedByUser")

        playlist.settings.musicDirectoryPath = musicDirectoryPath
        playlist.settings.musicDirectoryUri = musicDirectoryUri
        playlist.settings.randomMode = randomMode
        playlist.settings.blacklistItems = blacklistItems.toMutableList()  // Сохраняем blacklist ТОЛЬКО здесь
        playlist.settings.currentTrackPath = currentTrackPath
        playlist.settings.currentPosition = currentPosition


        playlist.updateLastModified()
        playlist.updateLastUsed()
        savePlaylists()

        if (debugLog) Log.d("[CCM]", "[PlaylistManager.saveCurrentStateToPlaylist] [BLACKLIST_DEBUG] Saved state to playlist: ${playlist.name}, blacklistSize=${playlist.settings.blacklistItems.size}")
    }
    /**
     * Получает текущий режим случайного порядка из настроек плейлиста
     */
    fun getCurrentRandomMode(): String {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.getCurrentRandomMode] In")
        val currentPlaylist = getCurrentPlaylist()
        val mode = currentPlaylist?.settings?.randomMode ?: "none"
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.getCurrentRandomMode] Mode: $mode")
        return mode
    }

    /**
     * Сохраняет режим случайного порядка в текущий плейлист
     */
    fun saveRandomModeToCurrentPlaylist(mode: String) {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.saveRandomModeToCurrentPlaylist] In, mode=$mode")
        val currentPlaylist = getCurrentPlaylist()

        if (currentPlaylist != null) {
            currentPlaylist.settings.randomMode = mode
            currentPlaylist.updateLastModified()
            savePlaylists()
            if (debugLog) Log.d("[CCM]", "[PlaylistManager.saveRandomModeToCurrentPlaylist] Saved to playlist '${currentPlaylist.name}': $mode")
        }
    }

    /**
     * Обновляет каталог музыки для текущего плейлиста
     */
    fun updateMusicDirectory(path: String, uri: String?) {
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.updateMusicDirectory] In, path=$path, uri=$uri")
        val playlist = getCurrentPlaylist() ?: return
        playlist.settings.musicDirectoryPath = path
        playlist.settings.musicDirectoryUri = uri
        playlist.updateLastModified()
        savePlaylists()
        if (debugLog) Log.d("[CCM]", "[PlaylistManager.updateMusicDirectory] Updated music directory for playlist: ${playlist.name}")
    }
    enum class SortCriterion {
        BY_NAME, BY_LAST_MODIFIED, BY_LAST_USED
    }

}