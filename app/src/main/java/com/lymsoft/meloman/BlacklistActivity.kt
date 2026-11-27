package com.lymsoft.meloman

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import androidx.core.net.toUri
import android.provider.DocumentsContract
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

@OptIn(UnstableApi::class)
class BlacklistActivity : AppCompatActivity() {

    private val debugLog: Boolean
        get() = DebugConfig.isEnabled

    private lateinit var playlistManager: PlaylistManager
    private var currentPlaylist: Playlist? = null
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        if (debugLog) Log.d("[CCM]", "[BlacklistActivity.onCreate] In")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blacklist)

        val contentView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.blacklist_title)

        playlistManager = PlaylistManager.getInstance(this)
        currentPlaylist = playlistManager.getCurrentPlaylist()
        container = findViewById(R.id.blacklist_container)

        refreshBlacklist()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MusicPlaybackService.playlistChanges.collect {
                    currentPlaylist = playlistManager.getCurrentPlaylist()
                    refreshBlacklist()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (debugLog) Log.d("[CCM]", "[BlacklistActivity.onOptionsItemSelected] In, itemId=${item.itemId}")
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun getBlacklistItems(): MutableList<BlacklistItem> {
        if (debugLog) Log.d("[CCM]", "[BlacklistActivity.getBlacklistItems] In")
        return currentPlaylist?.settings?.blacklistItems ?: mutableListOf()
    }

    private fun saveBlacklistItems(items: MutableList<BlacklistItem>) {
        if (debugLog) Log.d("[CCM]", "[BlacklistActivity.saveBlacklistItems] In, items.size=${items.size}")
        currentPlaylist?.let { playlist ->
            playlist.settings.blacklistItems = items
            playlist.updateLastModified()
            playlistManager.savePlaylists()
        }
    }

    private fun rebuildPlaylist() {
        if (debugLog) Log.d("[CCM]", "[BlacklistActivity.rebuildPlaylist] In")
        val intent = Intent(this, MusicPlaybackService::class.java)
        intent.action = MusicPlaybackService.ACTION_RESCAN_PLAYLIST
        startService(intent)
    }

    private fun refreshBlacklist() {
        if (debugLog) Log.d("[CCM]", "[BlacklistActivity.refreshBlacklist] In")
        container.removeAllViews()
        val items = getBlacklistItems()

        if (items.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = getString(R.string.blacklist_empty)
                textSize = 16f
                setPadding(16, 32, 16, 32)
                gravity = android.view.Gravity.CENTER
            }
            container.addView(emptyText)
            return
        }

        items.forEachIndexed { index, item ->
            val itemView = layoutInflater.inflate(R.layout.item_blacklist, container, false)
            val tvName = itemView.findViewById<TextView>(R.id.tv_blacklist_item)
            val btnRemove = itemView.findViewById<Button>(R.id.btn_remove)
            val btnDelete = itemView.findViewById<Button>(R.id.btn_delete)

            tvName.text = item.getDisplayName()

            btnRemove.setOnClickListener {
                removeFromBlacklist(index)
                rebuildPlaylist()
            }

            btnDelete.setOnClickListener {
                deleteFileOrDirectory(item, index)
            }

            container.addView(itemView)
        }
    }

    private fun removeFromBlacklist(index: Int) {
        if (debugLog) Log.d("[CCM]", "[BlacklistActivity.removeFromBlacklist] In, index=$index")
        val items = getBlacklistItems().toMutableList()
        val removed = items.removeAt(index)
        saveBlacklistItems(items)
        refreshBlacklist()
        Toast.makeText(this, getString(R.string.remove_from_blacklist, removed.name), Toast.LENGTH_SHORT).show()
    }

    private fun deleteFileOrDirectory(item: BlacklistItem, index: Int) {
        val file = File(item.path)
        when (item.type) {
            BlacklistItem.ItemType.FILE -> {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.confirm_deletion_title))
                    .setMessage(getString(R.string.confirm_delete_file, item.name))
                    .setPositiveButton(getString(R.string.delete_action)) { _, _ ->
                        if (deleteFileSaf(item.path)) {
                            Toast.makeText(this, getString(R.string.file_deleted, item.name), Toast.LENGTH_SHORT).show()
                            removeFromBlacklist(index)
                        } else {
                            Toast.makeText(this, getString(R.string.delete_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel_action), null)
                    .show()
            }
            BlacklistItem.ItemType.DIRECTORY -> {
                val fileCount = countFilesInDirectory(file)
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.confirm_deletion_title))
                    .setMessage(getString(R.string.confirm_delete_folder, item.name, fileCount))
                    .setPositiveButton(getString(R.string.delete_action)) { _, _ ->
                        if (deleteDirectorySaf(item.path)) {
                            Toast.makeText(this, getString(R.string.folder_deleted, item.name), Toast.LENGTH_SHORT).show()
                            removeFromBlacklist(index)
                        } else {
                            Toast.makeText(this, getString(R.string.delete_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(getString(R.string.cancel_action), null)
                    .show()
            }
        }
    }

    private fun deleteFileSaf(path: String): Boolean {
        if (debugLog) Log.d("[CCM]", "[BlacklistActivity.deleteFileSaf] In")
        val direct = File(path)
        if (direct.exists() && direct.delete()) return true
        return deleteDocumentByPath(path)
    }

    private fun deleteDirectorySaf(path: String): Boolean {
        if (debugLog) Log.d("[CCM]", "[BlacklistActivity.deleteDirectorySaf] In")
        val direct = File(path)
        if (direct.exists() && deleteRecursively(direct)) return true
        return deleteDocumentByPath(path)
    }

    private fun resolveDocumentForPath(destPath: String): DocumentFile? {
        if (debugLog) Log.d("[CCM]", "[BlacklistActivity.resolveDocumentForPath] In")
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val uriStr = prefs.getString("selected_folder_uri", null) ?: return null
        val treeUri = try { uriStr.toUri() } catch (_: Exception) { return null }
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return null

        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) { return null }
        val split = docId.split(":")
        if (split.size < 2) return null
        val volume = split[0]
        val rootPath = split.getOrNull(1) ?: ""

        val relative = when {
            volume == "primary" && destPath.startsWith("/storage/emulated/0/") ->
                destPath.removePrefix("/storage/emulated/0/")
            destPath.startsWith("/storage/$volume/") ->
                destPath.removePrefix("/storage/$volume/")
            else -> return null
        }
        val effective = when {
            rootPath.isEmpty() -> relative
            relative.startsWith("$rootPath/") -> relative.removePrefix("$rootPath/")
            else -> return null
        }
        if (effective.isEmpty()) return root

        var doc: DocumentFile = root
        for (part in effective.split("/")) {
            if (part.isEmpty()) continue
            doc = doc.findFile(part) ?: return null
        }
        return doc
    }

    private fun countFilesInDirectory(dir: File): Int {
        if (debugLog) Log.d("[CCM]", "[BlacklistActivity.countFilesInDirectory] In, dir=${dir.absolutePath}")
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                count += countFilesInDirectory(file)
            } else if (file.isFile) {
                count++
            }
        }
        return count
    }

    private fun deleteDocumentByPath(destPath: String): Boolean {
        val doc = resolveDocumentForPath(destPath) ?: return false
        return try {
            DocumentsContract.deleteDocument(contentResolver, doc.uri)
        } catch (e: Exception) {
            Log.w("[CCM]", "[BlacklistActivity] deleteDocument error: ${e.message}")
            false
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        if (debugLog) Log.d("[CCM]", "[BlacklistActivity.deleteRecursively] In, file=${file.absolutePath}")
        return if (file.isDirectory) {
            file.listFiles()?.forEach { child -> deleteRecursively(child) }
            file.delete()
        } else {
            file.delete()
        }
    }
}