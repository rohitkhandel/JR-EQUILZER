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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jr.equalizer.dsp.BuiltInPresets
import com.jr.equalizer.dsp.DspSettings
import com.jr.equalizer.dsp.DspSettingsStore
import com.jr.equalizer.dsp.AudioOutputProfile
import com.jr.equalizer.dsp.DeviceProfileStore
import com.jr.equalizer.dsp.EqBand
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

    DisposableEffect(context) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        fun refreshAudioProfile() {
            val nextProfile = DeviceProfileStore.detect(context)
            if (nextProfile != latestDeviceProfile) {
                DeviceProfileStore.save(context, latestDeviceProfile, latestSettings)
                activeDeviceProfile = nextProfile
                settings = DeviceProfileStore.load(context, nextProfile) ?: latestSettings
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
                    runCatching { settings = DspSettings.fromJsonString(json) }
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

    LaunchedEffect(settings, globalEnabled) {
        if (globalEnabled) {
            // The DSP service coalesces these updates on its own 16 ms handler;
            // keep UI-side dispatch short to avoid stacking two separate delays.
            delay(4)
            withContext(Dispatchers.IO) {
                runCatching {
                    context.startService(
                        Intent(context, GlobalEqService::class.java).apply {
                            action = GlobalEqService.ACTION_UPDATE
                            putExtra(GlobalEqService.EXTRA_JSON, settings.toJsonString())
                        }
                    )
                }
            }
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
                MainTab.EQUALIZER -> EqualizerScreen(settings, onSettings = { settings = it })
                MainTab.TONE -> ToneScreen(settings, onSettings = { settings = it })
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
                    onSettings = { settings = it },
                    onGlobal = { toggleGlobal(it) },
                    onPreset = { settings = it },
                    onImport = { jsonPicker.launch(arrayOf("application/json", "text/*")) },
                    onExport = { jsonSaver.launch("JR-EQ-Preset.json") }
                )
            }
            Text("JR-EQUILZER • 32-band DSP • v3.0.0", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
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
    var presetMenu by remember { mutableStateOf(false) }
    var bandLock by remember { mutableStateOf(false) }

    SectionTitle("Graphic Equalizer", "Fixed-frequency 32-band engine • ±15.0 dB")
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Equalizer", modifier = Modifier.weight(1f), color = White, fontWeight = FontWeight.SemiBold)
            Switch(checked = settings.enabled, onCheckedChange = { onSettings(settings.copy(enabled = it)) })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(10, 16, 32).forEach { count ->
                val selected = settings.bands.size == count
                OutlinedButton(
                    onClick = {
                        val nextBands = DspSettings.logarithmicBands(count).map { band ->
                            band.copy(gainDb = DspSettings.gainAtFrequency(settings.bands, band.frequencyHz))
                        }
                        onSettings(settings.copy(bands = nextBands))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) Cyan.copy(alpha = 0.15f) else Color.Transparent,
                        contentColor = if (selected) Cyan else White
                    )
                ) { Text("$count-Band", fontSize = 10.sp) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    onSettings(settings.copy(
                        bands = settings.bands.map { it.copy(gainDb = 0.0) },
                        preampDb = 0.0,
                        bassDb = 0.0,
                        trebleDb = 0.0
                    ))
                },
                modifier = Modifier.weight(1f)
            ) { Text("Flat / Reset", fontSize = 10.sp) }
            OutlinedButton(
                onClick = {
                    val smoothed = settings.bands.mapIndexed { index, band ->
                        val previous = settings.bands.getOrNull(index - 1)?.gainDb ?: band.gainDb
                        val next = settings.bands.getOrNull(index + 1)?.gainDb ?: band.gainDb
                        band.copy(gainDb = ((previous + band.gainDb + next) / 3.0).coerceIn(-15.0, 15.0))
                    }
                    onSettings(settings.copy(bands = smoothed))
                },
                modifier = Modifier.weight(1f)
            ) { Text("Smooth", fontSize = 10.sp) }
            OutlinedButton(
                onClick = { onSettings(settings.copy(bands = settings.bands.map { it.copy(gainDb = -it.gainDb) })) },
                modifier = Modifier.weight(1f)
            ) { Text("Invert", fontSize = 10.sp) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Band Lock", color = White, modifier = Modifier.weight(1f), fontSize = 12.sp)
            Switch(checked = bandLock, onCheckedChange = { bandLock = it })
            Text(if (bandLock) "Locked" else "Editable", color = Muted, fontSize = 10.sp)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { presetMenu = true }) { Text("Presets") }
                DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                    BuiltInPresets.all.forEach { preset ->
                        DropdownMenuItem(text = { Text(preset.name) }, onClick = {
                            val presetBands = DspSettings.logarithmicBands(settings.bands.size).map { band ->
                                band.copy(gainDb = DspSettings.gainAtFrequency(preset.settings.bands, band.frequencyHz))
                            }
                            onSettings(preset.settings.copy(bands = presetBands))
                            presetMenu = false
                        })
                    }
                }
            }
            Text("Pre-EQ • 32 fixed bands", color = Cyan, fontSize = 10.sp, modifier = Modifier.weight(1f))
        }
        ValueSlider("Preamp", settings.preampDb.toFloat(), -24f, 12f, "dB") {
            onSettings(settings.copy(preampDb = it.toDouble()))
        }
    }

    PanelCard {
        SectionTitle("Precision faders", "Scroll horizontally • zero-dB center reference")
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            settings.bands.forEachIndexed { index, band ->
                BandFader(band = band, locked = bandLock) { value ->
                    onSettings(settings.copy(bands = settings.bands.mapIndexed { i, item ->
                        if (i == index) item.copy(gainDb = value.toDouble()) else item
                    }))
                }
            }
        }
    }

    PanelCard {
        SectionTitle("Live spectrum", "FFT from the active audio session")
        SpectrumBars(spectrum.toFloatArray(), Modifier.fillMaxWidth().height(74.dp))
    }
}

@Composable
private fun BandFader(band: EqBand, locked: Boolean, onChange: (Float) -> Unit) {
    val latestOnChange by rememberUpdatedState(onChange)
    val latestGain by rememberUpdatedState(band.gainDb.toFloat())
    Column(
        modifier = Modifier.width(42.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            String.format(java.util.Locale.US, "%+.1f", band.gainDb),
            color = if (locked) Muted else Cyan,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Canvas(
            modifier = Modifier.width(34.dp).height(144.dp)
                .pointerInput(locked) {
                    if (!locked) {
                        var pendingValue = latestGain.coerceIn(-15f, 15f)
                        var lastEmitted = pendingValue
                        var lastDispatchMs = 0L
                        fun valueAt(yPosition: Float): Float {
                            val y = yPosition.coerceIn(0f, size.height.toFloat())
                            var value = 15f - (y / size.height.toFloat()) * 30f
                            if (kotlin.math.abs(value) < 0.25f) value = 0f
                            return value.coerceIn(-15f, 15f)
                        }
                        fun emitPending() {
                            if (pendingValue != lastEmitted) {
                                latestOnChange(pendingValue)
                                lastEmitted = pendingValue
                                lastDispatchMs = android.os.SystemClock.uptimeMillis()
                            }
                        }
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                pendingValue = valueAt(offset.y)
                                lastEmitted = Float.NaN
                                emitPending()
                            },
                            onDragEnd = { emitPending() },
                            onDragCancel = { emitPending() },
                            onVerticalDrag = { change, _ ->
                                pendingValue = valueAt(change.position.y)
                                val now = android.os.SystemClock.uptimeMillis()
                                if (now - lastDispatchMs >= 16L) emitPending()
                                change.consume()
                            }
                        )
                    }
                }
        ) {
            val centerX = size.width / 2f
            val top = 5.dp.toPx()
            val bottom = size.height - 5.dp.toPx()
            val zeroY = (top + bottom) / 2f
            val gain = band.gainDb.toFloat().coerceIn(-15f, 15f)
            val knobY = zeroY - (gain / 15f) * (bottom - top) / 2f
            drawLine(Color(0xFF4A4A58), Offset(centerX, top), Offset(centerX, bottom), 4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Muted.copy(alpha = 0.9f), Offset(4.dp.toPx(), zeroY), Offset(size.width - 4.dp.toPx(), zeroY), 1.dp.toPx())
            if (!locked) {
                drawLine(Cyan, Offset(centerX, zeroY), Offset(centerX, knobY), 4.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(Purple, 7.dp.toPx(), Offset(centerX, knobY))
                drawCircle(Cyan, 3.dp.toPx(), Offset(centerX, knobY))
            } else {
                drawCircle(Muted, 6.dp.toPx(), Offset(centerX, knobY))
            }
        }
        Text(
            formatFrequency(band.frequencyHz),
            color = Muted,
            fontSize = 8.sp,
            maxLines = 1
        )
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
private fun ToneScreen(settings: DspSettings, onSettings: (DspSettings) -> Unit) {
    SectionTitle("Tone & Dynamics", "Bass, treble, stereo image and six-band dynamics")
    PanelCard {
        SectionTitle("Tone controls", "0–100% boost range with automatic headroom")
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            RotaryKnob("Bass", (settings.bassDb / 15.0 * 100.0).toFloat(), 0f, 100f, "%", modifier = Modifier.weight(1f)) {
                onSettings(settings.copy(bassDb = (it / 100.0 * 15.0).coerceIn(0.0, 15.0)))
            }
            RotaryKnob("Treble", (settings.trebleDb / 12.0 * 100.0).toFloat(), 0f, 100f, "%", modifier = Modifier.weight(1f)) {
                onSettings(settings.copy(trebleDb = (it / 100.0 * 12.0).coerceIn(0.0, 12.0)))
            }
        }
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            RotaryKnob("Stereo Width", settings.stereoWidthPercent.toFloat(), 0f, 200f, "%", modifier = Modifier.weight(1f)) {
                onSettings(settings.copy(stereoWidthPercent = it.toDouble()))
            }
            RotaryKnob("Balance", settings.balance.toFloat(), -1f, 1f, "L / R", modifier = Modifier.weight(1f)) {
                onSettings(settings.copy(balance = it.toDouble()))
            }
        }
    }
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Six-band compressor", color = White, fontWeight = FontWeight.Bold)
                Text("Controls are sent to the active session MBC stage", color = Muted, fontSize = 10.sp)
            }
            Switch(checked = settings.compressor.enabled, onCheckedChange = {
                onSettings(settings.copy(compressor = settings.compressor.copy(enabled = it)))
            })
        }
        ValueSlider("Threshold", settings.compressor.thresholdDb.toFloat(), -40f, 0f, "dB") {
            onSettings(settings.copy(compressor = settings.compressor.copy(thresholdDb = it.toDouble())))
        }
        ValueSlider("Ratio", settings.compressor.ratio.toFloat(), 1f, 20f, ":1") {
            onSettings(settings.copy(compressor = settings.compressor.copy(ratio = it.toDouble())))
        }
        ValueSlider("Attack", settings.compressor.attackMs.toFloat(), 1f, 200f, "ms") {
            onSettings(settings.copy(compressor = settings.compressor.copy(attackMs = it.toDouble())))
        }
        ValueSlider("Release", settings.compressor.releaseMs.toFloat(), 10f, 1_000f, "ms") {
            onSettings(settings.copy(compressor = settings.compressor.copy(releaseMs = it.toDouble())))
        }
        ValueSlider("Makeup gain", settings.compressor.makeupDb.toFloat(), -12f, 6f, "dB") {
            onSettings(settings.copy(compressor = settings.compressor.copy(makeupDb = it.toDouble())))
        }
        HorizontalDivider(color = Color(0xFF343440))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Noise gate", modifier = Modifier.weight(1f), color = White)
            Switch(checked = settings.compressor.noiseGateEnabled, onCheckedChange = {
                onSettings(settings.copy(compressor = settings.compressor.copy(noiseGateEnabled = it)))
            })
        }
        ValueSlider("Gate threshold", settings.compressor.noiseGateThresholdDb.toFloat(), -100f, -30f, "dBFS") {
            onSettings(settings.copy(compressor = settings.compressor.copy(noiseGateThresholdDb = it.toDouble())))
        }
        HorizontalDivider(color = Color(0xFF343440))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Limiter", modifier = Modifier.weight(1f), color = White)
            Switch(checked = settings.compressor.limiterEnabled, onCheckedChange = {
                onSettings(settings.copy(compressor = settings.compressor.copy(limiterEnabled = it)))
            })
        }
        ValueSlider("Ceiling", settings.compressor.limiterCeilingDb.toFloat(), -12f, 0f, "dB") {
            onSettings(settings.copy(compressor = settings.compressor.copy(limiterCeilingDb = it.toDouble())))
        }
    }
}

@Composable
private fun RotaryKnob(
    title: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    suffix: String,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit
) {
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onChange)
    Column(modifier = modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier.size(104.dp).pointerInput(minimum, maximum) {
                var dragValue = latestValue
                detectDragGestures(
                    onDragStart = { dragValue = latestValue },
                    onDrag = { change, drag ->
                        dragValue = (dragValue - drag.y * (maximum - minimum) / 190f).coerceIn(minimum, maximum)
                        latestOnChange(dragValue)
                        change.consume()
                    }
                )
            }
        ) {
            val stroke = 8.dp.toPx()
            val start = -225f
            val sweep = 270f
            drawArc(Color(0xFF393946), start, sweep, false, style = Stroke(stroke, cap = StrokeCap.Round))
            val fraction = ((value - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
            drawArc(Cyan, start, sweep * fraction, false, style = Stroke(stroke, cap = StrokeCap.Round))
            val angle = Math.toRadians((start + sweep * fraction).toDouble())
            val radius = size.minDimension * 0.34f
            val point = Offset(size.width / 2f + kotlin.math.cos(angle).toFloat() * radius, size.height / 2f + kotlin.math.sin(angle).toFloat() * radius)
            drawCircle(Purple, 5.dp.toPx(), point)
            drawCircle(Color(0xFF262633), 30.dp.toPx(), Offset(size.width / 2f, size.height / 2f))
            drawCircle(Cyan.copy(alpha = 0.18f), 24.dp.toPx(), Offset(size.width / 2f, size.height / 2f))
        }
        Text(title, color = Muted, fontSize = 11.sp)
        Text(formatValue(value) + " " + suffix, color = White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun ValueSlider(title: String, value: Float, minimum: Float, maximum: Float, suffix: String, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = White, modifier = Modifier.weight(1f), fontSize = 12.sp)
            Text(formatValue(value) + " " + suffix, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.coerceIn(minimum, maximum),
            onValueChange = onChange,
            valueRange = minimum..maximum
        )
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
    SectionTitle("Settings & Profiles", "Audio routing, DSP headroom and per-device presets")
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
            "Player detection and session attachment remain separate from the optional output-mix fallback.",
            color = Muted, fontSize = 10.sp
        )
    }
    PanelCard {
        SectionTitle("DVC headroom manager", "Software input-gain compensation")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("DVC / Dynamic Headroom", color = White, fontWeight = FontWeight.Bold)
                Text("Attenuates DSP input before EQ boosts to reduce clipping", color = Muted, fontSize = 10.sp)
            }
            Switch(checked = settings.dvcEnabled, onCheckedChange = {
                onSettings(settings.copy(dvcEnabled = it))
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-Preamp", modifier = Modifier.weight(1f), color = White)
            Switch(checked = settings.autoPreampEnabled, onCheckedChange = {
                onSettings(settings.copy(autoPreampEnabled = it))
            })
        }
        ValueSlider("Manual preamp", settings.preampDb.toFloat(), -24f, 12f, "dB") {
            onSettings(settings.copy(preampDb = it.toDouble()))
        }
        Text(
            "This is software DSP headroom control. It does not bypass Android's system mixer or change the phone's hardware volume.",
            color = Muted, fontSize = 10.sp
        )
    }
    PanelCard {
        SectionTitle("Tone frequency customization", "Frequency and Q controls are saved with each device profile")
        ValueSlider("Bass frequency", settings.bassFrequencyHz.toFloat(), 20f, 150f, "Hz") {
            onSettings(settings.copy(bassFrequencyHz = it.toDouble()))
        }
        ValueSlider("Bass Q-factor", settings.bassQFactor.toFloat(), 0.3f, 3f, "Q") {
            onSettings(settings.copy(bassQFactor = it.toDouble()))
        }
        ValueSlider("Treble frequency", settings.trebleFrequencyHz.toFloat(), 4_000f, 16_000f, "Hz") {
            onSettings(settings.copy(trebleFrequencyHz = it.toDouble()))
        }
        ValueSlider("Treble Q-factor", settings.trebleQFactor.toFloat(), 0.3f, 3f, "Q") {
            onSettings(settings.copy(trebleQFactor = it.toDouble()))
        }
        Text(
            "Q-factor is used by the offline RBJ shelf filters. Android's live DynamicsProcessing EqBand exposes cutoff frequency and gain, but no Q parameter.",
            color = Muted, fontSize = 10.sp
        )
    }
    PanelCard {
        SectionTitle("Limiter mode")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(if (settings.hardLimiter) "Hard limiter" else "Soft limiter", color = White, fontWeight = FontWeight.Bold)
                Text("Hard is a firmer ceiling; Soft has a gentler knee", color = Muted, fontSize = 10.sp)
            }
            Switch(checked = settings.hardLimiter, onCheckedChange = {
                onSettings(settings.copy(hardLimiter = it))
            })
        }
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
                            bands = DspSettings.logarithmicBands(settings.bands.size).map { band ->
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
