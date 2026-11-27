package com.lymsoft.meloman

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreference
import com.google.gson.Gson
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.View
class SettingsActivity : AppCompatActivity() {
    private val debugLog: Boolean
        get() = DebugConfig.isEnabled

    override fun onCreate(savedInstanceState: Bundle?) {
        if (debugLog) Log.d("[CCM]", "[SettingsActivity.onCreate] In")
        super.onCreate(savedInstanceState)

        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        // Исправление наложения ActionBar на пункты меню (edge-to-edge на Android 15+)
        val contentView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this) {
            val intent = Intent(this@SettingsActivity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(intent)
            finish()
        }
    }


    @OptIn(UnstableApi::class)
    class SettingsFragment : PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val debugLog: Boolean
            get() = DebugConfig.isEnabled

        private lateinit var themePref: ListPreference
        private lateinit var modePref: ListPreference
        private lateinit var randomPref: ListPreference
        private lateinit var musicStoragePref: Preference
        private lateinit var importMusicPref: Preference
        private lateinit var manageBlacklistPref: Preference
        private lateinit var autoPlayPref: SwitchPreference
        private lateinit var rememberPositionPref: SwitchPreference

        private lateinit var playWhenAppClosedPref: SwitchPreference
        private lateinit var playlistManager: PlaylistManager
        private lateinit var playlistManagerPref: Preference
        private lateinit var musicDirectoryPref: Preference
        private lateinit var debugPref: Preference
        private lateinit var pickMusicFolderLauncher: ActivityResultLauncher<Intent>
        private lateinit var pickStorageFolderLauncher: ActivityResultLauncher<Intent>
        private val gson = Gson()
        private lateinit var playlistManagerLauncher: ActivityResultLauncher<Intent>

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            if (debugLog) Log.d("[CCM]", "[SettingsFragment.onCreatePreferences] In")
            setPreferencesFromResource(R.xml.preferences, rootKey)
            debugPref = findPreference("debug_log")!!
//            debugPref.isVisible = false //Отключает отображение пункта дебаг
            themePref = findPreference("theme_mode")!!
            val languagePref = findPreference<ListPreference>("language_code")
            languagePref?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
//            languagePref = findPreference("language")!!
            modePref = findPreference("operation_mode")!!
            randomPref = findPreference("random_mode")!!
            musicDirectoryPref = findPreference("music_directory")!!
            musicStoragePref = findPreference("music_storage")!!
            importMusicPref = findPreference("import_music")!!
            manageBlacklistPref = findPreference("manage_blacklist")!!
            autoPlayPref = findPreference("auto_play")!!
            rememberPositionPref = findPreference("remember_track_position")!!
            playWhenAppClosedPref = findPreference("play_when_app_closed")!!
            playlistManager = PlaylistManager.getInstance(requireContext())
            playlistManagerPref = findPreference("playlist_manager")!!
            playlistManagerLauncher = registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    val selectedPlaylistId = result.data?.getStringExtra("selected_playlist_id")
                    if (selectedPlaylistId != null) switchPlaylist(selectedPlaylistId)
                }
            }
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            prefs.edit { putString("operation_mode", "phone") }
            val modePref = findPreference<ListPreference>("operation_mode")
            modePref?.value = "phone"
            modePref?.isEnabled = false
            applyModeSettings("phone")
            autoPlayPref.isChecked = false
            autoPlayPref.setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(requireContext(), "Function for MelomanPro", Toast.LENGTH_SHORT).show()
                false
            }

            rememberPositionPref.isChecked = false
            rememberPositionPref.setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(requireContext(), "Function for MelomanPro", Toast.LENGTH_SHORT).show()
                false
            }

            playWhenAppClosedPref.isChecked = false
            playWhenAppClosedPref.setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(requireContext(), "Function for MelomanPro", Toast.LENGTH_SHORT).show()
                false
            }
            updateDisplayFromPlaylist()
            setupPreferenceListeners()
            setupFolderPickers()
            updateAllSummaries()
            forceMelomanLimits()
        }
        private fun forceMelomanLimits() {
            if (debugLog) Log.d("[CCM]", "[SettingsFragment.forceMelomanLimits] In")
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            prefs.edit {
                putString("operation_mode", "phone")
                putBoolean("auto_play", false)
                putBoolean("remember_track_position", false)
                putBoolean("play_when_app_closed", false)
            }

            modePref.value = "phone"
            autoPlayPref.isChecked = false
            rememberPositionPref.isChecked = false
            playWhenAppClosedPref.isChecked = false
            prefs.edit {
                putString("operation_mode", "phone")
                putBoolean("auto_play", false)
                putBoolean("remember_track_position", false)
                putBoolean("play_when_app_closed", false)
                putBoolean("was_playing", false)
                putBoolean("was_stopped_by_user", true)
            }
        }
        private fun setupPreferenceListeners() {
            if (debugLog) Log.d("[CCM]", "[SettingsFragment.setupPreferenceListeners] In")
            modePref.setOnPreferenceClickListener { _ ->
                Toast.makeText(requireContext(), "Function for MelomanPro", Toast.LENGTH_SHORT).show()
                true
            }

            themePref.setOnPreferenceChangeListener { _, newValue ->
                val theme = newValue as String
                applyTheme(theme)
                updateThemeSummary(theme)
                true
            }

            randomPref.setOnPreferenceChangeListener { _, newValue ->
                val randomMode = newValue as String
                updateRandomSummary(randomMode)
                rebuildPlaylist()
                true
            }


            playlistManagerPref.setOnPreferenceClickListener {
                playlistManagerLauncher.launch(Intent(requireContext(), PlaylistManagerActivity::class.java))
                true
            }

            musicDirectoryPref.setOnPreferenceClickListener {
                openFolderPicker("music")
                true
            }

            musicStoragePref.setOnPreferenceClickListener {
                openFolderPicker("storage")
                true
            }

            importMusicPref.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), ImportMusicActivity::class.java))
                true
            }

            manageBlacklistPref.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), BlacklistActivity::class.java))
                true
            }
        }

        private fun setupFolderPickers() {
            if (debugLog) Log.d("[CCM]", "[SettingsFragment.setupFolderPickers] In")

            pickMusicFolderLauncher = registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    val data = result.data
                    val uri = data?.data
                    if (uri != null) {
                        handleMusicFolderSelected(uri)
                    }
                }
            }

            pickStorageFolderLauncher = registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == RESULT_OK) {
                    val data = result.data
                    val uri = data?.data
                    if (uri != null) {
                        handleStorageFolderSelected(uri)
                    }
                }
            }
        }

        private fun handleMusicFolderSelected(uri: Uri) {
            if (debugLog) Log.d("[CCM]", "[SettingsFragment.handleMusicFolderSelected] uri=$uri")
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                saveFolderUri(uri)

                val realPath = getRealPathFromUri(uri)
                if (!realPath.isNullOrEmpty()) {
                    saveFolderPath(realPath, "selected_folder_path")

                    val currentPlaylist = playlistManager.getCurrentPlaylist()
                    if (currentPlaylist != null) {
                        playlistManager.updatePlaylistMusicDirectory(currentPlaylist.id, realPath, uri.toString())
                    }

                    updateMusicDirectorySummary(realPath)
                    playlistManager.updateMusicDirectory(realPath, uri.toString())
                    Toast.makeText(requireContext(), "${getString(R.string.music_directory_title)}: $realPath", Toast.LENGTH_LONG).show()

                    rebuildPlaylist()
                } else {
                    val altPath = getPathFromUriAlternative(uri)
                    if (altPath != null) {
                        saveFolderPath(altPath, "selected_folder_path")
                        updateMusicDirectorySummary(altPath)
                        Toast.makeText(requireContext(), "${getString(R.string.music_directory_title)}: $altPath", Toast.LENGTH_LONG).show()
                        rebuildPlaylist()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.import_error, "Failed to get folder path"), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                if (debugLog) Log.d("[CCM]", "[SettingsFragment.handleMusicFolderSelected] Error", e)
                Toast.makeText(requireContext(), getString(R.string.import_error, e.message ?: "Unknown error"), Toast.LENGTH_LONG).show()
            }
        }
        private fun updateDisplayFromPlaylist() {
            if (debugLog) Log.d("[CCM]", "[SettingsFragment.updateDisplayFromPlaylist] In")
            val currentPlaylist = playlistManager.getCurrentPlaylist()

            if (currentPlaylist != null) {
                val settings = currentPlaylist.settings
                val musicPath = settings.musicDirectoryPath.ifEmpty { "/storage/emulated/0/Music" }

                musicDirectoryPref.summary = musicPath
                val randomPref = findPreference<ListPreference>("random_mode")
                randomPref?.value = settings.randomMode.ifEmpty { "none"}
                updateRandomSummary(settings.randomMode.ifEmpty { "none"})
                updatePlaylistSummary()
            }
        }
        private fun handleStorageFolderSelected(uri: Uri) {
            if (debugLog) Log.d("[CCM]", "[SettingsFragment.handleStorageFolderSelected] uri=$uri")
            val realPath = getRealPathFromUri(uri)
            if (realPath != null) {
                saveFolderPath(realPath, "music_storage_path")
                updateMusicStorageSummary(realPath)
                Toast.makeText(requireContext(), "${getString(R.string.music_storage_title)}: $realPath", Toast.LENGTH_LONG).show()
            }
        }

        private fun updateAllSummaries() {
            if (debugLog) Log.d("[CCM]", "[SettingsFragment.updateAllSummaries] In")
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            updateThemeSummary(prefs.getString("theme_mode", "system"))
//            updateLanguageSummary()
            updateModeSummary(prefs.getString("operation_mode", "phone"))
            updateRandomSummary(prefs.getString("random_mode", "none"))
            updateMusicDirectorySummary(prefs.getString("selected_folder_path", null))
            updateMusicStorageSummary(prefs.getString("music_storage_path", null))
            loadSettingsFromPlaylist()
        }

        private fun updateThemeSummary(theme: String?) {
            val themeMode = theme ?: PreferenceManager.getDefaultSharedPreferences(requireContext()).getString("theme_mode", "system")
            val themeName = when (themeMode) {
                "light" -> getString(R.string.theme_light)
                "dark" -> getString(R.string.theme_dark)
                else -> getString(R.string.theme_system)
            }
            themePref.summary = themeName
        }


        private fun updateModeSummary(mode: String?) {
            val modeValue = mode ?: PreferenceManager.getDefaultSharedPreferences(requireContext()).getString("operation_mode", "phone")
            val modeName = when (modeValue) {
                "phone" -> getString(R.string.mode_phone)
                "car" -> getString(R.string.mode_car)
                else -> getString(R.string.mode_phone)
            }
            modePref.summary = modeName
        }

        private fun updateRandomSummary(randomMode: String?) {
            val mode = randomMode ?: PreferenceManager.getDefaultSharedPreferences(requireContext()).getString("random_mode", "none")
            val modeName = when (mode) {
                "none" -> getString(R.string.random_none)
                "in_folder" -> getString(R.string.random_in_folder)
                "everywhere" -> getString(R.string.random_everywhere)
                else -> getString(R.string.random_none)
            }
            randomPref.summary = modeName
        }

        private fun updateMusicDirectorySummary(path: String?) {
            if (debugLog) Log.d("[CCM]", "[SettingsFragment.updateMusicDirectorySummary] In, path=$path")
            val currentPlaylist = playlistManager.getCurrentPlaylist()
            val playlistPath = currentPlaylist?.settings?.musicDirectoryPath
            val finalPath = if (!playlistPath.isNullOrEmpty()) {
                playlistPath
            } else {
                path ?: "/storage/emulated/0/Music"
            }
            musicDirectoryPref.summary = finalPath
        }

        private fun updateMusicStorageSummary(path: String?) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val savedPath = path ?: prefs.getString("music_storage_path", null)
            if (!savedPath.isNullOrEmpty()) {
                musicStoragePref.summary = savedPath
            } else {
                musicStoragePref.summary = getString(R.string.music_storage_summary)
            }
        }

        private fun applyTheme(theme: String) {
            if (debugLog) Log.d("[CCM]", "[SettingsFragment.applyTheme] theme=$theme")
            when (theme) {
                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
        private fun switchPlaylist(playlistId: String) {
            if (debugLog) Log.d("[CCM]", "[SettingsFragment.switchPlaylist] playlistId=$playlistId")
            try {
                val playlistManager = PlaylistManager.getInstance(requireContext())
                val newPlaylist = playlistManager.getPlaylist(playlistId)

                if (newPlaylist != null) {
                    val settings = newPlaylist.settings
                    val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    prefs.edit {
                        putString("selected_folder_path", settings.musicDirectoryPath)
                        putString("selected_folder_uri", settings.musicDirectoryUri)
                    }

                    updateMusicDirectorySummary(settings.musicDirectoryPath)

                    val randomPref = findPreference<ListPreference>("random_mode")
                    randomPref?.value = settings.randomMode
                    updateRandomSummary(settings.randomMode)

                    Toast.makeText(requireContext(),
                        getString(R.string.playlist_switch_success, newPlaylist.name),
                        Toast.LENGTH_SHORT).show()

                    val switchIntent = Intent(requireContext(), MusicPlaybackService::class.java)
                    switchIntent.action = MusicPlaybackService.ACTION_SWITCH_TO_PLAYLIST
                    switchIntent.putExtra("playlist_id", playlistId)
                    requireContext().startService(switchIntent)

                    MusicPlaybackService.notifyPlaylistChanged()

                    updatePlaylistSummary()
                } else {
                    Toast.makeText(requireContext(), "Playlist not found", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (debugLog) Log.d("[CCM]", "[SettingsFragment.switchPlaylist] Error switching playlist", e)
                Toast.makeText(requireContext(), "Error switching playlist: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        private fun applyModeSettings(mode: String) {
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit {
                when (mode) {
                    "phone" -> {
                        putBoolean("auto_play", false)
                        putBoolean("remember_track_position", false)
                        putBoolean("play_when_app_closed", false)
                    }
                    "car" -> {
                        putBoolean("auto_play", true)
                        putBoolean("remember_track_position", true)
                        putBoolean("play_when_app_closed", true)
                    }
                }
            }

            when (mode) {
                "phone" -> {
                    autoPlayPref.isChecked = false
                    rememberPositionPref.isChecked = false
                    playWhenAppClosedPref.isChecked = false
                }
                "car" -> {
                    autoPlayPref.isChecked = true
                    rememberPositionPref.isChecked = true
                    playWhenAppClosedPref.isChecked = true
                }
            }

            val intent = Intent(requireContext(), MusicPlaybackService::class.java)
            intent.action = MusicPlaybackService.ACTION_UPDATE_SETTINGS
            requireContext().startService(intent)
        }

        private fun loadSettingsFromPlaylist() {
            if (debugLog) Log.d("[CCM]", "[SettingsFragment.loadSettingsFromPlaylist] In")
            val currentPlaylist = playlistManager.getCurrentPlaylist()

            if (currentPlaylist != null) {
                val settings = currentPlaylist.settings
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

                val musicPath = settings.musicDirectoryPath.ifEmpty { "/storage/emulated/0/Music" }
                val musicUri = settings.musicDirectoryUri

                prefs.edit {
                    putString("selected_folder_path", musicPath)
                    putString("selected_folder_uri", musicUri)
                    putString("blacklist_items", gson.toJson(settings.blacklistItems))
                }

                updateMusicDirectorySummary(musicPath)
                updateMusicStorageSummary(prefs.getString("music_storage_path", null))

                val randomPref = findPreference<ListPreference>("random_mode")
                randomPref?.value = settings.randomMode.ifEmpty { "none"}
                updateRandomSummary(settings.randomMode.ifEmpty { "none"})

                updatePlaylistSummary()
            }
        }

        private fun openFolderPicker(type: String) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }

            if (type == "music") {
                pickMusicFolderLauncher.launch(intent)
            } else {
                pickStorageFolderLauncher.launch(intent)
            }
        }

        private fun saveFolderUri(uri: Uri) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            prefs.edit { putString("selected_folder_uri", uri.toString()) }

            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                if (debugLog) Log.d("[CCM]", "[SettingsFragment.saveFolderUri] Error", e)
            }
        }

        private fun saveFolderPath(path: String, key: String) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            prefs.edit { putString(key, path) }
        }

        private fun getRealPathFromUri(uri: Uri): String? {
            return try {
                val docId = DocumentsContract.getTreeDocumentId(uri)
                val split = docId.split(":")
                if (split.size > 1) {
                    val volumeName = split[0]
                    val path = split[1]

                    when {
                        volumeName == "primary" -> "/storage/emulated/0/$path"
                        volumeName.startsWith("raw:") -> {
                            val rawPath = volumeName.substring(4)
                            "/storage/$rawPath/$path"
                        }
                        else -> "/storage/$volumeName/$path"
                    }
                } else {
                    getFullPathFromUri(uri)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                getFullPathFromUri(uri)
            }
        }

        private fun getFullPathFromUri(uri: Uri): String? {
            return try {
                val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    val columnIndex = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    if (it.moveToFirst()) {
                        it.getString(columnIndex)
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        private fun getPathFromUriAlternative(uri: Uri): String? {
            return try {
                val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
                val cursor = requireContext().contentResolver.query(uri, projection, null, null, null)
                cursor?.use {
                    val columnIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                    if (it.moveToFirst()) {
                        it.getString(columnIndex)
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        private fun rebuildPlaylist() {
            if (debugLog) Log.d("[CCM]", "[SettingsFragment.rebuildPlaylist] In")
            Thread {
                try {
                    val intent = Intent(requireContext(), MusicPlaybackService::class.java)
                    intent.action = MusicPlaybackService.ACTION_RESCAN_PLAYLIST
                    requireContext().startService(intent)
                } catch (e: Exception) {
                    if (debugLog) Log.d("[CCM]", "[SettingsFragment.rebuildPlaylist] Error", e)
                }
            }.start()
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
            loadSettingsFromPlaylist()
            updateAllSummaries()
            updatePlaylistSummary()
            forceMelomanLimits()
        }

        override fun onPause() {
            super.onPause()
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        }

        private fun updatePlaylistSummary() {
            try {
                val currentPlaylist = playlistManager.getCurrentPlaylist()
                val playlistName = currentPlaylist?.name ?: "None"
                playlistManagerPref.summary = getString(R.string.playlists_summary, playlistName)
            } catch (_: Exception) {
                playlistManagerPref.summary = getString(R.string.playlists_summary, "Error")
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (debugLog) Log.d("[CCM]", "[SettingsFragment.onSharedPreferenceChanged] key=$key")

            when (key) {
                "language_code" -> {
                    val code = sharedPreferences?.getString(key, "system") ?: "system"
                    if (debugLog) Log.d("[CCM]", "[SettingsFragment.onSharedPreferenceChanged] language_code=$code")
                    if (code == "system") {
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                    } else {
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
                    }
                }
                "selected_folder_path" -> {
                    val path = sharedPreferences?.getString(key, null)
                    updateMusicDirectorySummary(path)
                    rebuildPlaylist()
                }
                "music_storage_path" -> {
                    val path = sharedPreferences?.getString(key, null)
                    updateMusicStorageSummary(path)
                }
                "random_mode" -> {
                    val mode = sharedPreferences?.getString(key, null)
                    updateRandomSummary(mode)
                    val playlistManager = PlaylistManager.getInstance(requireContext())
                    playlistManager.saveRandomModeToCurrentPlaylist(mode ?: "none")
                    val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                        action = "UPDATE_RANDOM_MODE"
                        putExtra("random_mode", mode ?: "none")
                    }
                    requireContext().startService(intent)
                }
                "play_when_app_closed" -> {
                    val intent = Intent(requireContext(), MusicPlaybackService::class.java)
                    intent.action = MusicPlaybackService.ACTION_UPDATE_SETTINGS
                    requireContext().startService(intent)
                }
                "theme_mode" -> updateThemeSummary(sharedPreferences?.getString(key, null))
                "operation_mode" -> updateModeSummary(sharedPreferences?.getString(key, null))
                "debug_log" -> {
                    val enabled = sharedPreferences?.getBoolean(key, false) ?: false
                    DebugConfig.setEnabled(requireContext(), enabled)
                }
            }
        }

    }
}