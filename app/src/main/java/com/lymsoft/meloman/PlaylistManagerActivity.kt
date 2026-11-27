package com.lymsoft.meloman

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.media3.common.util.UnstableApi
import androidx.core.content.edit
import androidx.recyclerview.widget.DiffUtil
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

@UnstableApi
class PlaylistManagerActivity : AppCompatActivity() {

    private val debugLog: Boolean
        get() = DebugConfig.isEnabled
    private lateinit var playlistManager: PlaylistManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PlaylistAdapter
    private lateinit var emptyView: TextView
    private var currentSortCriterion: PlaylistManager.SortCriterion = PlaylistManager.SortCriterion.BY_LAST_USED

    override fun onCreate(savedInstanceState: Bundle?) {
        if (debugLog) android.util.Log.d("[CCM]", "[PlaylistManagerActivity.onCreate] In")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_manager)
        val contentView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.playlists_title)

        playlistManager = PlaylistManager.getInstance(this)

        recyclerView = findViewById(R.id.recyclerViewPlaylists)
        emptyView = findViewById(R.id.textEmpty)

        setupRecyclerView()
        setupFab()
        loadPlaylists()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MusicPlaybackService.playlistChanges.collect {
                    loadPlaylists()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (debugLog) android.util.Log.d("[CCM]", "[PlaylistManagerActivity.onResume] In")
        loadPlaylists()
    }

    private fun setupRecyclerView() {
        if (debugLog) android.util.Log.d("[CCM]", "[PlaylistManagerActivity.setupRecyclerView] In")
        adapter = PlaylistAdapter(
            onItemClick = { playlist -> showSwitchDialog(playlist) },
            onEditClick = { playlist -> showEditDialog(playlist) },
            onDeleteClick = { playlist -> showDeleteDialog(playlist) },
            onFolderClick  = { playlist -> showFolderPickerForPlaylist(playlist) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun showSwitchDialog(playlist: Playlist) {
        if (debugLog) android.util.Log.d("[CCM]", "[PlaylistManagerActivity.showSwitchDialog] playlist=${playlist.name}")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.playlist_switch_title))
            .setMessage(getString(R.string.playlist_switch_message, playlist.name))
            .setPositiveButton(getString(R.string.switch_action)) { _, _ -> switchToPlaylist(playlist.id) }
            .setNegativeButton(getString(R.string.cancel_action), null)
            .show()
    }

    private fun switchToPlaylist(playlistId: String) {
        if (debugLog) android.util.Log.d("[CCM]", "[PlaylistManagerActivity.switchToPlaylist] In, playlistId=$playlistId")
        try {
            val playlistManager = PlaylistManager.getInstance(this)
            val newPlaylist = playlistManager.getPlaylist(playlistId)

            if (newPlaylist == null) {
                Toast.makeText(this, "Playlist not found", Toast.LENGTH_SHORT).show()
                return
            }

            val oldPlaylist = playlistManager.getCurrentPlaylist()
            if (oldPlaylist != null && oldPlaylist.id != playlistId) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                val currentMusicPath = prefs.getString("selected_folder_path", "/storage/emulated/0/Music") ?: "/storage/emulated/0/Music"
                val currentMusicUri = prefs.getString("selected_folder_uri", null)
                val currentRandomMode = prefs.getString("random_mode", "none") ?: "none"
                val currentTrackPath = prefs.getString("saved_track_path", "") ?: ""
                val currentPosition = prefs.getLong("saved_position", 0)
                val isPlaying = prefs.getBoolean("was_playing", false)
                val wasStoppedByUser = prefs.getBoolean("was_stopped_by_user", true)

                playlistManager.saveCurrentStateToPlaylist(
                    musicDirectoryPath = currentMusicPath,
                    musicDirectoryUri = currentMusicUri,
                    randomMode = currentRandomMode,
                    blacklistItems = oldPlaylist.settings.blacklistItems,
                    currentTrackPath = currentTrackPath,
                    currentPosition = currentPosition,
                    isPlaying = isPlaying,
                    wasStoppedByUser = wasStoppedByUser
                )
            }

            val settings = newPlaylist.settings
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val newMusicPath = settings.musicDirectoryPath.ifEmpty { "/storage/emulated/0/Music"}
            val newMusicUri = settings.musicDirectoryUri
            val newRandomMode = settings.randomMode .ifEmpty {"none"}

            prefs.edit {
                putString("selected_folder_path", newMusicPath)
                putString("selected_folder_uri", newMusicUri)
                putString("random_mode", newRandomMode)
                putString("saved_track_path", settings.currentTrackPath )
                putLong("saved_position", settings.currentPosition)
            }

            playlistManager.switchToPlaylist(playlistId, settings.currentTrackPath, settings.currentPosition, true)

            val intent = Intent(this, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_SWITCH_TO_PLAYLIST
                putExtra("playlist_id", playlistId)
            }
            startService(intent)

            MusicPlaybackService.notifyPlaylistChanged()

            Toast.makeText(this,
                getString(R.string.playlist_switch_success, newPlaylist.name),
                Toast.LENGTH_SHORT).show()

            loadPlaylists()

        } catch (e: Exception) {
            if (debugLog) android.util.Log.d("[CCM]", "[PlaylistManagerActivity.switchToPlaylist] Error", e)
            Toast.makeText(this, "Error switching playlist: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupFab() {
        if (debugLog) android.util.Log.d("[CCM]", "[PlaylistManagerActivity.setupFab] In")
        val fab = findViewById<FloatingActionButton>(R.id.fabAddPlaylist)
        fab.setOnClickListener {
            showCreateDialog()
        }
    }
    private var pendingFolderPlaylistId: String? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            pendingFolderPlaylistId = null
            return@registerForActivityResult
        }
        val uri = result.data?.data ?: run {
            pendingFolderPlaylistId = null
            return@registerForActivityResult
        }
        val playlistId = pendingFolderPlaylistId ?: return@registerForActivityResult
        pendingFolderPlaylistId = null

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            if (debugLog) android.util.Log.d("[CCM]", "[PlaylistManagerActivity] persist permission error", e)
        }

        val realPath = getRealPathFromUri(uri)
        if (realPath.isNullOrEmpty()) {
            Toast.makeText(this, "Не удалось получить путь к папке", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }

        val pm = PlaylistManager.getInstance(this)
        pm.updatePlaylistMusicDirectory(playlistId, realPath, uri.toString())

        val currentId = pm.getCurrentPlaylist()?.id
        if (currentId == playlistId) {
            PreferenceManager.getDefaultSharedPreferences(this).edit {
                putString("selected_folder_path", realPath)
                putString("selected_folder_uri", uri.toString())
            }
            val intent = Intent(this, MusicPlaybackService::class.java).apply {
                action = MusicPlaybackService.ACTION_UPDATE_MUSIC_DIRECTORY
                putExtra("music_directory_path", realPath)
            }
            startService(intent)
        }

        Toast.makeText(this, "Папка: $realPath", Toast.LENGTH_LONG).show()
        loadPlaylists()
    }
    private fun showFolderPickerForPlaylist(playlist: Playlist) {
        pendingFolderPlaylistId = playlist.id
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        folderPickerLauncher.launch(intent)
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
                    volumeName.startsWith("raw:") -> "/storage/${volumeName.substring(4)}/$path"
                    else -> "/storage/$volumeName/$path"
                }
            } else null
        } catch (e: Exception) {
            if (debugLog) android.util.Log.d("[CCM]", "[PlaylistManagerActivity.getRealPathFromUri] Error", e)
            null
        }
    }
    private fun loadPlaylists() {
        if (debugLog) android.util.Log.d("[CCM]", "[PlaylistManagerActivity.loadPlaylists] In")
        var playlists = playlistManager.getAllPlaylists()

        playlists = when (currentSortCriterion) {
            PlaylistManager.SortCriterion.BY_NAME -> playlists.sortedBy { it.name.lowercase() }
            PlaylistManager.SortCriterion.BY_LAST_MODIFIED -> playlists.sortedByDescending { it.lastModified }
            PlaylistManager.SortCriterion.BY_LAST_USED -> playlists.sortedByDescending { it.lastUsed }
        }

        adapter.submitList(playlists)

        emptyView.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE

        val currentPlaylist = playlistManager.getCurrentPlaylist()
        if (currentPlaylist != null) {
            supportActionBar?.subtitle = "Current: ${currentPlaylist.name}"
        }
    }

    private fun showCreateDialog() {
        if (debugLog) android.util.Log.d("[CCM]", "[PlaylistManagerActivity.showCreateDialog] In")
        val input = EditText(this)
        input.hint = getString(R.string.playlist_create_hint)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.playlist_create_title))
            .setView(input)
            .setPositiveButton(getString(R.string.playlist_create_title)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val playlist = playlistManager.createPlaylist(name)
                    if (playlist != null) {
                        Toast.makeText(this,
                            getString(R.string.playlist_create_success, playlist.name),
                            Toast.LENGTH_SHORT).show()
                        loadPlaylists()
                    } else {
                        Toast.makeText(this,
                            getString(R.string.playlist_exists_error),
                            Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val playlist = playlistManager.createAutoNamedPlaylist()
                    Toast.makeText(this,
                        getString(R.string.playlist_create_auto, playlist.name),
                        Toast.LENGTH_SHORT).show()
                    loadPlaylists()
                }
            }
            .setNegativeButton(getString(R.string.cancel_action)) { _, _ -> }
            .show()
    }

    private fun showEditDialog(playlist: Playlist) {
        if (debugLog) android.util.Log.d("[CCM]", "[PlaylistManagerActivity.showEditDialog] playlist=${playlist.name}")
        val input = EditText(this)
        input.setText(playlist.name)
        input.selectAll()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.playlist_rename_title))
            .setView(input)
            .setPositiveButton(getString(R.string.playlist_rename_title)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != playlist.name) {
                    if (playlistManager.renamePlaylist(playlist.id, newName)) {
                        Toast.makeText(this,
                            getString(R.string.playlist_rename_success, newName),
                            Toast.LENGTH_SHORT).show()
                        loadPlaylists()
                    } else {
                        Toast.makeText(this,
                            getString(R.string.playlist_exists_error),
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel_action)) { _, _ -> }
            .show()
    }

    private fun showDeleteDialog(playlist: Playlist) {
        if (debugLog) android.util.Log.d("[CCM]", "[PlaylistManagerActivity.showDeleteDialog] playlist=${playlist.name}")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.playlist_delete_title))
            .setMessage(getString(R.string.playlist_delete_message, playlist.name))
            .setPositiveButton(getString(R.string.delete_action)) { _, _ ->
                if (playlistManager.deletePlaylist(playlist.id)) {
                    Toast.makeText(this,
                        getString(R.string.playlist_delete_success, playlist.name),
                        Toast.LENGTH_SHORT).show()
                    loadPlaylists()
                } else {
                    Toast.makeText(this,
                        getString(R.string.playlist_delete_last_error),
                        Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel_action)) { _, _ -> }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.playlist_manager_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_sort_by_name -> {
                currentSortCriterion = PlaylistManager.SortCriterion.BY_NAME
                loadPlaylists()
                Toast.makeText(this, getString(R.string.playlist_sorted_by_name), Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_sort_by_modified -> {
                currentSortCriterion = PlaylistManager.SortCriterion.BY_LAST_MODIFIED
                loadPlaylists()
                Toast.makeText(this, getString(R.string.playlist_sorted_by_modified), Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_sort_by_used -> {
                currentSortCriterion = PlaylistManager.SortCriterion.BY_LAST_USED
                loadPlaylists()
                Toast.makeText(this, getString(R.string.playlist_sorted_by_used), Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    class PlaylistAdapter(
        private val onItemClick: (Playlist) -> Unit,
        private val onEditClick: (Playlist) -> Unit,
        private val onDeleteClick: (Playlist) -> Unit,
        private val onFolderClick: (Playlist) -> Unit
    ) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

        private val debugLog: Boolean
            get() = DebugConfig.isEnabled
        private var playlists: List<Playlist> = emptyList()

        fun submitList(newList: List<Playlist>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = playlists.size
                override fun getNewListSize() = newList.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                    playlists[oldPos].id == newList[newPos].id
                override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                    playlists[oldPos] == newList[newPos]
            })
            playlists = newList
            diff.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_playlist, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val playlist = playlists[position]
            holder.bind(playlist)
        }

        override fun getItemCount(): Int = playlists.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val textName: TextView = itemView.findViewById(R.id.textPlaylistName)
            private val textModified: TextView = itemView.findViewById(R.id.textLastModified)
            private val textUsed: TextView = itemView.findViewById(R.id.textLastUsed)
            private val textMusicDirectory: TextView = itemView.findViewById(R.id.textMusicDirectory)
            private val buttonEdit: ImageButton = itemView.findViewById(R.id.buttonEdit)
            private val buttonDelete: ImageButton = itemView.findViewById(R.id.buttonDelete)
            private val buttonFolder: ImageButton = itemView.findViewById(R.id.buttonFolder)
            fun bind(playlist: Playlist) {
                textName.text = playlist.name
                textModified.text = itemView.context.getString(
                    R.string.playlist_modified_format,
                    itemView.context.getString(R.string.playlist_modified_prefix),
                    playlist.getFormattedLastModified()
                )
                textUsed.text = itemView.context.getString(
                    R.string.playlist_used_format,
                    itemView.context.getString(R.string.playlist_used_prefix),
                    playlist.getFormattedLastUsed()
                )
                val dir = playlist.settings.musicDirectoryPath
                textMusicDirectory.text = if (dir.isEmpty()) {
                    itemView.context.getString(R.string.playlist_no_directory)
                } else {
                    itemView.context.getString(R.string.playlist_directory_prefix, dir)
                }
                itemView.setOnClickListener { onItemClick(playlist) }
                buttonEdit.setOnClickListener { onEditClick(playlist) }
                buttonDelete.setOnClickListener { onDeleteClick(playlist) }
                buttonFolder.setOnClickListener { onFolderClick(playlist) }
            }
        }
    }
}