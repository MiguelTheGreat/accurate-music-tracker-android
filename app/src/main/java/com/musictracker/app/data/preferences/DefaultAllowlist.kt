package com.musictracker.app.data.preferences

object DefaultAllowlist {
    /**
     * Verified Android package IDs for popular streaming and local music players.
     * Intentionally excludes non-music media apps (e.g., com.google.android.youtube,
     * com.instagram.android, com.android.chrome, com.zhiliaoapp.musically).
     */
    val packages: Set<String> = setOf(
        // Streaming music players
        "com.google.android.apps.youtube.music", // YouTube Music
        "com.spotify.music",                     // Spotify
        "com.apple.android.music",               // Apple Music
        "com.aspiro.tidal",                      // TIDAL
        "com.amazon.mp3",                        // Amazon Music
        "com.soundcloud.android",                // SoundCloud
        "deezer.android.app",                    // Deezer
        "com.bandcamp.android",                  // Bandcamp

        // Local / offline music players
        "com.maxmpz.audioplayer",                // Poweramp
        "in.krosbits.musicolet",                 // Musicolet
        "com.piyush.music",                      // Oto Music
        "org.videolan.vlc",                      // VLC
        "code.name.monkey.retromusic",           // Retro Music Player
        "com.rhmsoft.pulsar",                    // Pulsar
        "com.rhmsoft.pulsar.pro",                // Pulsar Pro
        "com.sec.android.app.music",             // Samsung Music
        "com.foobar2000.foobar2000"              // foobar2000
    )
}
