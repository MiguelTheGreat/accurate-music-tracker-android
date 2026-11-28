package com.musictracker.app.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class SuggestedPlayer(
    val packageName: String,
    val trackTitle: String,
    val artist: String
)
