package com.lymsoft.meloman

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

object AudioTagReader {

    private val DebugLog: Boolean
        get() = DebugConfig.isEnabled

    /**
     * Читает ID3-теги из аудиофайла используя MediaMetadataRetriever (работает на всех Android версиях)
     * @return Pair(artist, title) где artist может быть null, title может быть null
     */
    fun readTags(file: File): Pair<String?, String?> {
        if (DebugLog) Log.d("[CCM]", "[AudioTagReader.readTags] In, file=${file.absolutePath}")
        return try {
            if (!file.exists() || !file.isFile) {
                if (DebugLog) Log.d("[CCM]", "[AudioTagReader.readTags] File not exists or not a file: ${file.absolutePath}")
                return Pair(null, null)
            }

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)

                var artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                var title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)

                // Очищаем значения
                artist = artist?.takeIf { it.isNotBlank() && it != "<unknown>" }
                title = title?.takeIf { it.isNotBlank() && it != "<unknown>" }

                if (DebugLog) Log.d("[CCM]", "[AudioTagReader.readTags] Read tags - Artist: $artist, Title: $title for: ${file.name}")
                Pair(artist, title)
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                    // Игнорируем
                }
            }
        } catch (e: Exception) {
            if (DebugLog) Log.d("[CCM]", "[AudioTagReader.readTags] Error reading tags from ${file.name}", e)
            Pair(null, null)
        }
    }

    /**
     * Формирует отображаемое имя трека по правилам:
     * - Если есть название - формат "Исполнитель(или Unknown) - Название"
     * - Если нет названия - имя файла
     */
    fun getDisplayName(file: File): String {
        if (DebugLog) Log.d("[CCM]", "[AudioTagReader.getDisplayName] In, file=${file.name}")
        val (artist, title) = readTags(file)

        val result = if (!title.isNullOrBlank()) {
            val artistName = if (!artist.isNullOrBlank()) artist else "Unknown"
            "$artistName - $title"
        } else {
            file.nameWithoutExtension
        }

        if (DebugLog) Log.d("[CCM]", "[AudioTagReader.getDisplayName] Result: $result")
        return result
    }
}