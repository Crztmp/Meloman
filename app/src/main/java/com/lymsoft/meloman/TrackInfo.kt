package com.lymsoft.meloman

import java.io.File

data class TrackInfo(
    val filePath: String = "",
    val folderPath: String,
    val folderName: String
) {
    companion object {
        private val DebugLog: Boolean
            get() = DebugConfig.isEnabled
    }

    // Вычисляемое поле для доступа к File
    val file: File?
        get() {
            if (DebugLog) println("[CCM] [TrackInfo.getFile] File $filePath")
            return if (filePath.isNotEmpty()) File(filePath) else null
        }

    val trackId: String
        get() {
//            if (DebugLog) println("[CCM] [TrackInfo.getTrackId] Вызов getter trackId")
            return filePath
        }

    // Поле для кэширования отображаемого имени (заполняется в MainActivity)
    var displayName: String = ""
        private set

    fun setDisplayName(name: String) {
        if (DebugLog) println("[CCM] [TrackInfo.setDisplayName] In, параметр: $name")
        displayName = name
        if (DebugLog) println("[CCM] [TrackInfo.setDisplayName] displayName установлен в: $displayName")
    }

    // Конструктор для обратной совместимости
    constructor(file: File, folderPath: String, folderName: String) : this(
        filePath = file.absolutePath,
        folderPath = folderPath,
        folderName = folderName
    ) {
//        if (DebugLog) println("[CCM] [TrackInfo.constructor] Вход в конструктор, file: ${file.absolutePath}, folderPath: $folderPath, folderName: $folderName")
    }

    init {
//        if (DebugLog) println("[CCM] [TrackInfo.init] Вход в init блок")
    }
}