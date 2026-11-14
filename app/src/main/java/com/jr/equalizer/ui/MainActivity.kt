package com.jr.equalizer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jr.equalizer.dsp.BuiltInPresets
import com.jr.equalizer.dsp.AcousticMatrix
import com.jr.equalizer.dsp.DspSettings
import com.jr.equalizer.dsp.DspSettingsStore
import com.jr.equalizer.dsp.AudioOutputProfile
import com.jr.equalizer.dsp.DeviceProfileStore
import com.jr.equalizer.dsp.ExportFormat
import com.jr.equalizer.dsp.OfflineAudioExporter
import com.jr.equalizer.service.AudioSessionEffects
import com.jr.equalizer.service.GlobalEqService
import com.jr.equalizer.service.AudioRuntimeStatusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Ink = Color(0xFF08080C)
private val Panel = Color(0xFF14141C)
private val PanelRaised = Color(0xFF1C1C28)
private val Cyan = Color(0xFF00F5D4)
private val Purple = Color(0xFF7B2CBF)
private val Muted = Color(0xFF9696A8)
private val White = Color(0xFFF4F4F8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8, 8, 12)
        window.navigationBarColor = android.graphics.Color.rgb(8, 8, 12)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Cyan,
                secondary = Purple,
                background = Ink,
                surface = Panel,
                onPrimary = Ink,
                onBackground = White,
                onSurface = White
            )) {
                JrEqualizerApp()
            }
        }
    }
}

private enum class MainTab(val title: String) {
    EQUALIZER("Equalizer"), TONE("Tone & Dynamics"), EXPORT("Audio Export"), SETTINGS("Settings")
}

@Composable
private fun JrEqualizerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioRuntime by AudioRuntimeStatusRepository.state
        .map { it.copy(spectrum = emptyList()) }
        .distinctUntilChanged()
        .collectAsState(
            initial = AudioRuntimeStatusRepository.state.value.copy(spectrum = emptyList())
        )
    var tab by remember { mutableStateOf(MainTab.EQUALIZER) }
    var activeDeviceProfile by remember { mutableStateOf(DeviceProfileStore.detect(context)) }
    var settings by remember {
        mutableStateOf(DeviceProfileStore.load(context, activeDeviceProfile) ?: DspSettingsStore.load(context))
    }
    var notificationListenerEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var globalEnabled by remember { mutableStateOf(
        context.getSharedPreferences(GlobalEqService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(GlobalEqService.KEY_ENABLED, false)
    ) }
    var globalStatus by remember { mutableStateOf(
        context.getSharedPreferences(GlobalEqService.PREFS, Context.MODE_PRIVATE)
            .getString(GlobalEqService.KEY_STATUS, "Not enabled") ?: "Not enabled"
    ) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf("No audio file selected") }
    var exportFormat by remember { mutableStateOf(ExportFormat.WAV) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var exportStatus by remember { mutableStateOf("Choose an audio file to begin.") }
    var exporting by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playerEffect by remember { mutableStateOf<android.media.audiofx.DynamicsProcessing?>(null) }
    var playerEffectSessionId by remember { mutableIntStateOf(-1) }
    var previewStatus by remember { mutableStateOf("Preview not started") }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "Notification permission helps keep the audio service visible.", Toast.LENGTH_LONG).show()
    }
    val audioVisualizationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && globalEnabled) {
            context.startService(Intent(context, GlobalEqService::class.java).setAction(GlobalEqService.ACTION_START_VISUALIZER))
        } else if (!granted) {
            Toast.makeText(context, "Audio permission is needed for the live spectrum visualizer.", Toast.LENGTH_LONG).show()
        }
    }

    val latestSettings by rememberUpdatedState(settings)
    val latestDeviceProfile by rememberUpdatedState(activeDeviceProfile)

    // Compose state changes immediately; the existing foreground DSP service
    // receives its ACTION_UPDATE directly from the tap callback (not a broadcast).
    fun applySettings(next: DspSettings) {
        settings = next
        DspSettingsStore.save(context, next)
        DeviceProfileStore.save(context, activeDeviceProfile, next)
        if (globalEnabled) {
            runCatching {
                context.startService(Intent(context, GlobalEqService::class.java).apply {
                    action = GlobalEqService.ACTION_UPDATE
                    putExtra(GlobalEqService.EXTRA_JSON, next.toJsonString())
                })
            }
        }
    }

    DisposableEffect(context) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        fun refreshAudioProfile() {
            val nextProfile = DeviceProfileStore.detect(context)
            if (nextProfile != latestDeviceProfile) {
                DeviceProfileStore.save(context, latestDeviceProfile, latestSettings)
                activeDeviceProfile = nextProfile
                applySettings(DeviceProfileStore.load(context, nextProfile) ?: latestSettings)
                Toast.makeText(context, "Audio profile: " + nextProfile.label, Toast.LENGTH_SHORT).show()
            }
        }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                refreshAudioProfile()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                refreshAudioProfile()
            }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationListenerEnabled = isNotificationListenerEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedName = queryDisplayName(context, uri)
            exportStatus = "Ready to preview or export."
            exportProgress = 0f
        }
    }
    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("Could not open this preset file.")
                    }
                }
                result.onSuccess { json ->
                    runCatching { applySettings(DspSettings.fromJsonString(json)) }
                        .onSuccess { Toast.makeText(context, "Preset imported.", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, "This preset JSON is not valid.", Toast.LENGTH_LONG).show() }
                }.onFailure {
                    Toast.makeText(context, it.message ?: "Import failed.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val jsonSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(settings.toJsonString().toByteArray(Charsets.UTF_8))
                        } ?: error("Could not write the preset.")
                    }
                }.onSuccess {
                    Toast.makeText(context, "Preset JSON exported.", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, it.message ?: "Export failed.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun stopPreview() {
        val effectToRelease = playerEffect
        val effectSession = playerEffectSessionId
        playerEffect = null
        playerEffectSessionId = -1
        scope.launch(Dispatchers.IO) { runCatching { AudioSessionEffects.release(effectToRelease, effectSession) } }
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        previewStatus = "Preview stopped"
    }

    fun startPreview() {
        val uri = selectedUri ?: run {
            Toast.makeText(context, "Select an audio file first.", Toast.LENGTH_SHORT).show()
            return
        }
        stopPreview()
        try {
            val media = MediaPlayer()
            player = media
            media.setDataSource(context, uri)
            media.setOnPreparedListener { prepared ->
                scope.launch {
                    val effect = runCatching {
                        withContext(Dispatchers.IO) {
                            AudioSessionEffects.create(prepared.audioSessionId, settings)
                        }
                    }.getOrNull()
                    playerEffect = effect
                    playerEffectSessionId = if (effect != null) prepared.audioSessionId else -1
                    if (effect == null) previewStatus = "Preview active; device DSP attachment unavailable."
                    prepared.start()
                    previewStatus = if (effect != null) "Preview playing with JR-EQUILZER settings"
                    else "Preview playing without session DSP"
                }
            }
            media.setOnCompletionListener {
                previewStatus = "Preview complete"
                val effectToRelease = playerEffect
                val effectSession = playerEffectSessionId
                playerEffect = null
                playerEffectSessionId = -1
                scope.launch(Dispatchers.IO) { runCatching { AudioSessionEffects.release(effectToRelease, effectSession) } }
            }
            media.setOnErrorListener { _, what, extra ->
                previewStatus = "Preview failed (" + what + "/" + extra + ")"
                true
            }
            media.prepareAsync()
            previewStatus = "Loading audio…"
        } catch (error: Throwable) {
            stopPreview()
            previewStatus = error.message ?: "Could not open this audio file."
        }
    }

    fun toggleGlobal(enabled: Boolean) {
        globalEnabled = enabled
        val intent = android.content.Intent(context, GlobalEqService::class.java).apply {
            action = if (enabled) GlobalEqService.ACTION_ENABLE else GlobalEqService.ACTION_DISABLE
            putExtra(GlobalEqService.EXTRA_JSON, settings.toJsonString())
        }
        try {
            if (enabled) {
                context.getSharedPreferences(GlobalEqService.PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(GlobalEqService.KEY_ENABLED, true).putString(GlobalEqService.KEY_SETTINGS, settings.toJsonString()).apply()
                if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                ContextCompat.startForegroundService(context, intent)
                globalStatus = "Checking whether device firmware permits output-mix effects…"
            } else {
                context.startService(intent)
                globalStatus = "Disabled"
            }
        } catch (error: Throwable) {
            globalEnabled = false
            globalStatus = error.message ?: "Audio service could not start."
        }
    }

    LaunchedEffect(settings, activeDeviceProfile) {
        delay(20)
        withContext(Dispatchers.IO) {
            DspSettingsStore.save(context, settings)
            DeviceProfileStore.save(context, activeDeviceProfile, settings)
        }
    }

    LaunchedEffect(globalEnabled) {
        if (globalEnabled) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                context.startService(Intent(context, GlobalEqService::class.java).setAction(GlobalEqService.ACTION_START_VISUALIZER))
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopPreview() }
    }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MainTab.values().forEach { item ->
                    val selected = tab == item
                    Column(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (selected) Cyan.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { tab = item }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = when (item) {
                                MainTab.EQUALIZER -> Icons.Filled.GraphicEq
                                MainTab.TONE -> Icons.Filled.Tune
                                MainTab.EXPORT -> Icons.Filled.LibraryMusic
                                MainTab.SETTINGS -> Icons.Filled.Settings
                            },
                            contentDescription = item.title,
                            tint = if (selected) Cyan else Muted,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            item.title,
                            color = if (selected) Cyan else Muted,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).background(Ink)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Header(globalEnabled)
            AudioStatusBanner(
                detectedAppName = audioRuntime.detectedAppName,
                detectedPlaying = audioRuntime.detectedPlaying,
                hookedAppName = audioRuntime.hookedAppName,
                hookedSessionId = audioRuntime.hookedSessionId,
                playbackConfigurationCount = audioRuntime.playbackConfigurationCount,
                globalEngine = audioRuntime.globalEngine,
                visualizerStatus = audioRuntime.visualizerStatus,
                listenerEnabled = notificationListenerEnabled,
                onEnableTracking = {
                    runCatching {
                        context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }.onFailure {
                        Toast.makeText(context, "Notification listener settings are unavailable on this device.", Toast.LENGTH_LONG).show()
                    }
                },
                onEnableVisualizer = {
                    audioVisualizationPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            )
            when (tab) {
                MainTab.EQUALIZER -> EqualizerScreen(settings, onSettings = { applySettings(it) })
                MainTab.TONE -> ToneScreen(settings, onSettings = { applySettings(it) })
                MainTab.EXPORT -> ExportScreen(
                    fileName = selectedName,
                    hasFile = selectedUri != null,
                    format = exportFormat,
                    progress = exportProgress,
                    status = exportStatus,
                    exporting = exporting,
                    previewStatus = previewStatus,
                    onPick = { audioPicker.launch(arrayOf("audio/*")) },
                    onFormat = { exportFormat = it },
                    onPreview = { if (player?.isPlaying == true) stopPreview() else startPreview() },
                    onExport = {
                        val uri = selectedUri
                        if (uri == null || exporting) return@ExportScreen
                        scope.launch {
                            exporting = true
                            exportProgress = 0f
                            exportStatus = "Processing audio with the active DSP settings…"
                            val result = runCatching {
                                OfflineAudioExporter(context).export(
                                    uri, settings, exportFormat,
                                    onProgress = { value ->
                                        scope.launch(Dispatchers.Main.immediate) { exportProgress = value }
                                    },
                                    onSpectrum = { values -> AudioRuntimeStatusRepository.updateSpectrum(values) }
                                )
                            }
                            result.onSuccess {
                                exportStatus = "Saved to Music/JR-Equalizer"
                                Toast.makeText(context, "Equalized audio saved.", Toast.LENGTH_LONG).show()
                            }.onFailure {
                                exportStatus = it.message ?: "Export failed."
                                Toast.makeText(context, exportStatus, Toast.LENGTH_LONG).show()
                            }
                            exporting = false
                        }
                    }
                )
                MainTab.SETTINGS -> SettingsScreen(
                    globalEnabled = globalEnabled,
                    globalStatus = audioRuntime.globalEngine,
                    settings = settings,
                    activeDeviceProfile = activeDeviceProfile,
                    onSettings = { applySettings(it) },
                    onGlobal = { toggleGlobal(it) },
                    onPreset = { applySettings(it) },
                    onImport = { jsonPicker.launch(arrayOf("application/json", "text/*")) },
                    onExport = { jsonSaver.launch("JR-EQ-Preset.json") }
                )
            }
            Text("JR-EQUILZER • CYBER-STUDIO DSP • v4.0.0", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun Header(globalEnabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("JR-EQUILZER", fontSize = 23.sp, fontWeight = FontWeight.Black, color = White, letterSpacing = 1.4.sp)
            Text("PRECISION AUDIO CONTROL", fontSize = 9.sp, color = Cyan, letterSpacing = 2.sp)
        }
        Box(
            modifier = Modifier.clip(CircleShape).background(if (globalEnabled) Cyan.copy(alpha = 0.14f) else PanelRaised)
                .padding(horizontal = 11.dp, vertical = 7.dp)
        ) {
            Text(if (globalEnabled) "● ACTIVE" else "● STANDBY", color = if (globalEnabled) Cyan else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AudioStatusBanner(
    detectedAppName: String,
    detectedPlaying: Boolean,
    hookedAppName: String,
    hookedSessionId: Int?,
    playbackConfigurationCount: Int,
    globalEngine: String,
    visualizerStatus: String,
    listenerEnabled: Boolean,
    onEnableTracking: () -> Unit,
    onEnableVisualizer: () -> Unit
) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ACTIVE AUDIO STATUS", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (detectedPlaying) "PLAYING" else "IDLE", color = if (detectedPlaying) Cyan else Muted, fontSize = 9.sp)
        }
        Text("Detected player: $detectedAppName", color = White, fontSize = 12.sp)
        Text(
            if (hookedSessionId != null) "EQ session attached: $hookedAppName • ID $hookedSessionId"
            else "EQ session: not attached — waiting for a compatible player session",
            color = if (hookedSessionId != null) Cyan else Muted,
            fontSize = 11.sp
        )
        Text("System playback activity: $playbackConfigurationCount active configuration(s)", color = Muted, fontSize = 10.sp)
        Text("Global mix: $globalEngine", color = Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(visualizerStatus, color = Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEnableTracking, modifier = Modifier.weight(1f)) {
                Text(
                    if (listenerEnabled) "Player tracking enabled" else "Enable Advanced Player Tracking for YouTube Music & Spotify",
                    fontSize = 9.sp
                )
            }
            OutlinedButton(onClick = onEnableVisualizer) {
                Text("Enable FFT", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun PanelCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun EqualizerScreen(settings: DspSettings, onSettings: (DspSettings) -> Unit) {
    val spectrum by AudioRuntimeStatusRepository.state
        .map { it.spectrum }
        .distinctUntilChanged()
        .collectAsState(initial = List(24) { 0f })
    var activeSignature by remember { mutableStateOf("Custom Matrix") }

    SectionTitle("CYBER-STUDIO MASTERING CONSOLE", "Slider-free • 8-zone acoustic matrix • 32-band DSP core")
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Global tone engine", color = White, fontWeight = FontWeight.Bold)
                Text("The player session connection is unchanged", color = Muted, fontSize = 10.sp)
            }
            Switch(checked = settings.enabled, onCheckedChange = {
                onSettings(settings.copy(enabled = it))
            })
        }
        Text("SOUND SIGNATURE PADS", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        val signatures = listOf(
            "🔥 Extreme Basshead",
            "💎 Crystal Harman",
            "🎤 Studio Vocals",
            "🎧 Dolby 3D Stage",
            "⚡ Raw Loudness",
            "🎵 Latin Urban / Pop",
            "🛠️ Custom Matrix"
        )
        signatures.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { item ->
                    val name = item.substringAfter(' ').trim()
                    TactilePill(
                        label = item,
                        selected = activeSignature == name,
                        modifier = Modifier.weight(1f)
                    ) {
                        activeSignature = name
                        val configured = when (name) {
                            "Extreme Basshead" -> AcousticMatrix.withZones(
                                settings.copy(bassDb = 16.0, trebleDb = 3.0, vocalPresenceDb = 0.0, bassFrequencyHz = 45.0, turboPercent = 0.0, dvcEnabled = false),
                                listOf(9.0, 7.0, 3.5, -1.0, -1.0, 0.5, 1.5, 2.0)
                            )
                            "Crystal Harman" -> AcousticMatrix.withZones(
                                settings.copy(bassDb = 7.0, trebleDb = 6.0, vocalPresenceDb = 2.0, turboPercent = 0.0, dvcEnabled = false),
                                listOf(2.0, 3.0, 1.0, -1.0, 1.5, 2.0, 2.5, 2.0)
                            )
                            "Studio Vocals" -> AcousticMatrix.withZones(
                                settings.copy(bassDb = 1.0, trebleDb = 3.0, vocalPresenceDb = 8.0, turboPercent = 0.0, dvcEnabled = false),
                                listOf(-1.0, -1.0, 0.0, 1.0, 4.0, 5.0, 2.0, 1.0)
                            )
                            "Dolby 3D Stage" -> AcousticMatrix.withZones(
                                settings.copy(bassDb = 4.0, trebleDb = 5.0, vocalPresenceDb = 1.0, stereoWidthPercent = 180.0, turboPercent = 0.0, dvcEnabled = false),
                                listOf(2.0, 2.0, 1.0, -1.0, 1.5, 2.0, 3.0, 3.0)
                            )
                            "Raw Loudness" -> AcousticMatrix.withZones(
                                settings.copy(bassDb = 4.0, trebleDb = 4.0, vocalPresenceDb = 1.0, turboPercent = 100.0, dvcEnabled = false),
                                listOf(1.0, 1.0, 0.0, 0.0, 0.5, 1.0, 1.0, 0.5)
                            )
                            "Latin Urban / Pop" -> AcousticMatrix.withZones(
                                settings.copy(bassDb = 12.0, trebleDb = 7.0, vocalPresenceDb = 2.5, bassFrequencyHz = 55.0, turboPercent = 20.0, dvcEnabled = false),
                                listOf(7.0, 6.0, 2.5, -1.0, 1.0, 2.5, 3.0, 2.0)
                            )
                            else -> settings
                        }
                        onSettings(configured)
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text("Active signature: $activeSignature", color = Cyan, fontSize = 10.sp)
    }

    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("8-ZONE ACOUSTIC MATRIX", color = White, fontWeight = FontWeight.Bold)
                Text("Each zone interpolates across the complete 32-band Pre-EQ", color = Muted, fontSize = 10.sp)
            }
            TactilePill("FLAT", selected = false) {
                activeSignature = "Custom Matrix"
                onSettings(AcousticMatrix.flat(settings))
            }
        }
        AcousticMatrix.zones.chunked(2).forEachIndexed { rowIndex, rowZones ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowZones.forEachIndexed { columnIndex, zone ->
                    val index = rowIndex * 2 + columnIndex
                    val gain = settings.zoneGainValues().getOrElse(index) { 0.0 }
                    MatrixZoneCard(
                        name = zone.name,
                        range = zone.rangeLabel,
                        gainDb = gain,
                        modifier = Modifier.weight(1f),
                        onGain = { next -> onSettings(AcousticMatrix.withZoneGain(settings, index, next)) },
                        onPreset = { next -> onSettings(AcousticMatrix.withZoneGain(settings, index, next)) }
                    )
                }
                if (rowZones.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text("Zone range: −12 dB to +15 dB • changes apply without dragging", color = Muted, fontSize = 10.sp)
    }

    PanelCard {
        SectionTitle("Live spectrum", "FFT monitor from the active audio session")
        SpectrumBars(spectrum.toFloatArray(), Modifier.fillMaxWidth().height(72.dp))
    }
}

@Composable
private fun MatrixZoneCard(
    name: String,
    range: String,
    gainDb: Double,
    modifier: Modifier = Modifier,
    onGain: (Double) -> Unit,
    onPreset: (Double) -> Unit
) {
    val gain = gainDb.coerceIn(-12.0, 15.0)
    val activeBars = (kotlin.math.abs(gain) / 15.0 * 10.0).toInt().coerceIn(0, 10)
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = PanelRaised)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(name, color = White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(range, color = Muted, fontSize = 9.sp)
            Text(String.format(java.util.Locale.US, "%+.1f dB", gain), color = if (gain < 0) Purple else Cyan, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                repeat(10) { segment ->
                    Box(
                        Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                            .background(
                                if (segment < activeBars) {
                                    if (gain < 0) Purple else Cyan
                                } else Panel
                            )
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                TactilePill("−", modifier = Modifier.weight(1f)) { onGain((gain - 0.5).coerceAtLeast(-12.0)) }
                TactilePill("+", modifier = Modifier.weight(1f)) { onGain((gain + 0.5).coerceAtMost(15.0)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("Cut" to -6.0, "Flat" to 0.0, "Warm" to 2.0, "Boost" to 6.0, "Extreme" to 12.0).forEach { (label, value) ->
                    TactilePill(
                        label = label,
                        modifier = Modifier.weight(1f),
                        selected = kotlin.math.abs(gain - value) < 0.01
                    ) { onPreset(value) }
                }
            }
        }
    }
}

@Composable
private fun TactilePill(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    OutlinedButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled,
        modifier = modifier.height(32.dp),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, if (selected) Cyan else Color(0xFF414150)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Cyan.copy(alpha = 0.16f) else PanelRaised,
            contentColor = if (selected) Cyan else White,
            disabledContentColor = Muted
        ),
        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)
    ) {
        Text(label, fontSize = 9.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun SpectrumBars(values: FloatArray, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val count = values.size.coerceAtLeast(1)
        val gap = 3.dp.toPx()
        val barWidth = (size.width - gap * (count - 1)) / count
        values.forEachIndexed { index, value ->
            val height = size.height * value.coerceIn(0f, 1f)
            drawRoundRect(
                color = if (index % 4 == 0) Purple else Cyan,
                topLeft = Offset(index * (barWidth + gap), size.height - height),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
            )
        }
    }
}

@Composable
private fun TactileDialControl(
    title: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    step: Float,
    suffix: String,
    modifier: Modifier = Modifier,
    resetValue: Float = minimum,
    onChange: (Float) -> Unit
) {
    val fraction = ((value - minimum) / (maximum - minimum).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
    Column(modifier = modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(modifier = Modifier.size(104.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                drawArc(PanelRaised, 135f, 270f, false, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(Cyan, 135f, 270f * fraction, false, style = Stroke(stroke, cap = StrokeCap.Round))
                for (tick in 0..10) {
                    val angle = Math.toRadians((135.0 + tick * 27.0))
                    val inner = size.minDimension * 0.40f
                    val outer = size.minDimension * 0.47f
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    drawLine(
                        if (tick / 10f <= fraction) Cyan else Muted.copy(alpha = 0.40f),
                        Offset(cx + kotlin.math.cos(angle).toFloat() * inner, cy + kotlin.math.sin(angle).toFloat() * inner),
                        Offset(cx + kotlin.math.cos(angle).toFloat() * outer, cy + kotlin.math.sin(angle).toFloat() * outer),
                        1.6.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                drawCircle(Panel, size.minDimension * 0.31f)
                drawCircle(Cyan.copy(alpha = 0.08f), size.minDimension * 0.27f)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(String.format(java.util.Locale.US, "%.0f", value), color = White, fontSize = 21.sp, fontWeight = FontWeight.Black)
                Text(suffix, color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(title, color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
            TactilePill(if (suffix == "Hz") "−5 Hz" else "−5%", modifier = Modifier.weight(1f)) {
                onChange((value - step).coerceAtLeast(minimum))
            }
            TactilePill(if (suffix == "Hz") "+5 Hz" else "+5%", modifier = Modifier.weight(1f)) {
                onChange((value + step).coerceAtMost(maximum))
            }
        }
        TactilePill("MAX / RESET", modifier = Modifier.fillMaxWidth()) {
            onChange(if (value >= maximum - step * 0.25f) resetValue else maximum)
        }
    }
}

@Composable
private fun StepperControl(
    title: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    step: Float,
    suffix: String,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(String.format(java.util.Locale.US, "%.1f %s", value, suffix), color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        TactilePill("−", modifier = Modifier.width(44.dp)) { onChange((value - step).coerceAtLeast(minimum)) }
        TactilePill("+", modifier = Modifier.width(44.dp)) { onChange((value + step).coerceAtMost(maximum)) }
        TactilePill("RESET", modifier = Modifier.width(57.dp)) { onChange(0f.coerceIn(minimum, maximum)) }
    }
}

@Composable
private fun ToneScreen(settings: DspSettings, onSettings: (DspSettings) -> Unit) {
    SectionTitle("Mastering Controls", "Six tactile controls • one tap per adjustment • no faders")
    PanelCard {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            TactileDialControl(
                "MONSTER BASS",
                (settings.bassDb / 18.0 * 100.0).toFloat(),
                0f, 100f, 5f, "%",
                modifier = Modifier.weight(1f)
            ) { onSettings(settings.copy(bassDb = it / 100.0 * 18.0)) }
            TactileDialControl(
                "CRYSTAL TREBLE",
                (settings.trebleDb / 16.0 * 100.0).toFloat(),
                0f, 100f, 5f, "%",
                modifier = Modifier.weight(1f)
            ) { onSettings(settings.copy(trebleDb = it / 100.0 * 16.0)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            TactileDialControl(
                "SUB PUNCH",
                settings.bassFrequencyHz.toFloat().coerceIn(20f, 90f),
                20f, 90f, 5f, "Hz",
                modifier = Modifier.weight(1f), resetValue = 45f
            ) { onSettings(settings.copy(bassFrequencyHz = it.toDouble())) }
            TactileDialControl(
                "VOCAL PRESENCE",
                (settings.vocalPresenceDb / 12.0 * 100.0).toFloat(),
                0f, 100f, 5f, "%",
                modifier = Modifier.weight(1f)
            ) { onSettings(settings.copy(vocalPresenceDb = it / 100.0 * 12.0)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
            TactileDialControl(
                "3D SURROUND",
                settings.stereoWidthPercent.toFloat().coerceIn(100f, 200f),
                100f, 200f, 5f, "%",
                modifier = Modifier.weight(1f), resetValue = 100f
            ) { onSettings(settings.copy(stereoWidthPercent = it.toDouble())) }
            TactileDialControl(
                "TURBO LOUDNESS",
                settings.turboPercent.toFloat().coerceIn(0f, 100f),
                0f, 100f, 5f, "%",
                modifier = Modifier.weight(1f)
            ) { onSettings(settings.copy(turboPercent = it.toDouble(), dvcEnabled = false)) }
        }
    }

    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Six-band dynamics", color = White, fontWeight = FontWeight.Bold)
                Text("MBC with low-band punch and a fixed −0.2 dBFS limiter", color = Muted, fontSize = 10.sp)
            }
            Switch(checked = settings.compressor.enabled, onCheckedChange = {
                onSettings(settings.copy(compressor = settings.compressor.copy(enabled = it)))
            })
        }
        StepperControl("Threshold", settings.compressor.thresholdDb.toFloat(), -40f, 0f, 1f, "dB") {
            onSettings(settings.copy(compressor = settings.compressor.copy(thresholdDb = it.toDouble())))
        }
        StepperControl("Ratio", settings.compressor.ratio.toFloat(), 1f, 20f, 1f, ":1") {
            onSettings(settings.copy(compressor = settings.compressor.copy(ratio = it.toDouble())))
        }
        StepperControl("Attack", settings.compressor.attackMs.toFloat(), 1f, 200f, 1f, "ms") {
            onSettings(settings.copy(compressor = settings.compressor.copy(attackMs = it.toDouble())))
        }
        StepperControl("Release", settings.compressor.releaseMs.toFloat(), 10f, 1_000f, 10f, "ms") {
            onSettings(settings.copy(compressor = settings.compressor.copy(releaseMs = it.toDouble())))
        }
        StepperControl("Makeup", settings.compressor.makeupDb.toFloat(), -12f, 6f, 0.5f, "dB") {
            onSettings(settings.copy(compressor = settings.compressor.copy(makeupDb = it.toDouble())))
        }
        HorizontalDivider(color = Color(0xFF343440))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Noise gate", modifier = Modifier.weight(1f), color = White)
            Switch(checked = settings.compressor.noiseGateEnabled, onCheckedChange = {
                onSettings(settings.copy(compressor = settings.compressor.copy(noiseGateEnabled = it)))
            })
        }
        StepperControl("Gate threshold", settings.compressor.noiseGateThresholdDb.toFloat(), -100f, -30f, 1f, "dBFS") {
            onSettings(settings.copy(compressor = settings.compressor.copy(noiseGateThresholdDb = it.toDouble())))
        }
        Text("The brickwall limiter stays enabled at −0.2 dBFS to catch peaks. Start at a comfortable listening level; DSP boost does not make higher volume safe.", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun ExportScreen(
    fileName: String,
    hasFile: Boolean,
    format: ExportFormat,
    progress: Float,
    status: String,
    exporting: Boolean,
    previewStatus: String,
    onPick: () -> Unit,
    onFormat: (ExportFormat) -> Unit,
    onPreview: () -> Unit,
    onExport: () -> Unit
) {
    var formatMenu by remember { mutableStateOf(false) }
    SectionTitle("Audio Exporter", "Render your active EQ directly to phone storage")
    PanelCard {
        Box(modifier = Modifier.fillMaxWidth().height(94.dp).clip(RoundedCornerShape(16.dp)).background(PanelRaised), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(34) { index ->
                    Box(
                        modifier = Modifier.width(3.dp).height((10 + (index * 13 % 48)).dp)
                            .clip(CircleShape).background(if (index % 5 == 0) Purple else Cyan.copy(alpha = 0.8f))
                    )
                }
            }
        }
        Text("Selected audio", color = Muted, fontSize = 11.sp)
        Text(fileName, color = White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Button(
            onClick = onPick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)
        ) { Text(if (hasFile) "Choose another file" else "Select audio file") }
        Text("Supported through Android decoders: MP3, WAV, FLAC, M4A and other supported audio types.", color = Muted, fontSize = 10.sp)
    }
    PanelCard {
        SectionTitle("Preview & render")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPreview, enabled = hasFile, modifier = Modifier.weight(1f)) {
                Text(if (previewStatus.contains("playing", true) || previewStatus == "Loading audio…") "Stop preview" else "Preview")
            }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(onClick = { formatMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (format == ExportFormat.WAV) "WAV • PCM16" else "M4A • AAC")
                }
                DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                    DropdownMenuItem(text = { Text("WAV • PCM 16-bit") }, onClick = { onFormat(ExportFormat.WAV); formatMenu = false })
                    DropdownMenuItem(text = { Text("M4A • AAC 192 kbps") }, onClick = { onFormat(ExportFormat.M4A); formatMenu = false })
                }
            }
        }
        Text(previewStatus, color = Muted, fontSize = 10.sp)
        Button(
            onClick = onExport,
            enabled = hasFile && !exporting,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = White)
        ) { Text(if (exporting) "Rendering…" else "Export equalized audio") }
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Cyan, trackColor = PanelRaised)
        Text(status, color = if (status.startsWith("Saved")) Cyan else Muted, fontSize = 11.sp)
        Text("Output folder: Music/JR-Equalizer", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text("Export applies the same 32 frequency controls, tone, headroom and limiter settings. Offline compression is processed in software; device live effects can vary by Android firmware. M4A is AAC re-encoded.", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun SettingsScreen(
    globalEnabled: Boolean,
    globalStatus: String,
    settings: DspSettings,
    activeDeviceProfile: AudioOutputProfile,
    onSettings: (DspSettings) -> Unit,
    onGlobal: (Boolean) -> Unit,
    onPreset: (DspSettings) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    var presetsExpanded by remember { mutableStateOf(false) }
    SectionTitle("Settings & Profiles", "Routing status, Turbo controls and per-device presets")
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Global equalization", color = White, fontWeight = FontWeight.Bold)
                Text("Use the detected player session when exposed", color = Muted, fontSize = 10.sp)
            }
            Switch(checked = globalEnabled, onCheckedChange = onGlobal)
        }
        Text(globalStatus, color = if (globalStatus.contains("accepted", true)) Cyan else Muted, fontSize = 11.sp)
        Text(
            "YouTube and YouTube Music session detection and attachment are preserved from v3.0.0.",
            color = Muted, fontSize = 10.sp
        )
    }
    PanelCard {
        SectionTitle("Turbo loudness", "Positive gain only — no automatic negative preamp compensation")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Turbo / Loudness Enhancer", color = White, fontWeight = FontWeight.Bold)
                Text("Up to +1.5 dB input gain and +1500 mB LoudnessEnhancer target", color = Muted, fontSize = 10.sp)
            }
            Switch(
                checked = settings.turboPercent > 0.0 || settings.dvcEnabled,
                onCheckedChange = { onSettings(settings.copy(turboPercent = if (it) 100.0 else 0.0, dvcEnabled = false)) }
            )
        }
        StepperControl("Turbo amount", settings.turboPercent.toFloat(), 0f, 100f, 5f, "%") {
            onSettings(settings.copy(turboPercent = it.toDouble(), dvcEnabled = false))
        }
        StepperControl("Manual preamp", settings.preampDb.toFloat(), -24f, 12f, 0.5f, "dB") {
            onSettings(settings.copy(preampDb = it.toDouble()))
        }
        Text(
            "Turbo is bounded by the −0.2 dBFS DSP limiter. It cannot raise the phone speaker or headphone hardware ceiling.",
            color = Muted, fontSize = 10.sp
        )
    }
    PanelCard {
        SectionTitle("Frequency tuning", "Button steppers replace all drag controls")
        StepperControl("Subwoofer punch frequency", settings.bassFrequencyHz.toFloat(), 20f, 90f, 5f, "Hz") {
            onSettings(settings.copy(bassFrequencyHz = it.toDouble()))
        }
        StepperControl("Bass Q-factor (offline shape)", settings.bassQFactor.toFloat(), 0.3f, 3f, 0.1f, "Q") {
            onSettings(settings.copy(bassQFactor = it.toDouble()))
        }
        StepperControl("Treble focus", settings.trebleFrequencyHz.toFloat(), 4_000f, 20_000f, 500f, "Hz") {
            onSettings(settings.copy(trebleFrequencyHz = it.toDouble()))
        }
        StepperControl("Treble Q-factor (offline shape)", settings.trebleQFactor.toFloat(), 0.3f, 3f, 0.1f, "Q") {
            onSettings(settings.copy(trebleQFactor = it.toDouble()))
        }
        Text(
            "Android's live DynamicsProcessing EqBand exposes cutoff and gain, not Q. The offline RBJ renderer uses Q on its tone peaks.",
            color = Muted, fontSize = 10.sp
        )
    }
    PanelCard {
        SectionTitle("Fixed peak protection")
        Text("Brickwall Limiter • threshold −0.2 dBFS • ratio 20:1 • attack 1 ms • release 60 ms • post-gain 0 dB", color = Cyan, fontSize = 11.sp)
        Text("The limiter is kept on for live playback and file export.", color = Muted, fontSize = 10.sp)
    }
    PanelCard {
        SectionTitle("Device profile auto-switch", "Current route: " + activeDeviceProfile.label)
        Text("Profiles are stored separately and switched when Android reports a wired, Bluetooth, USB or phone-speaker route change.", color = Muted, fontSize = 10.sp)
        Text("Phone Speaker • Wired Headset • Bluetooth Audio • USB DAC", color = Cyan, fontSize = 11.sp)
        Text("Physical volume-key blocking and system volume-step overrides are Android / OEM controlled and are not available to an ordinary third-party app.", color = Muted, fontSize = 10.sp)
    }
    PanelCard {
        SectionTitle("Preset manager")
        Box {
            OutlinedButton(onClick = { presetsExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Load built-in preset")
            }
            DropdownMenu(expanded = presetsExpanded, onDismissRequest = { presetsExpanded = false }) {
                BuiltInPresets.all.forEach { preset ->
                    DropdownMenuItem(text = { Text(preset.name) }, onClick = {
                        onPreset(preset.settings.copy(
                            matrixGainsDb = emptyList(),
                            bands = DspSettings.logarithmicBands(32).map { band ->
                                band.copy(gainDb = DspSettings.gainAtFrequency(preset.settings.bands, band.frequencyHz))
                            }
                        ))
                        presetsExpanded = false
                    })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Import JSON") }
            Button(
                onClick = onExport,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)
            ) { Text("Export JSON") }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    var name = uri.lastPathSegment ?: "Selected audio"
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
        }
    }
    return name
}

private fun formatFrequency(frequency: Double): String =
    if (frequency >= 1000.0) String.format(java.util.Locale.US, "%.2f kHz", frequency / 1000.0)
    else String.format(java.util.Locale.US, "%.1f Hz", frequency)

private fun formatValue(value: Float): String = String.format(java.util.Locale.US, "%.1f", value)


private fun isNotificationListenerEnabled(context: Context): Boolean {
    val enabled = runCatching {
        AndroidSettings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
    }.getOrNull() ?: return false
    return enabled.contains(context.packageName)
}
