package com.aibuilt.facefusion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Build constants (this app ships build 62, arm64-v8a only)
// ---------------------------------------------------------------------------
const val BUILD_NUMBER = 62
const val MAX_FACE_PHOTOS = 5
const val APP_ID = "com.facefusion.mobile.debug"
const val APP_LABEL = "FaceFusion Debug"
const val ABI_TAG = "arm64-v8a"
const val PINNED_KOTLIN = "2.0.21"
const val PINNED_NDK = "27.2.12479018"
const val PINNED_CMAKE = "3.22.1"
const val PINNED_SDK = "35"
const val QNN_VERSION = "2.49"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FaceFusionTheme {
                FaceFusionApp()
            }
        }
    }
}

@Composable
fun FaceFusionTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Color(0xFF8AB4F8),
        onPrimary = Color(0xFF0B1B33),
        secondary = Color(0xFF9BE7C4),
        background = Color(0xFF10131A),
        surface = Color(0xFF161B24),
        onBackground = Color(0xFFE3E6EC),
        onSurface = Color(0xFFE3E6EC)
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
class FaceEntry(val id: Int, val name: String)

class AppState {
    val faces = mutableStateListOf<FaceEntry>()
    val logs = mutableStateListOf<String>()

    var selectedFaceId by mutableStateOf<Int?>(null)
    var targetName by mutableStateOf<String?>(null)
    var swapping by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var resultName by mutableStateOf<String?>(null)
    var status by mutableStateOf("Idle - add up to $MAX_FACE_PHOTOS face photos")

    private var nextFaceId = 1
    private var nextTargetId = 1

    fun log(line: String) {
        logs.add(0, line)
        while (logs.size > 40) {
            logs.removeAt(logs.lastIndex)
        }
    }

    fun addFace() {
        if (faces.size >= MAX_FACE_PHOTOS) {
            status = "Limit reached: $MAX_FACE_PHOTOS face photos per project"
            log("limit reached ($MAX_FACE_PHOTOS)")
            return
        }
        val id = nextFaceId++
        val entry = FaceEntry(id, "face_" + id.toString().padStart(2, '0') + ".jpg")
        faces.add(entry)
        if (selectedFaceId == null) {
            selectedFaceId = id
        }
        status = "Added ${entry.name} - ${faces.size} of $MAX_FACE_PHOTOS selected"
        log("embed ${entry.name}")
    }

    fun removeFace(id: Int) {
        val removed = faces.firstOrNull { it.id == id } ?: return
        faces.remove(removed)
        if (selectedFaceId == id) {
            selectedFaceId = faces.firstOrNull()?.id
        }
        status = "Removed ${removed.name} - ${faces.size} of $MAX_FACE_PHOTOS selected"
        log("drop ${removed.name}")
    }

    fun pickTarget() {
        val id = nextTargetId++
        val name = "target_" + id.toString().padStart(2, '0') + ".jpg"
        targetName = name
        status = "Target image loaded: $name"
        log("target $name")
    }

    fun begin(source: String, target: String) {
        swapping = true
        progress = 0f
        resultName = null
        status = "Running pipeline on $ABI_TAG ..."
        log("swap $source -> $target")
    }

    fun finish() {
        val out = "swapped_" + (targetName ?: "target.png")
        swapping = false
        progress = 1f
        resultName = out
        status = "Swap complete"
        log("output $out")
    }

    fun reset() {
        faces.clear()
        logs.clear()
        selectedFaceId = null
        targetName = null
        swapping = false
        progress = 0f
        resultName = null
        nextFaceId = 1
        nextTargetId = 1
        status = "Idle - add up to $MAX_FACE_PHOTOS face photos"
    }
}

// ---------------------------------------------------------------------------
// App shell
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceFusionApp() {
    val state = remember { AppState() }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FaceFusion") },
                actions = {
                    Text(
                        text = "build $BUILD_NUMBER",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Person, null) },
                    label = { Text("Faces") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.SwapHoriz, null) },
                    label = { Text("Swap") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Info, null) },
                    label = { Text("Build") }
                )
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (tab) {
                0 -> FacesScreen(state)
                1 -> SwapScreen(state) { runPipeline(state, scope) }
                else -> BuildInfoScreen()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pipeline driver (simulated stages, no native call is made here)
// ---------------------------------------------------------------------------
fun runPipeline(state: AppState, scope: CoroutineScope) {
    val source = state.faces.firstOrNull { it.id == state.selectedFaceId }
    if (source == null) {
        state.status = "Pick a source face first"
        state.log("error: no source face selected")
        return
    }
    val target = state.targetName
    if (target == null) {
        state.status = "Pick a target image first"
        state.log("error: no target image")
        return
    }
    if (state.swapping) {
        return
    }
    state.begin(source.name, target)
    scope.launch {
        for (step in 1..10) {
            delay(150)
            state.progress = step / 10f
            when (step) {
                3 -> state.log("detector: ${state.faces.size} face(s) embedded")
                6 -> state.log("recogniser: 512-d vector matched")
                9 -> state.log("swapper: blending with QNN $QNN_VERSION")
            }
        }
        state.finish()
    }
}

// ---------------------------------------------------------------------------
// Faces tab - up to five photos
// ---------------------------------------------------------------------------
@Composable
fun FacesScreen(state: AppState) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Face photos", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pick up to $MAX_FACE_PHOTOS source faces. Each one is embedded once and can be reused for every swap.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { state.addFace() },
                        enabled = state.faces.size < MAX_FACE_PHOTOS && !state.swapping
                    ) {
                        Icon(Icons.Filled.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add photo")
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "${state.faces.size} / $MAX_FACE_PHOTOS",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.faces.isEmpty()) {
                item {
                    Text(
                        "No photos yet. Tap \"Add photo\" to create a slot.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            items(state.faces, key = { it.id }) { entry ->
                FaceRow(
                    entry = entry,
                    selected = entry.id == state.selectedFaceId,
                    onSelect = { state.selectedFaceId = entry.id },
                    onRemove = { state.removeFace(entry.id) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(state.status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun FaceRow(
    entry: FaceEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (selected) "primary source face" else "tap to make it the source",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, "remove ${entry.name}")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Swap tab
// ---------------------------------------------------------------------------
@Composable
fun SwapScreen(state: AppState, onRun: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Source face", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (state.faces.isEmpty()) {
            Text(
                "No face photos yet. Add them on the Faces tab.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            state.faces.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { state.selectedFaceId = entry.id }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = entry.id == state.selectedFaceId,
                        onClick = { state.selectedFaceId = entry.id }
                    )
                    Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Target image", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { state.pickTarget() }, enabled = !state.swapping) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Pick target")
            }
            Spacer(Modifier.width(12.dp))
            Text(state.targetName ?: "none", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onRun, enabled = !state.swapping) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Run swap")
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { state.reset() }, enabled = !state.swapping) {
                Icon(Icons.Filled.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("Reset")
            }
        }

        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(state.status, style = MaterialTheme.typography.bodyMedium)

        state.resultName?.let { result ->
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Result: $result", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Pipeline log", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(state.logs) { line ->
                Text(
                    text = "- $line",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Build tab
// ---------------------------------------------------------------------------
@Composable
fun BuildInfoScreen() {
    val rows = listOf(
        "Application ID" to APP_ID,
        "App label" to APP_LABEL,
        "Build number" to BUILD_NUMBER.toString(),
        "ABI" to ABI_TAG,
        "Kotlin" to PINNED_KOTLIN,
        "NDK" to PINNED_NDK,
        "CMake" to PINNED_CMAKE,
        "compileSdk / targetSdk" to PINNED_SDK,
        "QNN runtime" to QNN_VERSION,
        "Max face photos" to MAX_FACE_PHOTOS.toString()
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Build $BUILD_NUMBER", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Pinned toolchain - the app never upgrades or downgrades these values.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                rows.forEachIndexed { index, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(row.first, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            row.second,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (index != rows.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
