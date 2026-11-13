package com.jr.equalizer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val audioRuntime by AudioRuntimeStatusRepository.state.collectAsState()
    var tab by remember { mutableStateOf(MainTab.EQUALIZER) }
    var settings by remember { mutableStateOf(DspSettingsStore.load(context)) }
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
    var previewStatus by remember { mutableStateOf("Preview not started") }
    var dvcEnabled by remember { mutableStateOf(false) }

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
        playerEffect = null
        scope.launch(Dispatchers.IO) { runCatching { effectToRelease?.release() } }
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
                    if (effect == null) previewStatus = "Preview active; device DSP attachment unavailable."
                    prepared.start()
                    previewStatus = if (effect != null) "Preview playing with JR-EQUILZER settings"
                    else "Preview playing without session DSP"
                }
            }
            media.setOnCompletionListener {
                previewStatus = "Preview complete"
                val effectToRelease = playerEffect
                playerEffect = null
                scope.launch(Dispatchers.IO) { runCatching { effectToRelease?.release() } }
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

    LaunchedEffect(settings) {
        delay(20)
        withContext(Dispatchers.IO) { DspSettingsStore.save(context, settings) }
    }

    LaunchedEffect(settings, globalEnabled) {
        if (globalEnabled) {
            delay(16)
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
                MainTab.EQUALIZER -> EqualizerScreen(settings, audioRuntime.spectrum.toFloatArray(), onSettings = { settings = it })
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
                    dvcEnabled = dvcEnabled,
                    onGlobal = { toggleGlobal(it) },
                    onDvc = { dvcEnabled = it },
                    onPreset = { settings = it },
                    onImport = { jsonPicker.launch(arrayOf("application/json", "text/*")) },
                    onExport = { jsonSaver.launch("JR-EQ-Preset.json") }
                )
            }
            Text("JR-EQUILZER • Offline DSP • v2.0.0", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
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
private fun EqualizerScreen(settings: DspSettings, spectrum: FloatArray, onSettings: (DspSettings) -> Unit) {
    var presetMenu by remember { mutableStateOf(false) }
    var bandsMenu by remember { mutableStateOf(false) }
    SectionTitle("Equalizer", "Parametric response • 20 Hz – 20 kHz")
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Equalizer", modifier = Modifier.weight(1f), color = White, fontWeight = FontWeight.SemiBold)
            Switch(checked = settings.enabled, onCheckedChange = { onSettings(settings.copy(enabled = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { presetMenu = true }) { Text("Presets") }
                DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                    BuiltInPresets.all.forEach { preset ->
                        DropdownMenuItem(text = { Text(preset.name) }, onClick = {
                            onSettings(preset.settings)
                            presetMenu = false
                        })
                    }
                }
            }
            Box {
                OutlinedButton(onClick = { bandsMenu = true }) { Text(settings.bands.size.toString() + " bands") }
                DropdownMenu(expanded = bandsMenu, onDismissRequest = { bandsMenu = false }) {
                    listOf(5, 10, 15, 20, 31, 32).forEach { count ->
                        DropdownMenuItem(text = { Text(count.toString() + " bands") }, onClick = {
                            val next = DspSettings.logarithmicBands(count).mapIndexed { i, b ->
                                b.copy(gainDb = settings.bands.getOrNull(i)?.gainDb ?: 0.0)
                            }
                            onSettings(settings.copy(bands = next))
                            bandsMenu = false
                        })
                    }
                }
            }
            Text("RBJ DSP", color = Cyan, fontSize = 10.sp, modifier = Modifier.weight(1f))
        }
        ParametricGraph(
            bands = settings.bands,
            spectrum = spectrum,
            modifier = Modifier.fillMaxWidth().height(174.dp),
            onBandChange = { index, value ->
                onSettings(settings.copy(bands = settings.bands.mapIndexed { i, b -> if (i == index) b.copy(gainDb = value.toDouble()) else b }))
            }
        )
        ValueSlider("Preamp", settings.preampDb.toFloat(), -24f, 12f, "dB") {
            onSettings(settings.copy(preampDb = it.toDouble()))
        }
    }
    PanelCard {
        SectionTitle("Band controls", "Drag a fader to shape each frequency range")
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            settings.bands.forEachIndexed { index, band ->
                BandFader(band = band, index = index, onChange = { value ->
                    onSettings(settings.copy(bands = settings.bands.mapIndexed { i, b -> if (i == index) b.copy(gainDb = value.toDouble()) else b }))
                })
            }
        }
    }
    PanelCard {
        SectionTitle("Live spectrum", "FFT display updates when audio is processed in the exporter")
        SpectrumBars(spectrum, Modifier.fillMaxWidth().height(74.dp))
    }
}

@Composable
private fun ParametricGraph(
    bands: List<EqBand>,
    spectrum: FloatArray,
    modifier: Modifier,
    onBandChange: (Int, Float) -> Unit
) {
    val latestBands by rememberUpdatedState(bands)
    val latestOnChange by rememberUpdatedState(onBandChange)
    Box(
        modifier = modifier
            .background(PanelRaised, RoundedCornerShape(14.dp))
            .drawWithCache {
                val padding = 10.dp.toPx()
                val graphWidth = (size.width - padding * 2f).coerceAtLeast(1f)
                val graphHeight = (size.height - padding * 2f).coerceAtLeast(1f)
                val graphPath = Path()
                val points = bands.mapIndexed { index, band ->
                    val x = if (bands.size == 1) graphWidth / 2f
                    else graphWidth * index / (bands.size - 1).toFloat()
                    val y = graphHeight * (1f - ((band.gainDb.toFloat() + 12f) / 24f))
                    Offset(x + padding, y + padding)
                }
                if (points.isNotEmpty()) {
                    graphPath.moveTo(points.first().x, points.first().y)
                    for (index in 1 until points.size) {
                        val previous = points[index - 1]
                        val current = points[index]
                        val middle = (previous.x + current.x) / 2f
                        graphPath.quadraticTo(middle, previous.y, current.x, current.y)
                    }
                }
                onDrawBehind {
                    for (index in 0..4) {
                        val y = padding + graphHeight * index / 4f
                        drawLine(Color(0xFF363644), Offset(padding, y), Offset(size.width - padding, y), 1.dp.toPx())
                    }
                    for (index in 0..6) {
                        val x = padding + graphWidth * index / 6f
                        drawLine(Color(0xFF292936), Offset(x, padding), Offset(x, size.height - padding), 1.dp.toPx())
                    }
                    drawLine(Muted.copy(alpha = 0.55f), Offset(padding, size.height / 2f), Offset(size.width - padding, size.height / 2f), 1.dp.toPx())
                    val count = spectrum.size.coerceAtLeast(1)
                    val barGap = 2.dp.toPx()
                    val barWidth = (graphWidth - barGap * (count - 1)) / count
                    spectrum.forEachIndexed { index, level ->
                        val barHeight = graphHeight * level.coerceIn(0f, 1f) * 0.42f
                        drawRoundRect(
                            color = Cyan.copy(alpha = 0.13f),
                            topLeft = Offset(padding + index * (barWidth + barGap), size.height - padding - barHeight),
                            size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                        )
                    }
                    if (points.isNotEmpty()) {
                        drawPath(graphPath, Cyan, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                        points.forEach { drawCircle(Cyan, radius = 4.dp.toPx(), center = it) }
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val padding = 10.dp.toPx()
                    val width = (size.width - padding * 2f).coerceAtLeast(1f)
                    val height = (size.height - padding * 2f).coerceAtLeast(1f)
                    val x = (change.position.x - padding).coerceIn(0f, width)
                    val y = (change.position.y - padding).coerceIn(0f, height)
                    val currentBands = latestBands
                    if (currentBands.isNotEmpty()) {
                        val index = ((x / width) * currentBands.size).toInt().coerceIn(0, currentBands.lastIndex)
                        val gain = (12f - (y / height) * 24f).coerceIn(-12f, 12f)
                        latestOnChange(index, gain)
                    }
                    change.consume()
                }
            }
    )
}

@Composable
private fun BandFader(band: EqBand, index: Int, onChange: (Float) -> Unit) {
    val latestOnChange by rememberUpdatedState(onChange)
    Column(
        modifier = Modifier.width(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(band.gainDb.toInt().toString(), color = Cyan, fontSize = 10.sp)
        Canvas(
            modifier = Modifier.width(30.dp).height(126.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val y = change.position.y.coerceIn(0f, size.height.toFloat())
                        latestOnChange(12f - (y / size.height) * 24f)
                        change.consume()
                    }
                }
        ) {
            val centerX = size.width / 2f
            val top = 4.dp.toPx()
            val bottom = size.height - 4.dp.toPx()
            val zeroY = (top + bottom) / 2f
            val knobY = zeroY - (band.gainDb.toFloat() / 12f) * (bottom - top) / 2f
            drawLine(Color(0xFF4A4A58), Offset(centerX, top), Offset(centerX, bottom), 4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Purple, Offset(centerX, zeroY), Offset(centerX, knobY), 4.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Cyan, 7.dp.toPx(), Offset(centerX, knobY))
        }
        Text(formatFrequency(band.frequencyHz), color = Muted, fontSize = 8.sp, maxLines = 1)
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
    SectionTitle("Tone & Dynamics", "Shape tone, stereo field and dynamic range")
    PanelCard {
        SectionTitle("Tone controls", "Touch and drag each dial")
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            RotaryKnob("Bass", settings.bassDb.toFloat(), -18f, 18f, "dB", modifier = Modifier.weight(1f)) {
                onSettings(settings.copy(bassDb = it.toDouble()))
            }
            RotaryKnob("Treble", settings.trebleDb.toFloat(), -18f, 18f, "dB", modifier = Modifier.weight(1f)) {
                onSettings(settings.copy(trebleDb = it.toDouble()))
            }
        }
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            RotaryKnob("Stereo", settings.stereoWidthPercent.toFloat(), 0f, 200f, "%", modifier = Modifier.weight(1f)) {
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
                Text("Compressor", color = White, fontWeight = FontWeight.Bold)
                Text("Smooth level control", color = Muted, fontSize = 10.sp)
            }
            Switch(checked = settings.compressor.enabled, onCheckedChange = {
                onSettings(settings.copy(compressor = settings.compressor.copy(enabled = it)))
            })
        }
        ValueSlider("Threshold", settings.compressor.thresholdDb.toFloat(), -60f, 0f, "dB") {
            onSettings(settings.copy(compressor = settings.compressor.copy(thresholdDb = it.toDouble())))
        }
        ValueSlider("Ratio", settings.compressor.ratio.toFloat(), 1f, 20f, ":1") {
            onSettings(settings.copy(compressor = settings.compressor.copy(ratio = it.toDouble())))
        }
        ValueSlider("Attack", settings.compressor.attackMs.toFloat(), 0.1f, 200f, "ms") {
            onSettings(settings.copy(compressor = settings.compressor.copy(attackMs = it.toDouble())))
        }
        ValueSlider("Release", settings.compressor.releaseMs.toFloat(), 10f, 2000f, "ms") {
            onSettings(settings.copy(compressor = settings.compressor.copy(releaseMs = it.toDouble())))
        }
        ValueSlider("Makeup gain", settings.compressor.makeupDb.toFloat(), -12f, 18f, "dB") {
            onSettings(settings.copy(compressor = settings.compressor.copy(makeupDb = it.toDouble())))
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
        Text("Export uses the same Double-precision RBJ biquad coefficients as the offline DSP engine. M4A is AAC re-encoded.", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun SettingsScreen(
    globalEnabled: Boolean,
    globalStatus: String,
    dvcEnabled: Boolean,
    onGlobal: (Boolean) -> Unit,
    onDvc: (Boolean) -> Unit,
    onPreset: (DspSettings) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    var presetsExpanded by remember { mutableStateOf(false) }
    SectionTitle("Settings & Global Mode", "Playback integrations and preset library")
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Global equalization", color = White, fontWeight = FontWeight.Bold)
                Text("Try Android output-mix attachment", color = Muted, fontSize = 10.sp)
            }
            Switch(checked = globalEnabled, onCheckedChange = onGlobal)
        }
        Text(globalStatus, color = if (globalStatus.contains("accepted", true)) Cyan else Muted, fontSize = 11.sp)
        Text(
            "Android only permits effects on audio sessions exposed by the device or player. Session 0 may be rejected by Moto firmware; this switch reports that result and is not a guarantee of system-wide processing.",
            color = Muted, fontSize = 10.sp
        )
    }
    PanelCard {
        SectionTitle("Known players", "Detection and effect attachment are separate capabilities")
        listOf("YouTube Music", "Spotify", "Poweramp", "VLC", "Local music players").forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = White, modifier = Modifier.weight(1f), fontSize = 12.sp)
                Text("Detected when session is exposed", color = Muted, fontSize = 9.sp)
            }
        }
    }
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("DVC (Direct Volume Control)", color = White, fontWeight = FontWeight.Bold)
                Text("OEM / privileged audio routing capability", color = Muted, fontSize = 10.sp)
            }
            Switch(checked = dvcEnabled, onCheckedChange = onDvc)
        }
        Text(
            if (dvcEnabled) "Requested locally; Android does not grant true DVC routing to ordinary apps." else "Off. True DVC requires system / vendor integration on supported devices.",
            color = Muted, fontSize = 10.sp
        )
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
                        onPreset(preset.settings)
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
    if (frequency >= 1000.0) String.format(java.util.Locale.US, "%.1fk", frequency / 1000.0)
    else frequency.toInt().toString()

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
