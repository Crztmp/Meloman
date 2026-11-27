package com.lymsoft.meloman

import java.text.SimpleDateFormat
import java.util.Date
import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import java.io.File
import android.util.Log
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.abs
import android.content.res.ColorStateList
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@UnstableApi
class MainActivity : AppCompatActivity() {

    private val debugLog: Boolean
        get() = DebugConfig.isEnabled

    private var isFirstStart = true
    private lateinit var tvCurrentFolder: TextView
    private lateinit var tvCurrentTrack: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvRemainingTime: TextView
    private lateinit var tvDurationTime: TextView
    private lateinit var tvDebug: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvAuthor: TextView
    private lateinit var btnMute: MaterialButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var btnExit: MaterialButton
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnPrevTrack: MaterialButton
    private lateinit var btnNextTrack: MaterialButton
    private lateinit var btnPrevFolder: MaterialButton
    private lateinit var btnNextFolder: MaterialButton
    private lateinit var btnBlacklistTrack: MaterialButton
    private lateinit var btnBlacklistFolder: MaterialButton
    private lateinit var sbTrackProgress: SeekBar
    private lateinit var sbVolume: SeekBar
    private lateinit var btnRandom: MaterialButton
    private lateinit var tvPlaylistName: TextView
    private var currentRandomMode: String = "none"
    private var lastDisplayedTrackPath: String? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager
    private var lastDisplayedTrackNumber: Int = -1
    private var volumeReceiver: VolumeReceiver? = null

    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var isExiting = false
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val playbackService: MusicPlaybackService?
        get() = MusicPlaybackService.instance

    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val newVolume = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                if (newVolume >= 0) {
                    runOnUiThread { sbVolume.progress = newVolume }
                }
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(
                this,
                "Уведомления отключены — foreground-сервис может не отображаться",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            runOnUiThread {
                try {
                    updateUI()
                } catch (e: Exception) {
                    if (debugLog) Log.d("[CCM]", "[MainActivity.playerListener.onEvents] error", e)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            runOnUiThread {
                try {
                    updatePlayPauseButton(isPlaying)
                } catch (e: Exception) {
                    if (debugLog) Log.d(
                        "[CCM]",
                        "[MainActivity.playerListener.onIsPlayingChanged] error",
                        e
                    )
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            runOnUiThread {
                try {
                    lastDisplayedTrackPath = null
                    Handler(Looper.getMainLooper()).postDelayed({ updateUI() }, 300)
                } catch (e: Exception) {
                    if (debugLog) Log.d(
                        "[CCM]",
                        "[MainActivity.playerListener.onMediaItemTransition] error",
                        e
                    )
                }
            }
        }
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            Handler(Looper.getMainLooper()).postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (debugLog) Log.d("[CCM]", "[MainActivity.onCreate] In")
        super.onCreate(savedInstanceState)

        // Первый запускаемый модуль — инициализация DebugConfig и языка
        DebugConfig.init(this)
//        applySavedLanguage()
        ensureDefaultLanguage()

        setContentView(R.layout.activity_main)

        val prefsForBattery = PreferenceManager.getDefaultSharedPreferences(this)
        val batteryAsked = prefsForBattery.getBoolean("battery_optimization_asked", false)
        if (!batteryAsked) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                prefsForBattery.edit { putBoolean("battery_optimization_asked", true) }
                AlertDialog.Builder(this)
                    .setTitle("Оптимизация батареи")
                    .setMessage("Для стабильной работы в автомобиле рекомендуется отключить оптимизацию батареи для этого приложения")
                    .setPositiveButton("Отключить") { _, _ ->
                        requestIgnoreBatteryOptimizations(this)
                    }
                    .setNegativeButton("Не сейчас", null)
                    .show()
            }
        }
        initViews()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        applyTheme()
        setupUI()
        setupVolumeControl()
        setupVolumeReceiver()
        displayVersion()
        loadRandomModeFromPlaylist()
        if (savedInstanceState != null) isFirstStart = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, MusicPlaybackService::class.java))
        } else {
            startService(Intent(this, MusicPlaybackService::class.java))
        }
        connectToService()

        requestPermissions()
        requestNotificationPermissionIfNeeded()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { MusicPlaybackService.playlistChanges.collect { updatePlaylistNameDisplay(); updateUI() } }
                launch { MusicPlaybackService.debugMessages.collect { addDebugMessage(it) } }
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations(activity: AppCompatActivity) {
        if (debugLog) Log.d("[CCM]", "[MainActivity.requestIgnoreBatteryOptimizations] In")
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:$packageName".toUri()
                }
                activity.startActivity(intent)
            }
        } catch (e: Exception) {
            if (debugLog) Log.w(
                "[CCM]",
                "[MainActivity.requestIgnoreBatteryOptimizations] failed",
                e
            )
        }
    }

    private fun connectToService() {
        if (debugLog) Log.d("[CCM]", "[MainActivity.connectToService] In")
        val sessionToken = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                mediaController?.addListener(playerListener)
                if (debugLog) Log.d("[CCM]", "[MainActivity.connectToService] Controller connected")
                runOnUiThread { updateUI() }
                handleFirstStartAutoPlay()
            } catch (e: Exception) {
                if (debugLog) Log.d("[CCM]", "[MainActivity.connectToService] Error", e)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun handleFirstStartAutoPlay() {
        val autoPlay = prefs.getBoolean("auto_play", false)
        if (debugLog) Log.d(
            "[CCM]",
            "[MainActivity.handleFirstStartAutoPlay] isFirstStart=$isFirstStart, autoPlay=$autoPlay"
        )
        if (isFirstStart && autoPlay) {
            val isPlaying = mediaController?.isPlaying ?: false
            val hasItem = mediaController?.currentMediaItem != null
            if (!isPlaying && hasItem) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isExiting) {
                        mediaController?.play()
                        updatePlayPauseButton(true)
                    }
                }, 500)
            }
            isFirstStart = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("is_first_start", false)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        isFirstStart = savedInstanceState.getBoolean("is_first_start", true)
    }

    override fun onDestroy() {
        if (debugLog) Log.d("[CCM]", "[MainActivity.onDestroy] In")
        super.onDestroy()
        mainScope.cancel()
        volumeReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        try {
            mediaController?.removeListener(playerListener)
        } catch (_: Exception) {
        }
        try {
            mediaController?.release()
        } catch (_: Exception) {
        }
        mediaController = null
        try {
            controllerFuture?.let { MediaController.releaseFuture(it) }
        } catch (_: Exception) {
        }
        controllerFuture = null
    }

    private fun initViews() {
        tvCurrentFolder = findViewById(R.id.tvCurrentFolder)
        tvCurrentTrack = findViewById(R.id.tvCurrentTrack)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvRemainingTime = findViewById(R.id.tvRemainingTime)
        tvDurationTime = findViewById(R.id.tvDurationTime)
        tvDebug = findViewById(R.id.tvDebug)
        tvVersion = findViewById(R.id.tvVersion)
        tvAuthor = findViewById(R.id.tvAuthor)
        tvPlaylistName = findViewById(R.id.tvPlaylistName)
        btnRandom = findViewById(R.id.btnRandom)
        btnMute = findViewById(R.id.btnMute)
        btnSettings = findViewById(R.id.btnSettings)
        btnExit = findViewById(R.id.btnExit)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnStop = findViewById(R.id.btnStop)
        btnPrevTrack = findViewById(R.id.btnPrevTrack)
        btnNextTrack = findViewById(R.id.btnNextTrack)
        btnPrevFolder = findViewById(R.id.btnPrevFolder)
        btnNextFolder = findViewById(R.id.btnNextFolder)
        btnBlacklistTrack = findViewById(R.id.btnBlacklistTrack)
        btnBlacklistFolder = findViewById(R.id.btnBlacklistFolder)
        sbTrackProgress = findViewById(R.id.sbTrackProgress)
        sbVolume = findViewById(R.id.sbVolume)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnStop.setOnClickListener { stopPlayback() }
        btnExit.setOnClickListener { exitApp() }
        btnPrevTrack.setOnClickListener {
            playbackService?.previousTrack()
            Handler(Looper.getMainLooper()).postDelayed({ updateUI() }, 100)
        }
        btnNextTrack.setOnClickListener {
            playbackService?.nextTrack()
            Handler(Looper.getMainLooper()).postDelayed({ updateUI() }, 100)
        }
        btnPrevFolder.setOnClickListener { playbackService?.previousFolder() }
        btnNextFolder.setOnClickListener { playbackService?.nextFolder() }
        btnBlacklistTrack.setOnClickListener { blacklistCurrentTrack() }
        btnBlacklistFolder.setOnClickListener { blacklistCurrentFolder() }
        btnMute.setOnClickListener { toggleMute() }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnRandom.setOnClickListener { cycleRandomMode() }
        tvPlaylistName.setOnClickListener { showPlaylistSelectorDialog() }
        sbTrackProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) mediaController?.seekTo(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })


    }

    private fun setupVolumeReceiver() {
        volumeReceiver = VolumeReceiver()
        val filter = IntentFilter()
        filter.addAction("android.media.VOLUME_CHANGED_ACTION")
        registerReceiver(volumeReceiver, filter)
    }

    private fun showPlaylistSelectorDialog() {
        val playlistManager = PlaylistManager.getInstance(this)
        val playlists = playlistManager.getAllPlaylists().sortedByDescending { it.lastUsed }
        val currentPlaylist = playlistManager.getCurrentPlaylist()
        if (playlists.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_playlists_available), Toast.LENGTH_SHORT)
                .show()
            return
        }
        val playlistNames = playlists.map { playlist ->
            if (playlist.id == currentPlaylist?.id) "✓ ${playlist.name}" else playlist.name
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_playlist))
            .setItems(playlistNames) { dialog, which ->
                val selectedPlaylist = playlists[which]
                val intent = Intent(this, MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_SWITCH_TO_PLAYLIST
                    putExtra("playlist_id", selectedPlaylist.id)
                }
                startService(intent)
                updatePlaylistNameDisplay()
                Handler(Looper.getMainLooper()).postDelayed({ loadRandomModeFromPlaylist() }, 300)
                dialog.dismiss()
            }
            .setNeutralButton(getString(R.string.edit_playlists)) { _, _ ->
                startActivity(Intent(this, PlaylistManagerActivity::class.java))
            }
            .setNegativeButton(getString(R.string.cancel_action), null)
            .show()
    }

    private fun updatePlaylistNameDisplay() {
        val playlistManager = PlaylistManager.getInstance(this)
        val currentPlaylist = playlistManager.getCurrentPlaylist()
        if (currentPlaylist != null) {
            tvPlaylistName.text = getString(R.string.playlist_display_format, currentPlaylist.name)
        } else {
            tvPlaylistName.text = getString(R.string.no_playlist)
        }
    }

    private fun exitApp() {
        isExiting = true
        isFirstStart = true
        MusicPlaybackService.isUserExiting = true
        if (debugLog) Log.d(
            "[CCM]",
            "[MainActivity.exitApp] isExiting = $isExiting, isFirstStart = $isFirstStart"
        )
        playbackService?.let {
            try {
                val intent = Intent(this, MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_STOP_AND_EXIT
                }
                startService(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        finishAffinity()
    }

    private fun setupVolumeControl() {
        if (debugLog) Log.d("[CCM]", "[MainActivity.setupVolumeControl] in")
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        sbVolume.max = maxVolume
        sbVolume.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) setVolume(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setVolume(progress: Int) {
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                sendMediaKeyEvent(
                    if (progress > audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                        KeyEvent.KEYCODE_VOLUME_UP
                    } else {
                        KeyEvent.KEYCODE_VOLUME_DOWN
                    }
                )
            }
        } catch (e: Exception) {
            if (debugLog) Log.d("[CCM]", "[MainActivity.setVolume] Error", e)
        }
    }

    private fun sendMediaKeyEvent(keyCode: Int) {
        if (debugLog) Log.d("[CCM]", "[MainActivity.sendMediaKeyEvent] in, keyCode = $keyCode")
        try {
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)
        } catch (e: Exception) {
            if (debugLog) Log.d("[CCM]", "[MainActivity.sendMediaKeyEvent] Error", e)
        }
    }

    private fun toggleMute() {
        try {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (currentVolume > 0) {
                prefs.edit { putInt("saved_volume", currentVolume) }
                setVolume(0)
                btnMute.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_volume_mute))
                Toast.makeText(this, getString(R.string.sound_off), Toast.LENGTH_SHORT).show()
            } else {
                val savedVolume = prefs.getInt("saved_volume", sbVolume.max / 2)
                setVolume(savedVolume)
                btnMute.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_volume_up))
                Toast.makeText(this, getString(R.string.sound_on), Toast.LENGTH_SHORT).show()
            }
            sbVolume.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        } catch (_: Exception) {
            Toast.makeText(this, "Volume control not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI() {
        val service = playbackService
        if (service == null) {
            tvCurrentTrack.text = getString(R.string.no_track_playing)
            return
        }
        updatePlaylistNameDisplay()
        val track = try {
            service.getCurrentTrack()
        } catch (_: Exception) {
            null
        }
        if (track != null && track.file != null) {
            val trackNumber = service.getCurrentTrackNumberInFolder()
            val tracksInFolder = service.getCurrentFolderTracksCount()
            val path = track.filePath
            if (path != lastDisplayedTrackPath || trackNumber != lastDisplayedTrackNumber) {
                lastDisplayedTrackPath = path
                lastDisplayedTrackNumber = trackNumber
                mainScope.launch {
                    val displayName = withContext(Dispatchers.IO) {
                        track.file?.let { AudioTagReader.getDisplayName(it) }
                            ?: track.file?.nameWithoutExtension ?: "Unknown track"
                    }
                    track.setDisplayName(displayName)
                    withContext(Dispatchers.Main) {
                        tvCurrentTrack.text = getString(
                            R.string.track_position_format,
                            trackNumber,
                            tracksInFolder,
                            displayName
                        )
                    }
                }
            }
        } else {
            tvCurrentTrack.text = getString(R.string.no_track_playing)
        }
        val folderDisplay = try {
            service.getCurrentFolderDisplay()
        } catch (_: Exception) {
            Pair("Unknown", 0)
        }
        updatePlayPauseButton(mediaController?.isPlaying ?: service.isPlaying())
        if (folderDisplay.second > 0) {
            val folderIndex = try {
                service.getCurrentFolderIndex() + 1
            } catch (_: Exception) {
                0
            }
            tvCurrentFolder.text = getString(
                R.string.folder_position_format,
                folderIndex,
                folderDisplay.second,
                folderDisplay.first
            )
        } else {
            tvCurrentFolder.text = folderDisplay.first
        }
        val totalTracks = try {
            service.getTotalTracksCount()
        } catch (_: Exception) {
            0
        }
        val totalFolders = try {
            service.getTotalFoldersCount()
        } catch (_: Exception) {
            0
        }
        tvDebug.text = getString(R.string.debug_ready_summary, totalTracks, totalFolders)
        if (DebugConfig.isEnabled) tvDebug.visibility = View.VISIBLE
        else {
            tvDebug.text = ""; tvDebug.visibility = View.GONE
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        if (isPlaying) btnPlayPause.setIcon(
            ContextCompat.getDrawable(
                this,
                R.drawable.ic_control_pause
            )
        )
        else btnPlayPause.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_control_play))
        btnPlayPause.invalidate()
    }

    fun addDebugMessage(message: String) {
        runOnUiThread {
            val currentText = tvDebug.text.toString()
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val newText = getString(R.string.debug_message_format, timestamp, message, currentText)
            val lines = newText.lines()
            tvDebug.text = if (lines.size > 10) lines.take(10).joinToString("\n") else newText
        }
    }

    private fun updateProgress() {
        val service = playbackService ?: return
        val position = mediaController?.currentPosition ?: try {
            service.getCurrentPosition()
        } catch (_: Exception) {
            0L
        }
        val duration = mediaController?.duration ?: try {
            service.getDuration()
        } catch (_: Exception) {
            0L
        }
        if (duration > 0) {
            sbTrackProgress.progress = position.toInt()
            sbTrackProgress.max = duration.toInt()
            tvCurrentTime.text = formatTime(position)
            tvDurationTime.text = formatTime(duration)
            tvRemainingTime.text =
                getString(R.string.time_remaining_format, formatTime(duration - position))
        } else {
            sbTrackProgress.progress = 0
            tvCurrentTime.text = getString(R.string.time_zero)
            tvDurationTime.text = getString(R.string.time_zero)
            tvRemainingTime.text = getString(R.string.time_remaining_zero)
        }
        updatePlayPauseButton(mediaController?.isPlaying ?: service.isPlaying())
    }

    private fun formatTime(millis: Long): String {
        val seconds = (millis / 1000).toInt()
        return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun togglePlayPause() {
        val controller = mediaController
        val service = playbackService
        if (controller != null && service != null) {
            if (controller.isPlaying) {
                controller.pause()
            } else {
                service.play()   // ← напрямую, минуя MediaSession
            }
            updateUI()
            return
        }
        playbackService?.let { s ->
            if (s.isPlaying()) s.pause() else s.play()
            updateUI()
        }
    }

    private fun stopPlayback() {
        mediaController?.stop()
        playbackService?.stop()
        sbTrackProgress.progress = 0
        tvCurrentTime.text = getString(R.string.time_zero)
        tvRemainingTime.text = getString(R.string.time_remaining_zero)
        tvDurationTime.text = getString(R.string.time_zero)
        updatePlayPauseButton(false)
    }

    private fun blacklistCurrentTrack() {
        val track = playbackService?.getCurrentTrack()
        track?.file?.let { file ->
            playbackService?.addToBlacklist(
                file.absolutePath,
                BlacklistItem.ItemType.FILE,
                file.name
            )
            Toast.makeText(this, "Added to blacklist: ${file.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun blacklistCurrentFolder() {
        val track = playbackService?.getCurrentTrack()
        if (track != null) {
            playbackService?.addToBlacklist(
                track.folderPath,
                BlacklistItem.ItemType.DIRECTORY,
                File(track.folderPath).name
            )
            Toast.makeText(
                this,
                "Added to blacklist: ${File(track.folderPath).name}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun displayVersion() {
        try {
            tvVersion.text = packageManager.getPackageInfo(packageName, 0).versionName
            tvAuthor.text = getString(R.string.author_name)
        } catch (_: Exception) {
            tvVersion.text = getString(R.string.default_version)
            tvAuthor.text = getString(R.string.author_name)
        }
    }

    private fun applyTheme() {
        when (prefs.getString("theme_mode", "system")) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun requestPermissions() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_MEDIA_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.READ_MEDIA_AUDIO),
                        100
                    )
                }
            }

            else -> {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                        100
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val asked = prefs.getBoolean("notification_permission_asked", false)
        if (asked) {
            if (debugLog) Log.d("[CCM]", "[MainActivity.requestNotificationPermissionIfNeeded] already asked, skipping")
            return
        }

        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {
                // уже есть — не спрашиваем, но флаг ставим, чтобы больше не пытаться
                prefs.edit { putBoolean("notification_permission_asked", true) }
            }
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                prefs.edit { putBoolean("notification_permission_asked", true) }
                AlertDialog.Builder(this)
                    .setTitle("Уведомления")
                    .setMessage("Для отображения управления музыкой в шторке нужно разрешение на уведомления.")
                    .setPositiveButton("Разрешить") { _, _ -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    .setNegativeButton("Не сейчас", null)
                    .show()
            }
            else -> {
                prefs.edit { putBoolean("notification_permission_asked", true) }
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Handler(Looper.getMainLooper()).post(progressUpdater)
        registerReceiver(volumeChangeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
    }

    override fun onPause() {
        super.onPause()
        Handler(Looper.getMainLooper()).removeCallbacks(progressUpdater)
        try {
            unregisterReceiver(volumeChangeReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    inner class VolumeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val newVolume = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                if (newVolume >= 0) runOnUiThread { sbVolume.progress = newVolume }
            }
        }
    }

    private fun cycleRandomMode() {
        val nextMode = when (currentRandomMode) {
            "none" -> "in_folder"
            "in_folder" -> "everywhere"
            "everywhere" -> "none"
            else -> "none"
        }
        currentRandomMode = nextMode
        PlaylistManager.getInstance(this).saveRandomModeToCurrentPlaylist(nextMode)
        updateRandomButtonIcon()
        val intent = Intent(this, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_UPDATE_RANDOM_MODE
            putExtra("random_mode", nextMode)
        }
        startService(intent)
        lastDisplayedTrackPath = null
        lastDisplayedTrackNumber = -1
        Handler(Looper.getMainLooper()).postDelayed({
            updateUI()
        }, 300)
    }

    private fun updateRandomButtonIcon() {
        btnRandom.contentDescription = when (currentRandomMode) {
            "none" -> getString(R.string.random_button_none)
            "in_folder" -> getString(R.string.random_button_in_folder)
            "everywhere" -> getString(R.string.random_button_everywhere)
            else -> getString(R.string.random_button_none)
        }
        btnRandom.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_random))
        val tint = when (currentRandomMode) {
            "none" -> ContextCompat.getColor(this, R.color.gray)
            "in_folder" -> ContextCompat.getColor(this, R.color.dark_green)
            "everywhere" -> ContextCompat.getColor(this, R.color.yellow_400)
            else -> ContextCompat.getColor(this, R.color.gray)
        }
        btnRandom.iconTint = ColorStateList.valueOf(tint)
        btnRandom.invalidate()
    }

    private fun ensureDefaultLanguage() {
        if (debugLog) Log.d("[CCM]", "[MainActivity.ensureDefaultLanguage] In")
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.contains("language_code")) {
            if (debugLog) Log.d(
                "[CCM]",
                "[MainActivity.ensureDefaultLanguage] language_code not set, applying 'system'"
            )
            prefs.edit().putString("language_code", "system").apply()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
    }

    private fun loadRandomModeFromPlaylist() {
        currentRandomMode = PlaylistManager.getInstance(this).getCurrentRandomMode()
        updateRandomButtonIcon()
    }

}

