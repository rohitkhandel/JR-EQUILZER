package com.jr.equalizer.service

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class AudioRuntimeStatus(
    val detectedPackage: String? = null,
    val detectedAppName: String = "No active player detected",
    val detectedPlaying: Boolean = false,
    val playbackConfigurationCount: Int = 0,
    val hookedPackage: String? = null,
    val hookedAppName: String = "No player session hooked",
    val hookedSessionId: Int? = null,
    val attachedSessionCount: Int = 0,
    val globalEngine: String = "Global output effect not active",
    val globalAttached: Boolean = false,
    val visualizerStatus: String = "Visualizer not started",
    val spectrum: List<Float> = List(24) { 0f }
)

object AudioRuntimeStatusRepository {
    private val lock = Any()
    private val sessions = LinkedHashMap<Int, String?>()
    private val mutableState = MutableStateFlow(AudioRuntimeStatus())
    val state: StateFlow<AudioRuntimeStatus> = mutableState.asStateFlow()

    fun updateDetectedPlayer(packageName: String?) {
        synchronized(lock) {
            mutableState.value = mutableState.value.copy(
                detectedPackage = packageName,
                detectedAppName = packageName?.let(::displayName) ?: "No active media session",
                detectedPlaying = packageName != null
            )
        }
    }

    fun updatePlaybackConfigurationCount(count: Int) {
        mutableState.value = mutableState.value.copy(playbackConfigurationCount = count.coerceAtLeast(0))
    }

    fun sessionAttached(sessionId: Int, packageName: String?) {
        synchronized(lock) {
            sessions[sessionId] = packageName
            val named = sessions.entries.lastOrNull { !it.value.isNullOrBlank() }
            mutableState.value = mutableState.value.copy(
                hookedPackage = named?.value,
                hookedAppName = named?.value?.let(::displayName) ?: "Compatible audio session attached",
                hookedSessionId = sessionId,
                attachedSessionCount = sessions.size
            )
        }
    }

    fun sessionDetached(sessionId: Int) {
        synchronized(lock) {
            sessions.remove(sessionId)
            val named = sessions.entries.lastOrNull { !it.value.isNullOrBlank() }
            mutableState.value = mutableState.value.copy(
                hookedPackage = named?.value,
                hookedAppName = named?.value?.let(::displayName)
                    ?: if (sessions.isEmpty()) "No player session hooked" else "Compatible audio session attached",
                hookedSessionId = sessions.keys.lastOrNull(),
                attachedSessionCount = sessions.size
            )
        }
    }

    fun updateGlobalEngine(attached: Boolean, status: String) {
        mutableState.value = mutableState.value.copy(globalAttached = attached, globalEngine = status)
    }

    fun updateVisualizerStatus(status: String) {
        mutableState.value = mutableState.value.copy(visualizerStatus = status)
    }

    fun updateSpectrum(values: FloatArray) {
        mutableState.value = mutableState.value.copy(
            spectrum = values.take(24).map { it.coerceIn(0f, 1f) }.let { items ->
                if (items.size == 24) items else items + List(24 - items.size) { 0f }
            }
        )
    }

    private fun displayName(packageName: String): String = when (packageName) {
        "com.spotify.music" -> "Spotify"
        "com.google.android.apps.youtube.music" -> "YouTube Music"
        "com.google.android.youtube" -> "YouTube"
        "com.android.chrome" -> "Chrome"
        "com.google.android.googlequicksearchbox" -> "Google"
        "com.vanced.android.youtube" -> "YouTube"
        else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}
