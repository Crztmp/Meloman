package com.lymsoft.meloman

import java.util.UUID

data class CopyTask(
    val id: String = UUID.randomUUID().toString(),
    val sourcePath: String,
    val destPath: String,
    val destUri: String? = null,
    val type: TaskType,
    val name: String,
    var status: TaskStatus = TaskStatus.PENDING,
    var progress: Int = 0,
    val totalSize: Long = 0,
    var copiedSize: Long = 0
) {
    enum class TaskType {
        FILE, DIRECTORY
    }

    enum class TaskStatus {
        PENDING, COPYING, COMPLETED, CANCELLED, ERROR
    }


    fun getProgressPercent(): Int = if (totalSize > 0) ((copiedSize * 100) / totalSize).toInt() else progress
}

data class TaskResult(
    val taskId: String,
    val success: Boolean,
    val sourcePath: String
)