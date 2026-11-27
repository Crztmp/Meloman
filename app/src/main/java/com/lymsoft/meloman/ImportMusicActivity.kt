package com.lymsoft.meloman

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

@androidx.media3.common.util.UnstableApi
class ImportMusicActivity : AppCompatActivity() {

    private val debugLog: Boolean
        get() = DebugConfig.isEnabled

    private lateinit var container: LinearLayout
    private lateinit var itemsList: MutableList<File>

    private val importButtons = mutableMapOf<String, Button>()
    private val cancelButtons = mutableMapOf<String, Button>()
    private val progressBars = mutableMapOf<String, ProgressBar>()
    private val statusTexts = mutableMapOf<String, TextView>()

    private val existingFoldersCache = mutableSetOf<String>()
    private val existingFilesCache = mutableSetOf<String>()
    private val taskIdMap = mutableMapOf<String, String>()

    private var musicStoragePath: String = ""
    private var musicFolderPath: String = ""
    private var musicFolderUri: Uri? = null

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pendingSafFolderPath: String? = null
    private var pendingImportItem: File? = null
    private var writePermissionCheckedForPath: String? = null
    private var requestInProgress = false

    private val safPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = pendingSafFolderPath
        pendingSafFolderPath = null
        if (result.resultCode != RESULT_OK || path == null) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            if (debugLog) Log.d("[CCM]", "[ImportMusicActivity.safPickerLauncher] persist error", e)
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit {
            putString("selected_folder_uri", uri.toString())
        }
        musicFolderUri = uri
        writePermissionCheckedForPath = path
        Toast.makeText(this, "Доступ получен, повторите импорт", Toast.LENGTH_LONG).show()
    }

    private val sdCardAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        requestInProgress = false
        if (result.resultCode != RESULT_OK || result.data?.data == null) {
            pendingImportItem = null
            Toast.makeText(this, "Доступ к SD не предоставлен", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        val rootUri = result.data!!.data!!
        try {
            contentResolver.takePersistableUriPermission(
                rootUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            if (debugLog) Log.d("[CCM]", "[ImportMusicActivity.sdCardAccessLauncher] persist error", e)
        }
        val rootDoc = DocumentFile.fromTreeUri(this, rootUri)
        if (rootDoc == null) {
            pendingImportItem = null
            Toast.makeText(this, "Не удалось открыть SD", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        val volumeId = "CE65-41BB"
        val relative = if (musicFolderPath.startsWith("/storage/$volumeId/")) {
            musicFolderPath.removePrefix("/storage/$volumeId/")
        } else ""
        var target: DocumentFile = rootDoc
        if (relative.isNotEmpty()) {
            for (part in relative.split("/")) {
                if (part.isEmpty()) continue
                val next: DocumentFile = target.findFile(part)?.takeIf { it.isDirectory }
                    ?: target.createDirectory(part)
                    ?: run {
                        pendingImportItem = null
                        Toast.makeText(this, "Каталог $relative не найден на SD", Toast.LENGTH_LONG).show()
                        return@registerForActivityResult
                    }
                target = next
            }
        }
        val targetUri = target.uri
        PreferenceManager.getDefaultSharedPreferences(this).edit {
            putString("selected_folder_uri", targetUri.toString())
            putString("sd_card_uri", targetUri.toString())
        }
        musicFolderUri = targetUri
        writePermissionCheckedForPath = musicFolderPath
        pendingImportItem?.let { addToQueue(it) }
        pendingImportItem = null
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Доступ к файлам получен, повторите импорт", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Доступ к файлам не предоставлен", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val writePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "Разрешение на запись получено, повторите импорт", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Без разрешения на запись импорт невозможен", Toast.LENGTH_LONG).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) scanStorage()
        else {
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (debugLog) Log.d("[CCM]", "[ImportMusicActivity.onCreate] In")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_music)
        val contentView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = getString(R.string.import_music_title)

        container = findViewById(R.id.import_container)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        musicStoragePath = prefs.getString("music_storage_path", "") ?: ""
        musicFolderPath = prefs.getString("selected_folder_path", "/storage/emulated/0/Music") ?: "/storage/emulated/0/Music"
        musicFolderUri = prefs.getString("selected_folder_uri", null)?.toUri()

        showCurrentSettings()

        if (musicStoragePath.isEmpty()) {
            Toast.makeText(this, getString(R.string.import_select_storage_first), Toast.LENGTH_LONG).show()
            finish(); return
        }
        if (musicStoragePath == musicFolderPath) {
            Toast.makeText(this, getString(R.string.import_cannot_match_storage_and_music), Toast.LENGTH_LONG).show()
            finish(); return
        }
        if (isNested(musicStoragePath, musicFolderPath)) {
            Toast.makeText(this, getString(R.string.import_cannot_be_nested, musicStoragePath, musicFolderPath), Toast.LENGTH_LONG).show()
            finish(); return
        }
        if (isNested(musicFolderPath, musicStoragePath)) {
            Toast.makeText(this, getString(R.string.import_cannot_be_nested_reverse, musicFolderPath, musicStoragePath), Toast.LENGTH_LONG).show()
            finish(); return
        }

        if (musicFolderUri == null && musicFolderPath.startsWith("/storage/") && !musicFolderPath.startsWith("/storage/emulated/0")) {
            requestSdCardAccess()
        } else {
            checkPermissionsAndScan()
        }

        observeTaskUpdates()
        observeQueueUpdates()
        observeTaskResults()
    }

    private fun observeTaskUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MusicPlaybackService.taskUpdates.collect { task ->
                    updateTaskUI(task)
                }
            }
        }
    }

    private fun observeQueueUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MusicPlaybackService.queueUpdates.collect { size ->
                    updateQueueInfo(size)
                }
            }
        }
    }

    private fun observeTaskResults() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MusicPlaybackService.taskResults.collect { result ->
                    handleTaskResult(result.taskId, result.success, result.sourcePath)
                }
            }
        }
    }

    private fun handleTaskResult(taskId: String, success: Boolean, sourcePath: String) {
        if (debugLog) Log.d("[CCM]", "[ImportMusicActivity.handleTaskResult] In, taskId=$taskId, success=$success")
        val item = File(sourcePath)
        runOnUiThread {
            taskIdMap.remove(sourcePath)
            loadExistingItemsCache()
            val importBtn = importButtons[sourcePath]
            val cancelBtn = cancelButtons[sourcePath]
            val progressBar = progressBars[sourcePath]
            val statusText = statusTexts[sourcePath]
            cancelBtn?.isEnabled = true
            val newText = getImportButtonText(item)
            importBtn?.text = newText
            importBtn?.let { updateImportButtonColor(it, newText) }
            importBtn?.visibility = View.VISIBLE
            cancelBtn?.visibility = View.GONE
            progressBar?.visibility = View.GONE
            statusText?.visibility = View.GONE
            if (success) {
                Toast.makeText(this, "Завершено: ${item.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Ошибка при копировании: ${item.name}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateTaskUI(task: CopyTask) {
        val sourceKey = task.sourcePath
        when (task.status) {
            CopyTask.TaskStatus.PENDING -> {
                updateItemStatus(sourceKey, "В очереди", true)
                importButtons[sourceKey]?.visibility = View.GONE
            }
            CopyTask.TaskStatus.COPYING -> {
                updateItemStatus(sourceKey, "Копирование: ${task.progress}%", true, task.progress)
                importButtons[sourceKey]?.visibility = View.GONE
            }
            CopyTask.TaskStatus.COMPLETED -> {
                mainScope.launch {
                    delay(2000)
                    if (taskIdMap.containsKey(sourceKey)) {
                        runOnUiThread { restoreButtonUi(File(sourceKey)) }
                        taskIdMap.remove(sourceKey)
                    }
                }
            }
            CopyTask.TaskStatus.CANCELLED, CopyTask.TaskStatus.ERROR -> { /* ждём taskResults */ }
        }
    }

    private fun updateQueueInfo(queueSize: Int) {
        supportActionBar?.subtitle = if (queueSize > 0) "В очереди: $queueSize" else null
    }

    private fun updateItemStatus(sourceKey: String, status: String, isActive: Boolean, progress: Int = 0) {
        runOnUiThread {
            val cancelBtn = cancelButtons[sourceKey]
            val progressBar = progressBars[sourceKey]
            val statusText = statusTexts[sourceKey]
            if (isActive) {
                cancelBtn?.visibility = View.VISIBLE
                progressBar?.visibility = View.VISIBLE
                statusText?.text = status
                statusText?.visibility = View.VISIBLE
                if (progress > 0) progressBar?.progress = progress
            } else {
                cancelBtn?.visibility = View.GONE
                progressBar?.visibility = View.GONE
                statusText?.visibility = View.GONE
            }
        }
    }

    private fun restoreButtonUi(item: File) {
        runOnUiThread {
            val sourceKey = item.absolutePath
            loadExistingItemsCache()
            val importBtn = importButtons[sourceKey]
            val cancelBtn = cancelButtons[sourceKey]
            val progressBar = progressBars[sourceKey]
            val statusText = statusTexts[sourceKey]
            val newText = getImportButtonText(item)
            importBtn?.text = newText
            importBtn?.let { updateImportButtonColor(it, newText) }
            importBtn?.visibility = View.VISIBLE
            cancelBtn?.visibility = View.GONE
            progressBar?.visibility = View.GONE
            statusText?.visibility = View.GONE
        }
    }

    private fun loadExistingItemsCache() {
        existingFoldersCache.clear()
        existingFilesCache.clear()
        try {
            val folderUri = musicFolderUri
            if (folderUri != null) {
                val root = DocumentFile.fromTreeUri(this, folderUri)
                if (root != null && root.exists()) {
                    val targetDoc = resolveDocumentForPath(root, musicFolderPath)
                    targetDoc.listFiles().forEach { docFile ->
                        if (docFile.isDirectory) existingFoldersCache.add(docFile.name ?: "")
                    }
                }
            } else {
                val destFolder = File(musicFolderPath)
                if (destFolder.exists() && destFolder.isDirectory) {
                    destFolder.listFiles()?.forEach { file ->
                        if (file.isDirectory) existingFoldersCache.add(file.name)
                    }
                }
            }
        } catch (e: Exception) {
            if (debugLog) Log.d("[CCM]", "[ImportMusicActivity.loadExistingItemsCache] Error", e)
        }
    }

    private fun resolveDocumentForPath(root: DocumentFile, destPath: String): DocumentFile {
        val uri = root.uri
        val docId = try { DocumentsContract.getTreeDocumentId(uri) } catch (_: Exception) { return root }
        val split = docId.split(":")
        if (split.size < 2) return root
        val volume = split[0]
        val rootPath = split.getOrNull(1) ?: ""
        val relative = when {
            volume == "primary" && destPath.startsWith("/storage/emulated/0/") ->
                destPath.removePrefix("/storage/emulated/0/")
            destPath.startsWith("/storage/$volume/") -> destPath.removePrefix("/storage/$volume/")
            else -> return root
        }
        if (relative == rootPath) return root
        val effective = if (rootPath.isEmpty()) relative
        else if (relative.startsWith("$rootPath/")) relative.removePrefix("$rootPath/")
        else return root
        var doc: DocumentFile = root
        for (part in effective.split("/")) {
            if (part.isEmpty()) continue
            doc = doc.findFile(part)?.takeIf { it.isDirectory } ?: return root
        }
        return doc
    }

    private fun isFolderExistsInDestination(folderName: String) = existingFoldersCache.contains(folderName)
    private fun isFileExistsInDestination(fileName: String) = existingFilesCache.contains(fileName)

    private fun getImportButtonText(item: File): String {
        return if (item.isDirectory) {
            if (isFolderExistsInDestination(item.name)) getString(R.string.import_button_update)
            else getString(R.string.import_button_import)
        } else {
            if (isFileExistsInDestination(item.name)) getString(R.string.import_button_update)
            else getString(R.string.import_button_import)
        }
    }

    private fun updateImportButtonColor(button: Button, buttonText: String) {
        val isUpdate = buttonText == getString(R.string.import_button_update) || buttonText == "Update"
        button.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (isUpdate) android.R.color.holo_blue_dark else android.R.color.holo_green_dark
        )
    }

    private fun isNested(parent: String, child: String): Boolean =
        child.startsWith(parent + File.separator) || child == parent

    private fun showCurrentSettings() { /* логирование */ }

    private fun requestSdCardAccess() {
        val sm = getSystemService(STORAGE_SERVICE) as? StorageManager
        val sdCard = sm?.storageVolumes?.firstOrNull { it.isRemovable }
        if (sdCard == null) {
            requestInProgress = false
            pendingImportItem = null
            Toast.makeText(this, "SD-карта не найдена", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                sdCard.directory?.let { putExtra("android.provider.extra.INITIAL_URI", it.toUri()) }
            }
        }
        sdCardAccessLauncher.launch(intent)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun checkPermissionsAndScan() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO))
                } else scanStorage()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (!Environment.isExternalStorageManager()) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = "package:$packageName".toUri()
                    manageStorageLauncher.launch(intent)
                } else scanStorage()
            }
            else -> {
                val perms = mutableListOf<String>()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
                else scanStorage()
            }
        }
    }

    private fun scanStorage() {
        container.removeAllViews()
        importButtons.clear(); cancelButtons.clear(); progressBars.clear(); statusTexts.clear(); taskIdMap.clear()
        val storageFile = File(musicStoragePath)
        if (!storageFile.exists() || !storageFile.canRead()) {
            runOnUiThread {
                Toast.makeText(this, getString(R.string.import_storage_not_found_path, musicStoragePath), Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }
        Thread {
            val items = mutableListOf<File>()
            storageFile.listFiles()?.forEach { if (it.isDirectory) items.add(it) }
            items.sortBy { it.name.lowercase() }
            runOnUiThread { displayItems(items) }
        }.start()
        loadExistingItemsCache()
    }

    private fun displayItems(items: MutableList<File>) {
        container.removeAllViews()
        itemsList = items
        if (itemsList.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "Нет папок для импорта"
                textSize = 16f
                setPadding(16, 32, 16, 32)
                gravity = android.view.Gravity.CENTER
            }
            container.addView(emptyText)
            return
        }
        for (item in itemsList) {
            val itemView = layoutInflater.inflate(R.layout.item_import, container, false)
            val tvName = itemView.findViewById<TextView>(R.id.tv_import_item)
            val btnImport = itemView.findViewById<Button>(R.id.btn_import)
            val btnCancel = itemView.findViewById<Button>(R.id.btn_cancel)
            val progressBar = itemView.findViewById<ProgressBar>(R.id.progress_bar)
            val statusContainer = itemView.findViewById<LinearLayout>(R.id.import_item_container)
            val tvStatus = TextView(this).apply {
                text = ""; textSize = 10f; visibility = View.GONE
            }
            statusContainer?.addView(tvStatus, 1)
            statusTexts[item.absolutePath] = tvStatus
            tvName.text = getString(R.string.import_item_folder_name, item.name)
            importButtons[item.absolutePath] = btnImport
            cancelButtons[item.absolutePath] = btnCancel
            progressBars[item.absolutePath] = progressBar
            val buttonText = getImportButtonText(item)
            btnImport.text = buttonText
            updateImportButtonColor(btnImport, buttonText)
            btnImport.visibility = View.VISIBLE
            btnCancel.visibility = View.GONE
            progressBar.visibility = View.GONE
            btnImport.setOnClickListener { startImportWithPermission(item) }
            btnCancel.setOnClickListener { cancelTaskForItem(item) }
            container.addView(itemView)
        }
        updateActiveTasks()
    }

    private fun startImportWithPermission(item: File) {
        if (requestInProgress) return
        requestInProgress = true
        if (writePermissionCheckedForPath == musicFolderPath && hasWriteAccess()) {
            requestInProgress = false; addToQueue(item); return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager() && !hasUsableSafUriFor(musicFolderPath)) {
                pendingImportItem = item; showRequestManageStorageDialog(musicFolderPath); return
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED && !hasUsableSafUriFor(musicFolderPath)) {
                pendingImportItem = item; showRequestWritePermissionDialog(musicFolderPath); return
            }
        }
        if (hasUsableSafUriFor(musicFolderPath)) {
            writePermissionCheckedForPath = musicFolderPath; requestInProgress = false; addToQueue(item); return
        }
        if (canWriteTo(musicFolderPath)) {
            writePermissionCheckedForPath = musicFolderPath; requestInProgress = false; addToQueue(item); return
        }
        if (!canWriteTo(musicFolderPath) && !hasUsableSafUriFor(musicFolderPath)) {
            pendingImportItem = item
            openSafPickerForCurrentFolder(musicFolderPath)
            return
        }
        pendingImportItem = item; requestSdCardAccess()
    }

    private fun updateActiveTasks() {
        // Запрашиваем активные задачи через сервис — они придут через Flow taskUpdates.
        // Для совместимости: просто ничего не делаем, т.к. taskUpdates уже подписан.
    }

    private fun addToQueue(item: File) {
        val sourceKey = item.absolutePath
        if (taskIdMap.containsKey(sourceKey)) {
            Toast.makeText(this, "${item.name} уже в очереди", Toast.LENGTH_SHORT).show(); return
        }
        if (!hasWriteAccess()) {
            Toast.makeText(this, "Нет доступа на запись в $musicFolderPath", Toast.LENGTH_LONG).show(); return
        }
        val destUriForTask = musicFolderUri?.toString()
        val intent = Intent(this, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_ADD_COPY_TASK
            putExtra("source_path", item.absolutePath)
            putExtra("dest_path", musicFolderPath)
            putExtra("dest_uri", destUriForTask)
            putExtra("type", if (item.isDirectory) CopyTask.TaskType.DIRECTORY.name else CopyTask.TaskType.FILE.name)
            putExtra("name", item.name)
        }
        startService(intent)
        runOnUiThread {
            importButtons[sourceKey]?.visibility = View.GONE
            cancelButtons[sourceKey]?.visibility = View.VISIBLE
            progressBars[sourceKey]?.visibility = View.VISIBLE
            statusTexts[sourceKey]?.text = "Добавлено в очередь"
            statusTexts[sourceKey]?.visibility = View.VISIBLE
        }
    }

    private fun hasWriteAccess(): Boolean =
        hasUsableSafUriFor(musicFolderPath) || canWriteTo(musicFolderPath)

    private fun hasUsableSafUriFor(path: String): Boolean {
        val uriStr = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("selected_folder_uri", null) ?: return false
        val uri = try {
            uriStr.toUri() } catch (_: Exception) { return false }
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            try {
                val doc = DocumentFile.fromTreeUri(this, uri)
                doc != null && doc.exists()
            } catch (_: Exception) { false }
        }
    }

    private fun openSafPickerForCurrentFolder(path: String) {
        pendingSafFolderPath = path
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra("android.provider.extra.INITIAL_URI", path.toUri())
        }
        safPickerLauncher.launch(intent)
    }

    private fun canWriteTo(path: String): Boolean {
        return try {
            val dir = File(path)
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return false
            val test = File(dir, ".meloman_write_test_${System.currentTimeMillis()}")
            val created = try { test.createNewFile() } catch (_: Exception) { false }
            if (created) test.delete()
            created
        } catch (_: Exception) { false }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun showRequestManageStorageDialog(path: String) {
        AlertDialog.Builder(this)
            .setTitle("Нужен доступ к файлам")
            .setMessage("Для копирования в «$path» нужно разрешение «Управление всеми файлами». Открыть настройки?")
            .setPositiveButton("Открыть настройки") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = "package:$packageName".toUri()
                    }
                    manageStorageLauncher.launch(intent)
                } catch (_: Exception) {
                    manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun showRequestWritePermissionDialog(path: String) {
        AlertDialog.Builder(this)
            .setTitle("Нужно разрешение на запись")
            .setMessage("Для копирования в «$path» нужно разрешение на запись.")
            .setPositiveButton("Разрешить") { _, _ ->
                writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun cancelTaskForItem(item: File) {
        val sourceKey = item.absolutePath
        val taskId = taskIdMap[sourceKey]
        if (taskId != null) {
            val intent = Intent(this, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_CANCEL_COPY_TASK
                putExtra("task_id", taskId)
            }
            startService(intent)
            runOnUiThread {
                cancelButtons[sourceKey]?.isEnabled = false
                statusTexts[sourceKey]?.text = "Отмена..."
            }
        } else {
            restoreButtonUi(item)
        }
    }

    private fun runOnUiThread(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        Handler(Looper.getMainLooper()).post(action)
    }

    override fun onResume() { super.onResume(); requestInProgress = false }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }
}