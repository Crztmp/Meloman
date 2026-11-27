@file:Suppress("OPT_IN_ARGUMENT_IS_NOT_MARKER")

package com.lymsoft.meloman

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class MusicPlaybackService : MediaSessionService() {

    private val debugLog: Boolean
        get() = DebugConfig.isEnabled
    @Volatile
    private var isServiceStopping = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var lastKnownTrackCount: Int = -1
    private var lastKnownRandomMode: String = "none"
    private var fileCheckHandler: Handler? = null
    private var forceRebuildOnNextRescan = false
    private val isScanningLock = Any()
    private var positionSaveHandler: Handler? = null

    // === Копирование (перенесено из App) ===
    private val copyQueue = ConcurrentLinkedQueue<CopyTask>()
    private val pendingTasks = mutableMapOf<String, CopyTask>()
    private val cancelledTasks = mutableMapOf<String, CopyTask>()
    private var isProcessingQueue = AtomicBoolean(false)
    private var currentCopyTask: CopyTask? = null
    private var currentCopyJob: Job? = null
    private val copyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // === SharedFlow (перенесено из App.companion) ===
    companion object {
        @Volatile
        var instance: MusicPlaybackService? = null
            private set
        @Volatile
        var isUserExiting = false
        const val CHANNEL_ID = "MusicPlaybackChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_RESCAN_PLAYLIST = "RESCAN_PLAYLIST"
        const val ACTION_UPDATE_SETTINGS = "UPDATE_SETTINGS"
        const val ACTION_SCREEN_WOKE_UP = "SCREEN_WOKE_UP"
        const val ACTION_PLAY_PAUSE = "ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "ACTION_NEXT"
        const val ACTION_PREVIOUS = "ACTION_PREVIOUS"
        const val ACTION_SAVE_CURRENT_STATE = "SAVE_CURRENT_STATE"
        const val ACTION_UPDATE_MUSIC_DIRECTORY = "UPDATE_MUSIC_DIRECTORY"
        const val ACTION_UPDATE_RANDOM_MODE = "UPDATE_RANDOM_MODE"
        const val ACTION_GET_PLAYBACK_STATE = "GET_PLAYBACK_STATE"
        const val ACTION_SWITCH_TO_PLAYLIST = "SWITCH_TO_PLAYLIST"
        const val ACTION_STOP_AND_EXIT = "ACTION_STOP_AND_EXIT"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_PLAY = "ACTION_PLAY"

        // === Копирование: команды ===
        const val ACTION_ADD_COPY_TASK = "ACTION_ADD_COPY_TASK"
        const val ACTION_CANCEL_COPY_TASK = "ACTION_CANCEL_COPY_TASK"
        const val ACTION_GET_ACTIVE_COPY_TASKS = "ACTION_GET_ACTIVE_COPY_TASKS"

        // === SharedFlow ===
        private val _taskUpdates = MutableSharedFlow<CopyTask>(extraBufferCapacity = 64)
        val taskUpdates: SharedFlow<CopyTask> = _taskUpdates.asSharedFlow()
        private val _taskResults = MutableSharedFlow<TaskResult>(extraBufferCapacity = 16)
        val taskResults: SharedFlow<TaskResult> = _taskResults.asSharedFlow()
        private val _queueUpdates = MutableSharedFlow<Int>(extraBufferCapacity = 64)
        val queueUpdates: SharedFlow<Int> = _queueUpdates.asSharedFlow()
        private val _playlistChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val playlistChanges: SharedFlow<Unit> = _playlistChanges.asSharedFlow()
        private val _debugMessages = MutableSharedFlow<String>(extraBufferCapacity = 32)
        val debugMessages: SharedFlow<String> = _debugMessages.asSharedFlow()

        private val mainScope = CoroutineScope(Dispatchers.Main)

        fun notifyPlaylistChanged() {
            mainScope.launch { _playlistChanges.emit(Unit) }
        }

        private fun notifyTaskUpdated(task: CopyTask) {
            if (DebugConfig.isEnabled) Log.d("[CCM]", "[MusicPlaybackService.notifyTaskUpdated] taskId=${task.id}, status=${task.status}")
            mainScope.launch { _taskUpdates.emit(task) }
        }

        private fun notifyQueueUpdated(size: Int) {
            if (DebugConfig.isEnabled) Log.d("[CCM]", "[MusicPlaybackService.notifyQueueUpdated] size=$size")
            mainScope.launch { _queueUpdates.emit(size) }
        }

        private fun sendTaskResult(taskId: String, success: Boolean, sourcePath: String) {
            if (DebugConfig.isEnabled) Log.d("[CCM]", "[MusicPlaybackService.sendTaskResult] In")
            mainScope.launch { _taskResults.emit(TaskResult(taskId, success, sourcePath)) }
        }
    }

    private val fileCheckRunnable = object : Runnable {
        override fun run() {
            if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.fileCheckRunnable] In")
            if (exoPlayer?.isPlaying == true) {
                rebuildPlaylistOrderOnly()
                fileCheckHandler?.postDelayed(this, 30 * 60 * 1000L)
            }
        }
    }

    private val positionSaveRunnable = object : Runnable {
        override fun run() {
            if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.positionSaveRunnable] In")
            if (rememberTrackPosition) {
                saveCurrentState()
            }
            positionSaveHandler?.postDelayed(this, 3_000L)
        }
    }

    interface PlaybackStateListener {
        fun onTrackChanged()
        fun onPlaylistChanged()
    }

    private var isFirstStart = true
    private var isServiceRebuilding = false
    private var stateListener: PlaybackStateListener? = null
    private lateinit var prefs: SharedPreferences
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var allTracks: List<TrackInfo> = emptyList()
    private var currentPlaylist: List<TrackInfo> = emptyList()
    private var folders: List<FolderInfo> = emptyList()
    private var currentPlaylistIndex = 0
    private var currentFolderIndex = 0
    private var currentRandomMode = "none"
    private var currentRandomModeValue: String = "none"
    private var currentMusicDirectory: String = ""
    private var currentTrackFullPath: String = ""
    private var isScanning = false
    private var isPreparingTrack = false
    private var pendingPlayAfterPrepare = false
    private var isRebuildingPlaylist = false
    private var wasStoppedByUser = false
    private var wasPlaying = true
    private var rememberTrackPosition = true
    private var playOnIgnition = true
    private var isAutoPlayEnabled = true
    private var playWhenAppClosed = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.screenReceiver.onReceive] action=${intent?.action}")
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.screenReceiver.onReceive] Screen ON")
                    handleScreenWakeUp()
                }
            }
        }
    }

    private val exoPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onPlaybackStateChanged] state=$playbackState")
            if (isServiceStopping) return
            val player = exoPlayer ?: return
            when (playbackState) {
                Player.STATE_READY -> {
                    startForegroundSafe(createNotification())
//                    saveCurrentState()
                    if (pendingPlayAfterPrepare && !wasStoppedByUser) {
                        pendingPlayAfterPrepare = false
                        player.play()
                    }
                    stateListener?.onTrackChanged()
                }
                Player.STATE_ENDED -> {
                    if (isPreparingTrack || isRebuildingPlaylist) return
                    if (wasStoppedByUser || isServiceStopping) return
                    if (currentRandomMode == "everywhere" && currentPlaylistIndex >= currentPlaylist.size - 1) {
                        currentPlaylistIndex = -1
                        lastKnownRandomMode = ""
                        rebuildPlaylistOrderOnly()
                        if (currentPlaylist.size > 1) {
                            currentPlaylistIndex = 1
                            prepareTrack(1, 0, true)
                        } else if (currentPlaylist.size == 1) {
                            currentPlaylistIndex = 0
                            prepareTrack(0, 0, true)
                        }
                    } else {
                        nextTrack()
                    }
                }
                else -> {}
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onPositionDiscontinuity(reason: Int) {
            saveCurrentState()
            stateListener?.onTrackChanged()
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onReceive] action=${intent?.action}")
            when (intent?.action) {
                ACTION_PLAY_PAUSE -> {
                    val player = exoPlayer ?: return
                    if (player.isPlaying) pause() else play()
                }
                ACTION_NEXT -> nextTrack()
                ACTION_PREVIOUS -> previousTrack()
                ACTION_SCREEN_WOKE_UP -> handleScreenWakeUp()
                ACTION_UPDATE_SETTINGS -> loadSettings()
                ACTION_RESCAN_PLAYLIST -> rebuildPlaylist()
                ACTION_SAVE_CURRENT_STATE -> saveCurrentStateToPlaylist()
                ACTION_SWITCH_TO_PLAYLIST -> {
                    intent.getStringExtra("playlist_id")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { switchToPlaylist(it) }
                }
                ACTION_GET_PLAYBACK_STATE -> {
                    prefs.edit { putBoolean("playback_is_playing", exoPlayer?.isPlaying == true) }
                }
                ACTION_UPDATE_MUSIC_DIRECTORY -> {
                    intent.getStringExtra("music_directory_path")?.let { updateMusicDirectory(it) }
                }
                ACTION_UPDATE_RANDOM_MODE -> {
                    intent.getStringExtra("random_mode")?.let { updateRandomMode(it) }
                }
                "GET_CURRENT_TRACK_INFO" -> {
                    val player = exoPlayer ?: return
                    prefs.edit {
                        putString("current_track_path", currentTrackFullPath)
                        putLong("current_track_position", player.currentPosition)
                        putBoolean("was_playing", player.isPlaying)
                    }
                }
                ACTION_STOP_AND_EXIT -> {
                    if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onReceive] ACTION_STOP_AND_EXIT")
                    wasStoppedByUser = true
                    exoPlayer?.pause()
                    saveCurrentState()
                    saveCurrentStateToPlaylist()
                    exoPlayer?.clearMediaItems()
                    try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                    stopPeriodicPositionSave()
                    stopSelf()
                }
                // === Копирование ===
                ACTION_ADD_COPY_TASK -> {
                    val sourcePath = intent.getStringExtra("source_path") ?: return
                    val destPath = intent.getStringExtra("dest_path") ?: return
                    val destUri = intent.getStringExtra("dest_uri")
                    val typeName = intent.getStringExtra("type") ?: return
                    val name = intent.getStringExtra("name") ?: return
                    val type = CopyTask.TaskType.valueOf(typeName)
                    handleAddCopyTask(sourcePath, destPath, destUri, type, name)
                }
                ACTION_CANCEL_COPY_TASK -> {
                    val taskId = intent.getStringExtra("task_id") ?: return
                    handleCancelCopyTask(taskId)
                }
                ACTION_GET_ACTIVE_COPY_TASKS -> {
                    // Активные задачи отдаются через Flow; при необходимости можно добавить broadcast.
                }
            }
        }
    }

    override fun onCreate() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onCreate] In")
        super.onCreate()
        instance = this

        DebugConfig.init(this)

        Process.setThreadPriority(Process.myPid(), Process.THREAD_PRIORITY_AUDIO)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "meloman::WakeLock"
        ).apply { setReferenceCounted(false) }

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
        playWhenAppClosed = prefs.getBoolean("play_when_app_closed", false)

        try { mediaSession?.release() } catch (_: Exception) {}
        mediaSession = null

        val player: ExoPlayer = exoPlayer ?: ExoPlayer.Builder(this).build().also {
            exoPlayer = it
            if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onCreate] Created NEW ExoPlayer ${System.identityHashCode(it)}")
        }

        val sessionId = generateUniqueSessionId()
        val session = MediaSession.Builder(this, player)
            .setId(sessionId)
            .setCallback(object : MediaSession.Callback {
                @Deprecated("Deprecated in Java")
                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent
                ): Boolean {
                    if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onMediaButtonEvent] Media button disabled")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@MusicPlaybackService, "Function for MelomanPro", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
            })
            .build()

        mediaSession = session

        try { player.removeListener(exoPlayerListener) } catch (_: Exception) {}
        player.addListener(exoPlayerListener)

        createNotificationChannel()
        restorePlaylistState()
        scanMusic()
        loadSettings()
        registerControlReceiver()
        registerScreenReceiver()
        if (rememberTrackPosition) startPeriodicPositionSave()
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onCreate] Service created, session=$sessionId, player=${System.identityHashCode(player)}")
    }

    private fun registerScreenReceiver() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.registerScreenReceiver] In")
        try {
            val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
        } catch (e: Exception) {
            if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.registerScreenReceiver] Error", e)
        }
    }

    private fun unregisterScreenReceiver() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.unregisterScreenReceiver] In")
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
    }

    // === Копирование: обработчики команд ===

    private fun handleAddCopyTask(
        sourcePath: String,
        destPath: String,
        destUri: String?,
        type: CopyTask.TaskType,
        name: String
    ) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.handleAddCopyTask] In, source=$sourcePath, type=$type, name=$name")
        val source = File(sourcePath)
        copyScope.launch {
            val freeSpace = getFreeSpace(destPath)
            val totalSize = calculateTotalSize(source)
            val queueTotalSize = getQueueTotalSize()
            if (freeSpace < totalSize + queueTotalSize) {
                val message = "Недостаточно места. Требуется: ${formatSize(totalSize + queueTotalSize)}, Свободно: ${formatSize(freeSpace)}"
                if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.handleAddCopyTask] Insufficient space: $message")
                sendTaskResult("", false, sourcePath)
                return@launch
            }
            val effectiveDestUri = if (destUri != null) resolveSafDestUri(destPath, destUri) else null
            val task = CopyTask(
                sourcePath = source.absolutePath,
                destPath = destPath,
                destUri = effectiveDestUri,
                type = type,
                name = name,
                totalSize = totalSize
            )
            if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.handleAddCopyTask] Created task: id=${task.id}")
            pendingTasks[task.id] = task
            copyQueue.add(task)
            notifyQueueUpdated(copyQueue.size)
            startProcessingQueue()
        }
    }

    private fun handleCancelCopyTask(taskId: String) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.handleCancelCopyTask] In, taskId=$taskId")
        if (currentCopyTask?.id == taskId) {
            currentCopyJob?.cancel()
            currentCopyTask?.status = CopyTask.TaskStatus.CANCELLED
            cancelledTasks[taskId] = currentCopyTask!!
            notifyTaskUpdated(currentCopyTask!!)
            sendTaskResult(taskId, false, currentCopyTask!!.sourcePath)
            currentCopyTask = null
            return
        }
        val task = copyQueue.find { it.id == taskId }
        if (task != null) {
            copyQueue.remove(task)
            task.status = CopyTask.TaskStatus.CANCELLED
            pendingTasks.remove(taskId)
            cancelledTasks[taskId] = task
            notifyTaskUpdated(task)
            notifyQueueUpdated(copyQueue.size)
            sendTaskResult(taskId, false, task.sourcePath)
        }
    }

    private fun startProcessingQueue() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.startProcessingQueue] In")
        if (isProcessingQueue.compareAndSet(false, true)) {
            processNextCopyTask()
        }
    }

    private fun processNextCopyTask() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.processNextCopyTask] In")
        copyScope.launch {
            val nextTask = copyQueue.poll()
            if (nextTask == null) {
                if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.processNextCopyTask] Queue empty")
                isProcessingQueue.set(false)
                withContext(Dispatchers.Main) { sendPlaylistUpdateCommand() }
                return@launch
            }
            currentCopyTask = nextTask
            pendingTasks[nextTask.id] = nextTask
            nextTask.status = CopyTask.TaskStatus.COPYING
            notifyTaskUpdated(nextTask)
            currentCopyJob = coroutineContext[Job]
            try {
                val success = performCopy(nextTask)
                if (success && nextTask.status != CopyTask.TaskStatus.CANCELLED) {
                    nextTask.status = CopyTask.TaskStatus.COMPLETED
                    notifyTaskUpdated(nextTask)
                    sendTaskResult(nextTask.id, true, nextTask.sourcePath)
                    withContext(Dispatchers.Main) { sendPlaylistUpdateCommand() }
                } else if (nextTask.status != CopyTask.TaskStatus.CANCELLED) {
                    nextTask.status = CopyTask.TaskStatus.ERROR
                    notifyTaskUpdated(nextTask)
                    sendTaskResult(nextTask.id, false, nextTask.sourcePath)
                }
            } catch (e: CancellationException) {
                if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.processNextCopyTask] Cancelled: ${nextTask.name}")
                nextTask.status = CopyTask.TaskStatus.CANCELLED
                notifyTaskUpdated(nextTask)
                sendTaskResult(nextTask.id, false, nextTask.sourcePath)
            } catch (e: Exception) {
                if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.processNextCopyTask] Error: ${e.message}", e)
                nextTask.status = CopyTask.TaskStatus.ERROR
                notifyTaskUpdated(nextTask)
                sendTaskResult(nextTask.id, false, nextTask.sourcePath)
            } finally {
                pendingTasks.remove(nextTask.id)
                currentCopyTask = null
                currentCopyJob = null
                notifyQueueUpdated(copyQueue.size)
                processNextCopyTask()
            }
        }
    }

    private suspend fun performCopy(task: CopyTask): Boolean {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.performCopy] In, taskId=${task.id}")
        return withContext(Dispatchers.IO) {
            try {
                val source = File(task.sourcePath)
                val destFile = File(task.destPath, task.name)
                val destUri = task.destUri?.toUri()
                if (task.type == CopyTask.TaskType.DIRECTORY) {
                    copyDirectory(source, destFile, task, destUri)
                } else {
                    copyFile(source, destFile, destUri)
                }
                true
            } catch (e: Exception) {
                if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.performCopy] Error: ${e.message}", e)
                false
            }
        }
    }

    private fun copyFile(source: File, dest: File, destUri: Uri?) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.copyFile] source=${source.name}, saf=${destUri != null}")
        checkCopyCancellation()
        if (destUri != null) copyFileSaf(source, dest, destUri) else copyFileFile(source, dest)
    }

    private fun copyFileFile(source: File, dest: File) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.copyFileFile] In")
        val parentDir = dest.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
            if (!parentDir.exists()) throw IOException("Cannot create parent directory: ${parentDir.absolutePath}")
        }
        if (dest.exists()) {
            val deleted = dest.delete()
            if (!deleted && dest.exists()) throw IOException("Cannot delete existing file: ${dest.absolutePath}")
        }
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(8192)
                var length: Int
                while (input.read(buffer).also { length = it } > 0) {
                    checkCopyCancellation()
                    output.write(buffer, 0, length)
                }
                output.flush()
            }
        }
    }

    private fun copyFileSaf(source: File, dest: File, destUri: Uri) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.copyFileSaf] In")
        val root = DocumentFile.fromTreeUri(this, destUri)
            ?: throw IOException("Cannot open SAF tree: $destUri")
        val docFile = root.findFile(dest.name)
            ?: root.createFile(guessMimeType(dest.name), dest.name)
            ?: throw IOException("Cannot create SAF file: ${dest.name}")
        val output = contentResolver.openOutputStream(docFile.uri)
            ?: throw IOException("Cannot open output stream: ${docFile.uri}")
        output.use { out ->
            FileInputStream(source).use { input ->
                val buffer = ByteArray(8192)
                var length: Int
                while (input.read(buffer).also { length = it } > 0) {
                    checkCopyCancellation()
                    out.write(buffer, 0, length)
                }
                out.flush()
            }
        }
    }

    private fun copyDirectory(source: File, dest: File, task: CopyTask, destUri: Uri?) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.copyDirectory] source=${source.name}, saf=${destUri != null}")
        if (destUri != null) copyDirectorySaf(source, dest, destUri, task)
        else copyDirectoryFile(source, dest, task)
    }

    private fun copyDirectoryFile(source: File, dest: File, task: CopyTask) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.copyDirectoryFile] In")
        if (!dest.exists()) {
            if (!dest.mkdirs() && !dest.exists()) throw IOException("Cannot create directory: ${dest.absolutePath}")
        }
        val files = source.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                copyDirectoryFile(file, File(dest, file.name), task)
            } else if (isAudioFile(file)) {
                copyFileFile(file, File(dest, file.name))
            }
        }
    }

    private fun copyDirectorySaf(source: File, dest: File, destUri: Uri, task: CopyTask) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.copyDirectorySaf] In")
        val parentDoc = DocumentFile.fromTreeUri(this, destUri)
            ?: throw IOException("Cannot open SAF tree: $destUri")
        val destDoc = parentDoc.findFile(dest.name)
            ?.takeIf { it.isDirectory }
            ?: parentDoc.createDirectory(dest.name)
            ?: throw IOException("Cannot create SAF directory: ${dest.name}")
        val files = source.listFiles() ?: return
        var copiedTotal = task.copiedSize
        for (file in files) {
            checkCopyCancellation()
            if (file.isDirectory) {
                copyDirectorySaf(file, File(dest, file.name), destDoc.uri, task)
            } else if (isAudioFile(file)) {
                val docFile = destDoc.findFile(file.name)
                    ?: destDoc.createFile(guessMimeType(file.name), file.name)
                    ?: throw IOException("Cannot create SAF file: ${file.name}")
                val output = contentResolver.openOutputStream(docFile.uri)
                    ?: throw IOException("Cannot open output: ${docFile.uri}")
                output.use { out ->
                    FileInputStream(file).use { input ->
                        val buffer = ByteArray(8192)
                        var length: Int
                        while (input.read(buffer).also { length = it } > 0) {
                            checkCopyCancellation()
                            out.write(buffer, 0, length)
                        }
                        out.flush()
                    }
                }
                copiedTotal += file.length()
                task.copiedSize = copiedTotal
                task.progress = if (task.totalSize > 0) ((copiedTotal * 100) / task.totalSize).toInt() else 0
                notifyTaskUpdated(task)
            }
        }
    }

    private fun checkCopyCancellation() {
        if (currentCopyJob?.isCancelled == true) {
            throw CancellationException("Task cancelled")
        }
    }

    private suspend fun calculateTotalSize(file: File): Long {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.calculateTotalSize] file=${file.name}")
        return withContext(Dispatchers.IO) {
            if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile && isAudioFile(it) }.sumOf { it.length() }
            } else file.length()
        }
    }

    private suspend fun getFreeSpace(destPath: String): Long {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.getFreeSpace] destPath=$destPath")
        return withContext(Dispatchers.IO) {
            try {
                val stat = StatFs(destPath)
                stat.availableBlocksLong * stat.blockSizeLong
            } catch (e: Exception) {
                Long.MAX_VALUE
            }
        }
    }

    private fun getQueueTotalSize(): Long {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.getQueueTotalSize] In")
        var total = currentCopyTask?.totalSize ?: 0
        total += copyQueue.sumOf { it.totalSize }
        return total
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun guessMimeType(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }

    private fun resolveSafDestUri(destPath: String, destUri: String): String? {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.resolveSafDestUri] In")
        val uri = destUri.toUri()
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            return destUri
        }
        val split = docId.split(":")
        if (split.size < 2) return destUri
        val volume = split[0]
        val rootPath = split.getOrNull(1) ?: ""
        val relative = when {
            volume == "primary" && destPath.startsWith("/storage/emulated/0/") ->
                destPath.removePrefix("/storage/emulated/0/")
            destPath.startsWith("/storage/$volume/") ->
                destPath.removePrefix("/storage/$volume/")
            else -> return destUri
        }
        if (relative == rootPath) return destUri
        val effectiveRelative = when {
            rootPath.isEmpty() -> relative
            relative.startsWith("$rootPath/") -> relative.removePrefix("$rootPath/")
            else -> return destUri
        }
        if (effectiveRelative.isEmpty()) return destUri
        var doc: DocumentFile = DocumentFile.fromTreeUri(this, uri) ?: return destUri
        for (part in effectiveRelative.split("/")) {
            if (part.isEmpty()) continue
            val next: DocumentFile = doc.findFile(part)?.takeIf { it.isDirectory }
                ?: doc.createDirectory(part)
                ?: return destUri
            doc = next
        }
        return doc.uri.toString()
    }

    private fun sendPlaylistUpdateCommand() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.sendPlaylistUpdateCommand] In")
        val intent = Intent(this, MusicPlaybackService::class.java)
        intent.action = ACTION_RESCAN_PLAYLIST
        startService(intent)
    }

    // === Остальная логика сервиса (без изменений, кроме ссылок на App) ===

    private fun startPeriodicPositionSave() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.startPeriodicPositionSave] In")
        if (positionSaveHandler == null) positionSaveHandler = Handler(Looper.getMainLooper())
        positionSaveHandler?.removeCallbacks(positionSaveRunnable)
        positionSaveHandler?.postDelayed(positionSaveRunnable, 3_000L)
    }

    private fun stopPeriodicPositionSave() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.stopPeriodicPositionSave] In")
        positionSaveHandler?.removeCallbacks(positionSaveRunnable)
    }

    private fun generateUniqueSessionId(): String {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.generateUniqueSessionId] In")
        return "${packageName}_${Process.myPid()}_${System.currentTimeMillis()}_${System.nanoTime()}"
    }

    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun registerControlReceiver() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.registerControlReceiver] In")
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREVIOUS)
            addAction(ACTION_SCREEN_WOKE_UP)
            addAction(ACTION_UPDATE_SETTINGS)
            addAction(ACTION_RESCAN_PLAYLIST)
            addAction(ACTION_SAVE_CURRENT_STATE)
            addAction(ACTION_SWITCH_TO_PLAYLIST)
            addAction(ACTION_GET_PLAYBACK_STATE)
            addAction(ACTION_UPDATE_MUSIC_DIRECTORY)
            addAction(ACTION_UPDATE_RANDOM_MODE)
            addAction("GET_CURRENT_TRACK_INFO")
            addAction(ACTION_ADD_COPY_TASK)
            addAction(ACTION_CANCEL_COPY_TASK)
            addAction(ACTION_GET_ACTIVE_COPY_TASKS)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(controlReceiver, filter)
            }
        } catch (e: Exception) {
            if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.registerControlReceiver] Error", e)
        }
    }

    private fun unregisterControlReceiver() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.unregisterControlReceiver] In")
        try { unregisterReceiver(controlReceiver) } catch (_: IllegalArgumentException) {}
    }

    private fun createNotificationChannel() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.createNotificationChannel] In")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Music Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback control"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onStartCommand] action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP_AND_EXIT -> {
                if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onStartCommand] ACTION_STOP_AND_EXIT")
                wasStoppedByUser = true
                exoPlayer?.pause()
                saveCurrentState()
                saveCurrentStateToPlaylist()
                exoPlayer?.clearMediaItems()
                try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                stopPeriodicPositionSave()
                isUserExiting = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PLAY_PAUSE -> {
                if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onStartCommand] ACTION_PLAY_PAUSE")
                if (exoPlayer?.isPlaying == true) pause() else play()
            }
            ACTION_PAUSE -> {
                if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onStartCommand] ACTION_PAUSE")
                pause()
                return START_NOT_STICKY
            }
            ACTION_PLAY -> play()
            ACTION_ADD_COPY_TASK -> {
                if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onStartCommand] ACTION_ADD_COPY_TASK")
                val sourcePath = intent.getStringExtra("source_path")
                val destPath = intent.getStringExtra("dest_path")
                val destUri = intent.getStringExtra("dest_uri")
                val typeName = intent.getStringExtra("type")
                val name = intent.getStringExtra("name")
                if (sourcePath != null && destPath != null && typeName != null && name != null) {
                    val type = CopyTask.TaskType.valueOf(typeName)
                    handleAddCopyTask(sourcePath, destPath, destUri, type, name)
                } else {
                    if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onStartCommand] ACTION_ADD_COPY_TASK: missing extras")
                }
            }
            ACTION_CANCEL_COPY_TASK -> {
                if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onStartCommand] ACTION_CANCEL_COPY_TASK")
                val taskId = intent.getStringExtra("task_id")
                if (taskId != null) {
                    handleCancelCopyTask(taskId)
                }
            }
        }

        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_RESCAN_PLAYLIST -> { forceRebuildOnNextRescan = true; rebuildPlaylist() }
            ACTION_SWITCH_TO_PLAYLIST -> intent.getStringExtra("playlist_id")
                ?.takeIf { it.isNotEmpty() }?.let { switchToPlaylist(it) }
            ACTION_UPDATE_RANDOM_MODE -> intent.getStringExtra("random_mode")?.let { updateRandomMode(it) }
            ACTION_UPDATE_MUSIC_DIRECTORY -> intent.getStringExtra("music_directory_path")?.let { updateMusicDirectory(it) }
        }
        if ((exoPlayer?.isPlaying == true) && wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L)
        }
        return START_NOT_STICKY
    }

    private fun tryStartScan(): Boolean {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.tryStartScan] In")
        return synchronized(isScanningLock) {
            if (isScanning) return false
            isScanning = true
            true
        }
    }

    private fun endScan() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.endScan] In")
        synchronized(isScanningLock) { isScanning = false }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onTaskRemoved] playWhenAppClosed=$playWhenAppClosed")
        super.onTaskRemoved(rootIntent)
        if (!playWhenAppClosed) {
            pause()
            exoPlayer?.clearMediaItems()
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onDestroy] playWhenAppClosed=$playWhenAppClosed, isServiceStopping=$isServiceStopping")
        instance = null
        stopPeriodicPositionSave()
        serviceScope.cancel()
        copyScope.cancel()
        unregisterControlReceiver()
        unregisterScreenReceiver()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        try { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) } catch (_: Exception) {}
        try { mediaSession?.release() } catch (_: Exception) {}
        mediaSession = null
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefsChangeListener) } catch (_: Exception) {}
        // Освобождаем плеер всегда, если это явный выход (isServiceStopping) или playWhenAppClosed=false
        if (isServiceStopping || !playWhenAppClosed) {
            try { exoPlayer?.removeListener(exoPlayerListener) } catch (_: Exception) {}
            try { exoPlayer?.release() } catch (_: Exception) {}
            exoPlayer = null
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.onGetSession] In")
        return mediaSession
    }

    private fun loadSettings() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.loadSettings] In")
        rememberTrackPosition = prefs.getBoolean("remember_track_position", false)
        playOnIgnition = prefs.getBoolean("play_on_ignition", false)
        isAutoPlayEnabled = prefs.getBoolean("auto_play", false)
        playWhenAppClosed = prefs.getBoolean("play_when_app_closed", false)
        val playlistManager = PlaylistManager.getInstance(this)
        currentRandomMode = playlistManager.getCurrentRandomMode()
        currentRandomModeValue = currentRandomMode
    }

    fun scanMusic() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.scanMusic] In")
        if (!tryStartScan()) return
        val musicPath = prefs.getString("selected_folder_path", "/storage/emulated/0/Music")
        val musicDir = File(musicPath!!)
        Thread {
            try {
                val allTracksList = scanDirectory(musicDir)
                val blacklist = loadBlacklistForScan()
                Handler(Looper.getMainLooper()).post { buildPlaylist(allTracksList, blacklist, true) }
            } finally {
                Handler(Looper.getMainLooper()).post { endScan() }
            }
        }.start()
    }

    fun rebuildPlaylist() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.rebuildPlaylist] In")
        if (!tryStartScan()) return
        if (isRebuildingPlaylist) return
        val freshMusicDirectory = prefs.getString("selected_folder_path", "/storage/emulated/0/Music") ?: "/storage/emulated/0/Music"
        val musicDirChanged = currentMusicDirectory != freshMusicDirectory
        val playlistManager = PlaylistManager.getInstance(this)
        val freshRandomMode = playlistManager.getCurrentRandomMode()
        val randomModeChanged = currentRandomMode != freshRandomMode
        val force = forceRebuildOnNextRescan
        forceRebuildOnNextRescan = false

        if (!force && !isServiceRebuilding && !musicDirChanged && !randomModeChanged && (exoPlayer?.isPlaying == true) && currentPlaylist.isNotEmpty()) return
        if (musicDirChanged) currentMusicDirectory = freshMusicDirectory
        isRebuildingPlaylist = true
        isServiceRebuilding = true
        if (currentRandomMode != freshRandomMode) currentRandomMode = freshRandomMode

        val musicPath = prefs.getString("selected_folder_path", "/storage/emulated/0/Music")
        val musicDir = File(musicPath!!)
        Thread {
            try {
                val allTracksList = scanDirectory(musicDir)
                val blacklist = loadBlacklistForScan()
                Handler(Looper.getMainLooper()).post {
                    buildPlaylist(allTracksList, blacklist, false)
                    isRebuildingPlaylist = false
                    isServiceRebuilding = false
                    Handler(Looper.getMainLooper()).postDelayed({ stateListener?.onPlaylistChanged() }, 200)
                }
            } finally {
                Handler(Looper.getMainLooper()).post { endScan() }
            }
        }.start()
    }

    private fun rebuildPlaylistOrderOnly() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.rebuildPlaylistOrderOnly] In")
        if (allTracks.isEmpty() && folders.isEmpty()) { rebuildPlaylist(); return }
        val sourceTracks = if (currentRandomMode == "everywhere") allTracks else folders.flatMap { it.tracks }
        val (existingSource, removedCount) = filterExistingTracks(sourceTracks)
        val currentCount = existingSource.size
        val modeChanged = lastKnownRandomMode != currentRandomMode
        val countChanged = lastKnownTrackCount != currentCount

        if (removedCount > 0) {
            allTracks = existingSource
            val grouped = existingSource.groupBy { it.folderPath }
            folders = grouped.map { (path, tracks) ->
                val sorted = tracks.sortedBy { it.file?.name?.lowercase() ?: "" }
                FolderInfo(File(path), sorted.size, sorted.toMutableList())
            }.sortedBy { it.folder.absolutePath }
            lastKnownTrackCount = currentCount
            lastKnownRandomMode = currentRandomMode
            rebuildPlaylist()
            return
        }
        if (!modeChanged && !countChanged) return

        if (countChanged) {
            val grouped = existingSource.groupBy { it.folderPath }
            val mergedFolders = grouped.map { (path, tracks) ->
                val sorted = tracks.sortedBy { it.file?.name?.lowercase() ?: "" }
                FolderInfo(File(path), sorted.size, sorted.toMutableList())
            }.sortedBy { it.folder.absolutePath }
            val existingPaths = allTracks.map { it.filePath }.toSet()
            val newTracks = mergedFolders.flatMap { it.tracks }.filter { it.filePath !in existingPaths }
            if (newTracks.isNotEmpty()) {
                allTracks = allTracks + newTracks
                folders = mergedFolders
            }
        }
        val oldTrackPath = getCurrentTrack()?.trackId ?: currentTrackFullPath
        val newPlaylist = buildOrderedPlaylist(currentRandomMode, oldTrackPath)
        if (newPlaylist.isEmpty()) { rebuildPlaylist(); return }
        currentPlaylist = newPlaylist
        val newIndex = currentPlaylist.indexOfFirst { it.trackId == oldTrackPath }
        if (newIndex >= 0) {
            currentPlaylistIndex = newIndex
            currentTrackFullPath = oldTrackPath
        } else {
            currentPlaylistIndex = 0
            if (currentPlaylist.isNotEmpty()) prepareTrack(0, 0, wasPlaying && !wasStoppedByUser)
        }
        lastKnownTrackCount = currentPlaylist.size
        lastKnownRandomMode = currentRandomMode
        savePlaylistState()
        stateListener?.onPlaylistChanged()
        stateListener?.onTrackChanged()
    }

    private fun buildOrderedPlaylist(mode: String, currentTrackPath: String): List<TrackInfo> {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.buildOrderedPlaylist] In")
        return when (mode) {
            "in_folder" -> buildList {
                for (folder in folders) {
                    val tracks = folder.tracks.toMutableList()
                    val curInFolder = tracks.indexOfFirst { it.trackId == currentTrackPath }
                    if (curInFolder > 0) {
                        val cur = tracks.removeAt(curInFolder)
                        tracks.shuffle()
                        tracks.add(0, cur)
                    } else if (curInFolder == -1) tracks.shuffle()
                    addAll(tracks)
                }
            }
            "everywhere" -> buildList {
                val tracks = allTracks.toMutableList()
                val cur = tracks.indexOfFirst { it.trackId == currentTrackPath }
                if (cur >= 0) {
                    val current = tracks.removeAt(cur)
                    tracks.shuffle()
                    add(current)
                    addAll(tracks)
                } else {
                    tracks.shuffle()
                    addAll(tracks)
                }
            }
            else -> buildList { for (folder in folders) addAll(folder.tracks) }
        }
    }

    private fun switchToPlaylist(playlistId: String) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.switchToPlaylist] In")
        val playlistManager = PlaylistManager.getInstance(this)
        val newPlaylist = playlistManager.getPlaylist(playlistId) ?: return
        val wasPlayingBefore = exoPlayer?.isPlaying == true
        val wasPosition = if (rememberTrackPosition) (exoPlayer?.currentPosition ?: 0L) else 0L

        if (wasPlayingBefore) pause() else { saveCurrentState(); saveCurrentStateToPlaylist() }
        wasStoppedByUser = false
        wasPlaying = wasPlayingBefore

        val newLast = playlistManager.getLastTrackFromPlaylist(playlistId)
        playlistManager.switchToPlaylist(
            playlistId = playlistId,
            currentTrackPath = currentTrackFullPath,
            currentPosition = wasPosition,
            updateLastUsed = wasPlayingBefore
        )
        val newMusicPath = newPlaylist.settings.musicDirectoryPath
        prefs.edit {
            putString("selected_folder_path", newMusicPath)
            putString("selected_folder_uri", newPlaylist.settings.musicDirectoryUri)
            putString("saved_track_path", newLast.first)
            putLong("saved_position", newLast.second)
            putBoolean("was_playing", wasPlayingBefore)
        }
        currentMusicDirectory = newMusicPath
        currentRandomMode = newPlaylist.settings.randomMode
        currentRandomModeValue = currentRandomMode
        currentPlaylist = emptyList()
        currentPlaylistIndex = 0
        currentTrackFullPath = newLast.first
        isScanning = false
        scanMusic()

        Handler(Looper.getMainLooper()).postDelayed({
            if (currentPlaylist.isEmpty()) {
                wasStoppedByUser = true
                wasPlaying = false
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
                saveCurrentState()
                saveCurrentStateToPlaylist()
                stateListener?.onTrackChanged()
                stateListener?.onPlaylistChanged()
                return@postDelayed
            }
            if (wasPlayingBefore) {
                wasStoppedByUser = false
                exoPlayer?.play()
                startPeriodicFileCheck()
            } else {
                wasStoppedByUser = true
                exoPlayer?.pause()
            }
            stateListener?.onPlaylistChanged()
            stateListener?.onTrackChanged()
        }, 700)
    }

    private fun scanDirectory(dir: File): List<TrackInfo> {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.scanDirectory] In")
        val tracks = mutableListOf<TrackInfo>()
        val blacklist = loadBlacklistForScan()
        fun scan(d: File) {
            if (isBlacklisted(d.absolutePath, blacklist)) return
            val files = d.listFiles() ?: return
            for (file in files.sortedBy { it.name.lowercase() }) {
                if (file.isDirectory) scan(file)
                else if (isAudioFile(file) && !isBlacklisted(file.absolutePath, blacklist)) {
                    tracks.add(TrackInfo(file, d.absolutePath, d.name))
                }
            }
        }
        scan(dir)
        return tracks
    }

    private fun isAudioFile(file: File): Boolean {
        return file.extension.lowercase() == "mp3"
    }

    private fun isBlacklisted(path: String, blacklist: List<BlacklistItem>): Boolean {
        return blacklist.any { path == it.path || path.startsWith("${it.path}/") }
    }

    private fun loadBlacklistForScan(): List<BlacklistItem> {
        val playlistManager = PlaylistManager.getInstance(this)
        return playlistManager.getCurrentPlaylist()?.settings?.blacklistItems ?: emptyList()
    }

    fun addToBlacklist(path: String, type: BlacklistItem.ItemType, name: String) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.addToBlacklist] In")
        val playlistManager = PlaylistManager.getInstance(this)
        val current = playlistManager.getCurrentPlaylist() ?: return
        val blacklist = current.settings.blacklistItems
        if (blacklist.none { it.path == path }) {
            blacklist.add(BlacklistItem(path, type, name))
            current.updateLastModified()
            playlistManager.savePlaylists()
            when (type) {
                BlacklistItem.ItemType.FILE -> nextTrack()
                BlacklistItem.ItemType.DIRECTORY -> nextFolder()
            }
            rebuildPlaylist()
        }
    }

    fun buildPlaylist(allTracksList: List<TrackInfo>, blacklist: List<BlacklistItem>, isInitialStart: Boolean) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.buildPlaylist] In")
        val randomMode = currentRandomMode
        val filteredTracks = allTracksList.filter { track ->
            val filePath = track.file?.absolutePath ?: ""
            !isBlacklisted(filePath, blacklist) && !isBlacklisted(track.folderPath, blacklist)
        }
        allTracks = filteredTracks
        val folderMap = mutableMapOf<String, MutableList<TrackInfo>>()
        for (track in allTracks) folderMap.getOrPut(track.folderPath) { mutableListOf() }.add(track)
        val sortedFolderPaths = folderMap.keys.sorted()
        val tempFolders = mutableListOf<FolderInfo>()
        for (folderPath in sortedFolderPaths) {
            val tracks = folderMap[folderPath] ?: emptyList()
            val sortedTracks = tracks.sortedBy { it.file?.name?.lowercase() ?: "" }
            tempFolders.add(FolderInfo(File(folderPath), sortedTracks.size, sortedTracks.toMutableList()))
        }
        folders = tempFolders

        val oldTrackPath = if (currentPlaylistIndex < currentPlaylist.size) currentPlaylist[currentPlaylistIndex].trackId else currentTrackFullPath
        val oldPosition = exoPlayer?.currentPosition ?: 0L
        val wasPlayingNow = (exoPlayer?.isPlaying == true) && !wasStoppedByUser

        val newPlaylist = when (randomMode) {
            "in_folder" -> buildList {
                for (folder in folders) addAll(folder.tracks.toMutableList().apply { shuffle() })
            }
            "everywhere" -> allTracks.shuffled()
            else -> buildList { for (folder in folders) addAll(folder.tracks) }
        }
        currentPlaylist = newPlaylist

        val newIndex = currentPlaylist.indexOfFirst { it.trackId == oldTrackPath }
        if (isInitialStart || newIndex < 0) {
            if (isInitialStart && currentTrackFullPath.isNotEmpty() && currentPlaylist.isNotEmpty()) {
                val currentPlayingPath = getCurrentTrack()?.trackId ?: ""
                val isPlayingNow = exoPlayer?.isPlaying == true
                if (currentPlayingPath == currentTrackFullPath && isPlayingNow) {
                    val savedNewIndex = currentPlaylist.indexOfFirst { it.trackId == currentPlayingPath }
                    if (savedNewIndex >= 0) currentPlaylistIndex = savedNewIndex
                    stateListener?.onTrackChanged()
                } else {
                    val savedIndex = currentPlaylist.indexOfFirst { it.trackId == currentTrackFullPath }
                    if (savedIndex >= 0) {
                        currentPlaylistIndex = savedIndex
                        val pm = PlaylistManager.getInstance(this)
                        val savedPosition = pm.getCurrentPlaylist()?.settings?.currentPosition ?: 0L
                        val savedWasPlaying = isAutoPlayEnabled || (prefs.getBoolean("was_playing", false) && !wasStoppedByUser)
                        prepareTrack(currentPlaylistIndex, savedPosition, savedWasPlaying)
                    } else {
                        currentPlaylistIndex = 0
                        if (currentPlaylist.isNotEmpty()) prepareTrack(0, 0, false)
                    }
                }
            } else if (newIndex < 0 && oldTrackPath.isNotEmpty() && currentPlaylist.isNotEmpty()) {
                currentPlaylistIndex = 0
                prepareTrack(0, 0, wasPlayingNow)
            }
        } else {
            currentPlaylistIndex = newIndex
            if (wasPlayingNow) exoPlayer?.seekTo(oldPosition)
        }
        savePlaylistState()
        lastKnownTrackCount = currentPlaylist.size
        lastKnownRandomMode = currentRandomMode
    }

    private fun savePlaylistState() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.savePlaylistState] In")
        val currentTrackPath = if (currentPlaylistIndex < currentPlaylist.size) currentPlaylist[currentPlaylistIndex].trackId else currentTrackFullPath
        currentTrackFullPath = currentTrackPath
        prefs.edit { putString("saved_track_path", currentTrackPath) }
    }

    private fun saveCurrentStateToPlaylist() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.saveCurrentStateToPlaylist] In, rememberTrackPosition = $rememberTrackPosition")
        val playlistManager = PlaylistManager.getInstance(this)
        val musicPath = prefs.getString("selected_folder_path", "/storage/emulated/0/Music") ?: ""
        val blacklist = loadBlacklistForScan()
        val position = if (rememberTrackPosition) (exoPlayer?.currentPosition ?: 0L) else 0L
        playlistManager.saveCurrentStateToPlaylist(
            musicDirectoryPath = musicPath,
            musicDirectoryUri = prefs.getString("selected_folder_uri", null),
            randomMode = currentRandomMode,
            blacklistItems = blacklist,
            currentTrackPath = currentTrackFullPath,
            currentPosition = position,
            isPlaying = exoPlayer?.isPlaying == true,
            wasStoppedByUser = wasStoppedByUser
        )
    }

    fun restorePlaylistState() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.restorePlaylistState] In")
        val playlistManager = PlaylistManager.getInstance(this)
        val playlist = playlistManager.getCurrentPlaylist() ?: return
        currentRandomMode = playlist.settings.randomMode
        currentRandomModeValue = currentRandomMode
        currentTrackFullPath = playlist.settings.currentTrackPath
        currentPlaylistIndex = 0
        currentPlaylist = emptyList()
        wasStoppedByUser = false
        isAutoPlayEnabled = prefs.getBoolean("auto_play", false)
    }

    private fun filterExistingTracks(tracks: List<TrackInfo>): Pair<List<TrackInfo>, Int> {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.filterExistingTracks] In")
        val (exists, missing) = tracks.partition { it.file?.exists() == true }
        return exists to missing.size
    }

    private fun handleScreenWakeUp() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.handleScreenWakeUp] In")
        if (playOnIgnition && isAutoPlayEnabled && !wasStoppedByUser && currentPlaylist.isNotEmpty()) {
            wakeUpActivityIfNeeded()
            play()
        }
    }

    private fun wakeUpActivityIfNeeded() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.wakeUpActivityIfNeeded] In")
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.wakeUpActivityIfNeeded] Error", e)
        }
    }

    fun nextTrack() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.nextTrack] In")
        val player = exoPlayer ?: return
        if (isPreparingTrack || isRebuildingPlaylist || currentPlaylist.isEmpty()) return
        isPreparingTrack = true
        pendingPlayAfterPrepare = false
        val newIndex = if (currentPlaylistIndex + 1 < currentPlaylist.size) currentPlaylistIndex + 1 else 0
        if (newIndex == currentPlaylistIndex && currentPlaylist.size == 1) { isPreparingTrack = false; return }
        currentPlaylistIndex = newIndex
        val shouldPlay = if (wasStoppedByUser) false else wasPlaying
        prepareTrack(currentPlaylistIndex, 0, shouldPlay)
        updateCurrentFolderFromPlaylist()
        saveCurrentState()
        stateListener?.onTrackChanged()
        player.hashCode()
    }

    fun previousTrack() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.previousTrack] In")
        if (exoPlayer == null) return
        if (isPreparingTrack || isRebuildingPlaylist || currentPlaylist.isEmpty()) return
        isPreparingTrack = true
        pendingPlayAfterPrepare = false
        currentPlaylistIndex = if (currentPlaylistIndex > 0) currentPlaylistIndex - 1 else currentPlaylist.size - 1
        val shouldPlay = if (wasStoppedByUser) false else wasPlaying
        prepareTrack(currentPlaylistIndex, 0, shouldPlay)
        updateCurrentFolderFromPlaylist()
        saveCurrentState()
        stateListener?.onTrackChanged()
    }

    fun nextFolder() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.nextFolder] In")
        if (exoPlayer == null || isRebuildingPlaylist || currentPlaylist.isEmpty()) return
        val currentFolderPath = getCurrentTrack()?.folderPath
        if (currentRandomMode == "everywhere") {
            var nextIndex = currentPlaylistIndex + 1
            while (nextIndex < currentPlaylist.size) {
                if (currentPlaylist[nextIndex].folderPath != currentFolderPath) {
                    currentPlaylistIndex = nextIndex
                    val shouldPlay = if (wasStoppedByUser) false else (exoPlayer?.isPlaying == true)
                    prepareTrack(currentPlaylistIndex, 0, shouldPlay)
                    updateCurrentFolderFromPlaylist()
                    saveCurrentState()
                    stateListener?.onTrackChanged()
                    return
                }
                nextIndex++
            }
            currentPlaylistIndex = 0
        } else {
            val currentFolderIndexInFolders = getCurrentFolderIndexInFolders()
            val newFolderIndex = if (currentFolderIndexInFolders + 1 < folders.size) currentFolderIndexInFolders + 1 else 0
            val targetFolderPath = folders[newFolderIndex].folder.absolutePath
            val newIndex = currentPlaylist.indexOfFirst { it.folderPath == targetFolderPath }
            if (newIndex >= 0) currentPlaylistIndex = newIndex else return
        }
        val shouldPlay = if (wasStoppedByUser) false else (exoPlayer?.isPlaying == true)
        prepareTrack(currentPlaylistIndex, 0, shouldPlay)
        updateCurrentFolderFromPlaylist()
        saveCurrentState()
        stateListener?.onTrackChanged()
    }

    fun previousFolder() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.previousFolder] In")
        if (exoPlayer == null || isRebuildingPlaylist || currentPlaylist.isEmpty()) return
        val currentFolderPath = getCurrentTrack()?.folderPath
        if (currentRandomMode == "everywhere") {
            var prevIndex = currentPlaylistIndex - 1
            while (prevIndex >= 0) {
                if (currentPlaylist[prevIndex].folderPath != currentFolderPath) {
                    currentPlaylistIndex = prevIndex
                    val shouldPlay = if (wasStoppedByUser) false else (exoPlayer?.isPlaying == true)
                    prepareTrack(currentPlaylistIndex, 0, shouldPlay)
                    updateCurrentFolderFromPlaylist()
                    saveCurrentState()
                    stateListener?.onTrackChanged()
                    return
                }
                prevIndex--
            }
            currentPlaylistIndex = currentPlaylist.size - 1
        } else {
            val currentFolderIndexInFolders = getCurrentFolderIndexInFolders()
            val newFolderIndex = if (currentFolderIndexInFolders > 0) currentFolderIndexInFolders - 1 else folders.size - 1
            val targetFolderPath = folders[newFolderIndex].folder.absolutePath
            val newIndex = currentPlaylist.indexOfFirst { it.folderPath == targetFolderPath }
            if (newIndex >= 0) currentPlaylistIndex = newIndex else return
        }
        val shouldPlay = if (wasStoppedByUser) false else (exoPlayer?.isPlaying == true)
        prepareTrack(currentPlaylistIndex, 0, shouldPlay)
        updateCurrentFolderFromPlaylist()
        saveCurrentState()
        stateListener?.onTrackChanged()
    }

    private fun updateCurrentFolderFromPlaylist() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.updateCurrentFolderFromPlaylist] In")
        val track = getCurrentTrack() ?: return
        currentFolderIndex = folders.indexOfFirst { it.folder.absolutePath == track.folderPath }.coerceAtLeast(0)
    }

    private fun getCurrentFolderIndexInFolders(): Int {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.getCurrentFolderIndexInFolders] In")
        val track = getCurrentTrack() ?: return 0
        return folders.indexOfFirst { it.folder.absolutePath == track.folderPath }.coerceAtLeast(0)
    }

    private fun prepareTrack(index: Int, position: Long = 0, autoPlay: Boolean = false) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.prepareTrack] In, index=$index, position=$position, autoPlay=$autoPlay")
        val player = exoPlayer ?: return
        if (currentPlaylist.isEmpty()) { isPreparingTrack = false; return }
        if (isFirstStart) wasStoppedByUser = false
        try {
            if (index < currentPlaylist.size) {
                val track = currentPlaylist[index]
                if (track.file == null) { isPreparingTrack = false; return }
                currentTrackFullPath = track.trackId
                if (track.file?.exists() == true) {
                    player.setMediaItem(MediaItem.fromUri(track.file!!.absolutePath))
                    player.prepare()
                    if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.prepareTrack] seekTo($position)")
                    if (position > 0) player.seekTo(position)
                    if (autoPlay && !wasStoppedByUser) player.play() else player.pause()
                    updateNotification()
                } else {
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isRebuildingPlaylist) rebuildPlaylist()
                    }, 300)
                }
            }
        } catch (e: Exception) {
            if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.prepareTrack] Error", e)
        } finally {
            isPreparingTrack = false
            isFirstStart = false
        }
    }

    fun getCurrentTrack(): TrackInfo? {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.getCurrentTrack] In")
        return try { if (currentPlaylistIndex < currentPlaylist.size) currentPlaylist[currentPlaylistIndex] else null }
        catch (_: Exception) { null }
    }

    fun getCurrentTrackNumberInFolder(): Int {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.getCurrentTrackNumberInFolder] In")
        val track = getCurrentTrack() ?: return 0
        val folder = folders.find { it.folder.absolutePath == track.folderPath } ?: return 0
        return folder.tracks.indexOfFirst { it.file?.absolutePath == track.file?.absolutePath } + 1
    }

    fun getCurrentFolderTracksCount(): Int {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.getCurrentFolderTracksCount] In")
        val track = getCurrentTrack() ?: return 0
        return folders.find { it.folder.absolutePath == track.folderPath }?.trackCount ?: 0
    }

    fun getCurrentFolderDisplay(): Pair<String, Int> {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.getCurrentFolderDisplay] In")
        return if (currentRandomMode == "everywhere") {
            Pair(getCurrentTrack()?.folderName ?: "Unknown", 0)
        } else {
            val folderIndex = getCurrentFolderIndexInFolders()
            val folder = if (folderIndex < folders.size) folders[folderIndex] else null
            Pair(folder?.folder?.name ?: "Unknown", folders.size)
        }
    }

    fun getCurrentFolderIndex(): Int {
        return getCurrentFolderIndexInFolders()
    }
    fun getTotalFoldersCount(): Int {
        return folders.size
    }
    fun getTotalTracksCount(): Int {
        return allTracks.size
    }
    fun getCurrentPosition(): Long {
        return try { exoPlayer?.currentPosition ?: 0L } catch (_: Exception) { 0L }
    }
    fun getDuration(): Long {
        return try { exoPlayer?.duration ?: 0L } catch (_: Exception) { 0L }
    }
    fun isPlaying(): Boolean {
        return try { exoPlayer?.isPlaying ?: false } catch (_: Exception) { false }
    }

    fun play() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.play] In")
        val player = exoPlayer ?: return
        wasStoppedByUser = false
        wasPlaying = true

        if (player.mediaItemCount == 0 || player.currentMediaItem == null) {
            if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.play] Player empty, restoring track")
            if (currentPlaylist.isNotEmpty() && currentPlaylistIndex in currentPlaylist.indices) {
                // Плейлист в памяти есть — просто готовим текущий трек
                prepareTrack(currentPlaylistIndex, 0, true)
            } else {
                // Плейлиста нет (перезапуск сервиса) — пересканируем и играем
                if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.play] Playlist empty, rebuilding")
                rebuildAndPlay()
            }
            saveCurrentState()
            startPeriodicFileCheck()
            return
        }
        when (player.playbackState) {
            Player.STATE_ENDED, Player.STATE_IDLE -> {
                if (currentPlaylist.isNotEmpty() && currentPlaylistIndex < currentPlaylist.size) {
                    prepareTrack(currentPlaylistIndex, 0, true)
                }
            }
            Player.STATE_READY -> player.play()
            else -> pendingPlayAfterPrepare = true
        }
        if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
        saveCurrentState()
        startPeriodicFileCheck()
    }
    private fun rebuildAndPlay() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.rebuildAndPlay] In")
        val musicPath = prefs.getString("selected_folder_path", "/storage/emulated/0/Music") ?: "/storage/emulated/0/Music"
        Thread {
            try {
                val allTracksList = scanDirectory(File(musicPath))
                val blacklist = loadBlacklistForScan()
                Handler(Looper.getMainLooper()).post {
                    buildPlaylist(allTracksList, blacklist, false)
                    if (currentPlaylist.isNotEmpty()) {
                        prepareTrack(currentPlaylistIndex.coerceIn(0, currentPlaylist.size - 1), 0, true)
                        saveCurrentState()
                        startPeriodicFileCheck()
                    } else {
                        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.rebuildAndPlay] Playlist still empty")
                        wasStoppedByUser = true
                    }
                }
            } catch (e: Exception) {
                if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.rebuildAndPlay] Error", e)
                Handler(Looper.getMainLooper()).post { wasStoppedByUser = true }
            }
        }.start()
    }
    fun pause() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.pause] In")
        wasStoppedByUser = true
        exoPlayer?.pause()
        saveCurrentState()
        saveCurrentStateToPlaylist()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopPeriodicFileCheck()
    }

    fun stop() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.stop] In")
        val player = exoPlayer
        wasStoppedByUser = true
        player?.stop()
        player?.seekTo(0)
        saveCurrentState()
        saveCurrentStateToPlaylist()
        updateNotification()
        player?.clearMediaItems()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopPeriodicFileCheck()
    }

    private fun startPeriodicFileCheck() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.startPeriodicFileCheck] In")
        if (fileCheckHandler == null) fileCheckHandler = Handler(Looper.getMainLooper())
        fileCheckHandler?.removeCallbacks(fileCheckRunnable)
        fileCheckHandler?.postDelayed(fileCheckRunnable, 30 * 60 * 1000L)
    }

    private fun stopPeriodicFileCheck() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.stopPeriodicFileCheck] In")
        fileCheckHandler?.removeCallbacks(fileCheckRunnable)
    }

    private fun saveCurrentState() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.saveCurrentState] In, rememberTrackPosition = $rememberTrackPosition")
        val position = if (rememberTrackPosition) (exoPlayer?.currentPosition ?: 0L) else 0L
        val pm = PlaylistManager.getInstance(this)
        val playlist = pm.getCurrentPlaylist()
        if (playlist != null) {
            val changed = playlist.settings.currentTrackPath != currentTrackFullPath ||
                    playlist.settings.currentPosition != position
            if (changed) {
                playlist.settings.currentTrackPath = currentTrackFullPath
                if (!rememberTrackPosition) {
                    playlist.settings.currentPosition = position
                }
                playlist.updateLastModified()
                pm.savePlaylists()
            }
        }
    }

    private fun createNotification(): Notification {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.createNotification] In")
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val track = getCurrentTrack()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(track?.file?.nameWithoutExtension ?: "No track")
            .setContentText(track?.folderName ?: "")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundSafe(notification: Notification) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.startForegroundSafe] In")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.startForegroundSafe] Error", e)
        }
    }

    private fun updateNotification() {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.updateNotification] In")
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val notification = createNotification()
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
                startForegroundSafe(notification)
            } catch (e: Exception) {
                if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.updateNotification] Error", e)
            }
        }, 100)
    }

    fun updateMusicDirectory(newPath: String) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.updateMusicDirectory] In")
        if (currentMusicDirectory != newPath) {
            currentMusicDirectory = newPath
            prefs.edit { putString("selected_folder_path", newPath) }
            rebuildPlaylist()
        }
    }

    fun updateRandomMode(newMode: String) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.updateRandomMode] In")
        val changed = currentRandomMode != newMode
        currentRandomMode = newMode
        currentRandomModeValue = newMode
        PlaylistManager.getInstance(this).saveRandomModeToCurrentPlaylist(newMode)
        if (changed) {
            lastKnownRandomMode = ""
            rebuildPlaylistOrderOnly()
        }
    }

    fun getCurrentFolderTracksForDialog(): List<Pair<String, Int>> {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.getCurrentFolderTracksForDialog] In")
        val current = getCurrentTrack() ?: return emptyList()
        val folderPath = current.folderPath
        return currentPlaylist.mapIndexedNotNull { index, track ->
            if (track.folderPath == folderPath) {
                val name = track.file?.nameWithoutExtension ?: track.filePath.substringAfterLast("/")
                name to index
            } else null
        }
    }

    fun playAtIndex(index: Int) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.playAtIndex] In")
        if (index < 0 || index >= currentPlaylist.size) return
        currentPlaylistIndex = index
        val shouldPlay = !wasStoppedByUser && (exoPlayer?.isPlaying == true || wasPlaying)
        prepareTrack(index, 0, shouldPlay)
        updateCurrentFolderFromPlaylist()
        saveCurrentState()
        stateListener?.onTrackChanged()
    }

    fun getFoldersForDialog(): List<Triple<String, Int, String>> {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.getFoldersForDialog] In")
        return folders.mapIndexed { idx, fi -> Triple(fi.folder.name, idx, fi.folder.absolutePath) }
    }

    fun playFolderAtIndex(folderIndex: Int) {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.playFolderAtIndex] In")
        if (folderIndex < 0 || folderIndex >= folders.size) return
        val targetFolder = folders[folderIndex].folder.absolutePath
        val newIndex = if (currentRandomMode == "everywhere") {
            val size = currentPlaylist.size
            if (size == 0) return
            var found = -1
            for (offset in 0 until size) {
                val idx = (currentPlaylistIndex + offset) % size
                if (currentPlaylist[idx].folderPath == targetFolder) { found = idx; break }
            }
            found
        } else currentPlaylist.indexOfFirst { it.folderPath == targetFolder }
        if (newIndex >= 0) {
            currentPlaylistIndex = newIndex
            val shouldPlay = !wasStoppedByUser && (exoPlayer?.isPlaying == true || wasPlaying)
            prepareTrack(newIndex, 0, shouldPlay)
            updateCurrentFolderFromPlaylist()
            saveCurrentState()
            stateListener?.onTrackChanged()
        }
    }

    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.prefsChangeListener] key=$key")
        when (key) {
            "remember_track_position" -> {
                rememberTrackPosition = prefs.getBoolean("remember_track_position", false)
                if (rememberTrackPosition) startPeriodicPositionSave() else stopPeriodicPositionSave()
            }
            "play_on_ignition" -> playOnIgnition = prefs.getBoolean("play_on_ignition", false)
            "auto_play" -> isAutoPlayEnabled = prefs.getBoolean("auto_play", false)
            "play_when_app_closed" -> playWhenAppClosed = prefs.getBoolean("play_when_app_closed", false)
            "debug_log" -> {
                val enabled = prefs.getBoolean("debug_log", false)
                DebugConfig.setEnabled(this, enabled)
            }
        }
    }

    fun getCurrentPlaylistIndex(): Int {
        if (debugLog) Log.d("[CCM]", "[MusicPlaybackService.getCurrentPlaylistIndex] In")
        return currentPlaylistIndex
    }
}