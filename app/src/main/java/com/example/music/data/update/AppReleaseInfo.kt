package com.example.music.data.update

import kotlinx.serialization.Serializable

@Serializable
data class AppReleaseInfo(
    val repo: String = "",
    val tagName: String = "",
    val releaseName: String = "",
    val versionName: String? = null,
    val versionCode: Int? = null,
    val publishedAt: String? = null,
    val body: String = "",
    val apkUrl: String? = null,
    val apkFileName: String? = null,
    val htmlUrl: String? = null,
)
