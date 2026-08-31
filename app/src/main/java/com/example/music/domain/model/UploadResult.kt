package com.example.music.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class UploadResponse(
    val uploaded: List<UploadedFile> = emptyList(),
    val libraryCount: Int = 0,
)

@Serializable
data class UploadedFile(
    val name: String,
    val path: String,
    val size: Long,
)
