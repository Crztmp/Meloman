package com.lymsoft.meloman

import java.io.File

data class FolderInfo(
    val folder: File,
    val trackCount: Int,
    val tracks: MutableList<TrackInfo>
)