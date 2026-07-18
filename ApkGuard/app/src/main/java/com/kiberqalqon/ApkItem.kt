package com.uzguard

import java.io.File

data class ApkItem(
    val file: File,
    val name: String,
    val path: String,
    val sizeBytes: Long
) {
    val sizeFormatted: String
        get() {
            val kb = sizeBytes / 1024
            val mb = kb / 1024
            return when {
                mb > 0 -> "${mb} MB"
                kb > 0 -> "${kb} KB"
                else -> "$sizeBytes B"
            }
        }
}
