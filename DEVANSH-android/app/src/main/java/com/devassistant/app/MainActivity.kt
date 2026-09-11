package com.devassistant.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.devassistant.app.data.ActivityLogStore
import com.devassistant.app.data.SettingsStore
import com.devassistant.app.data.TaskStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results observed via hasRequiredPermissions() on next recomposition trigger */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsStore(this)

        setContent {
            var tab by remember { mutableStateOf(0) }
            MaterialTheme {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, label = { Text("DEVANSH") }, icon = {})
                            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Activity") }, icon = {})
                            NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, label = { Text("Settings") }, icon = {})
                        }
                    }
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (tab) {
                            0 -> HomeScreen(
                                hasAccessibility = { isAccessibilityServiceEnabled() },
                                hasMicPermission = { hasMicPermission() },
                                onRequestMic = { requestMicPermission() },
                                onOpenAccessibilitySettings = { openAccessibilitySettings() },
                                onStartService = { startForegroundService(Intent(this@MainActivity, WakeWordService::class.java)) },
                                onStopService = { stopService(Intent(this@MainActivity, WakeWordService::class.java)) }
                            )
                            1 -> ActivityScreen()
                            2 -> SettingsScreen(settings)
                        }
                    }
                }
            }
        }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions.launch(perms.toTypedArray())
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${DevanshAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}

@Composable
fun HomeScreen(
    hasAccessibility: () -> Boolean,
    hasMicPermission: () -> Boolean,
    onRequestMic: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    var serviceRunning by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("DEVANSH", style = MaterialTheme.typography.headlineLarge)
        Text(if (serviceRunning) "🎙 Listening for \"Hey DEVANSH\"" else "Idle", style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(8.dp))

        if (!hasMicPermission()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Microphone permission is required for voice control.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequestMic) { Text("Grant microphone access") }
                }
            }
        }

        if (!hasAccessibility()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Accessibility Service is required so DEVANSH can see the screen and tap/type on your behalf.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenAccessibilitySettings) { Text("Enable Accessibility Service") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                if (serviceRunning) onStopService() else onStartService()
                serviceRunning = !serviceRunning
            },
            enabled = hasMicPermission() && hasAccessibility()
        ) {
            Text(if (serviceRunning) "Stop listening" else "Start listening (\"Hey DEVANSH\")")
        }

        Text(
            "Try: \"Hey DEVANSH, open Chrome\" or \"Hey DEVANSH, open YouTube and search for Python tutorials.\"",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun ActivityScreen() {
    val entries by ActivityLogStore.entries.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Activity", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { ActivityLogStore.clear() }) { Text("Clear") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries.reversed()) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${entry.timestamp} — ${entry.command}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when (entry.status) {
                                TaskStatus.RUNNING -> "Executing…"
                                TaskStatus.COMPLETED -> "Completed"
                                TaskStatus.FAILED -> "Failed: ${entry.error ?: ""}"
                                TaskStatus.NEEDS_USER -> "Waiting on you"
                            }
                        )
                        entry.steps.forEach { step -> Text("• $step", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(settings: SettingsStore) {
    val scope = rememberCoroutineScope()
    val savedKey by settings.apiKey.collectAsState(initial = "")
    var apiKeyField by remember(savedKey) { mutableStateOf(savedKey) }
    val wakeWordEnabled by settings.wakeWordEnabled.collectAsState(initial = true)
    val confirmHighRisk by settings.confirmHighRisk.collectAsState(initial = true)

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = apiKeyField,
            onValueChange = { apiKeyField = it },
            label = { Text("Google AI (Gemini) API key") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { scope.launch { settings.setApiKey(apiKeyField.trim()) } }) { Text("Save API key") }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = wakeWordEnabled, onCheckedChange = { scope.launch { settings.setWakeWordEnabled(it) } })
            Spacer(Modifier.width(8.dp))
            Text("Wake word (\"Hey DEVANSH\") enabled")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = confirmHighRisk, onCheckedChange = { scope.launch { settings.setConfirmHighRisk(it) } })
            Spacer(Modifier.width(8.dp))
            Text("Ask before high-risk actions (payments, deletions, sending messages)")
        }

        Text(
            "DEVANSH sends your spoken command and a text description of what's on screen (button " +
                "labels, not screenshots) to Google's Gemini API to decide what to do next. It never " +
                "sends your API key anywhere except generativelanguage.googleapis.com. Get a free " +
                "key at aistudio.google.com.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
