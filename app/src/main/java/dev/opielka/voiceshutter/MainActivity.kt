package dev.opielka.voiceshutter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                SetupScreen()
            }
        }
    }
}

private data class SetupState(
    val accessibilityEnabled: Boolean,
    val microphoneGranted: Boolean,
    val batteryExempt: Boolean,
)

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val enabled by prefs.enabled.collectAsState(initial = false)

    var state by remember { mutableStateOf(context.readSetupState()) }
    RefreshOnResume { state = context.readSetupState() }

    val requestMicrophone = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { state = context.readSetupState() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Voice Shutter", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Powiedz „smile” przy otwartym aparacie, żeby zrobić zdjęcie.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Nasłuch włączony", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = enabled,
                    onCheckedChange = { value -> scope.launch { prefs.setEnabled(value) } },
                )
            }
        }

        KeywordsCard(prefs)

        DelayCard(prefs)

        RequirementCard(
            title = "Usługa dostępności",
            satisfied = state.accessibilityEnabled,
            explanation = "Pozwala kliknąć spust w aplikacji aparatu i wykryć, że aparat jest otwarty.",
            actionLabel = "Otwórz ustawienia dostępności",
            onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        )

        RequirementCard(
            title = "Mikrofon",
            satisfied = state.microphoneGranted,
            explanation = "Potrzebny do rozpoznania słowa „smile”. Nagranie nigdy nie opuszcza telefonu.",
            actionLabel = "Nadaj uprawnienie",
            onAction = { requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) },
        )

        RequirementCard(
            title = "Wyjątek od optymalizacji baterii",
            satisfied = state.batteryExempt,
            explanation = "Bez niego system nie pozwoli uruchomić nasłuchu automatycznie po otwarciu aparatu.",
            actionLabel = "Otwórz ustawienia baterii",
            onAction = {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            },
        )

        Text(
            "Na Honorze dodatkowo: Ustawienia → Bateria → Uruchamianie aplikacji → " +
                "Voice Shutter → Zarządzaj ręcznie (wszystkie trzy przełączniki).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DelayCard(prefs: Prefs) {
    val scope = rememberCoroutineScope()
    val storedDelay by prefs.shutterDelayMs.collectAsState(initial = ShutterDelay.DEFAULT_MS)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Opóźnienie migawki", style = MaterialTheme.typography.titleMedium)
                Text(ShutterDelay.label(storedDelay), style = MaterialTheme.typography.bodyMedium)
            }

            Slider(
                value = ShutterDelay.indexOf(storedDelay).toFloat(),
                onValueChange = { position ->
                    val delay = ShutterDelay.atIndex(position.toInt())
                    if (delay != storedDelay) scope.launch { prefs.setShutterDelayMs(delay) }
                },
                valueRange = 0f..(ShutterDelay.OPTIONS_MS.size - 1).toFloat(),
                steps = ShutterDelay.steps,
            )

            Text(
                "Czas między rozpoznaniem hasła a zdjęciem. Bez opóźnienia aparat łapie " +
                    "moment, w którym jeszcze wymawiasz słowo — stąd zalecane 0,5 s.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun KeywordsCard(prefs: Prefs) {
    val scope = rememberCoroutineScope()
    val stored by prefs.keywordsRaw.collectAsState(initial = KeywordList.DEFAULT)
    var draft by remember { mutableStateOf(stored) }
    LaunchedEffect(stored) { draft = stored }

    val parsed = KeywordList.parse(draft)
    val rejected = KeywordList.rejected(draft)
    val dirty = draft != stored

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Hasła wyzwalające", style = MaterialTheme.typography.titleMedium)
            Text(
                "Oddziel przecinkami. Model rozumie tylko angielski.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = draft,
                onValueChange = { if (!KeywordList.isTooLong(it)) draft = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(KeywordList.DEFAULT) },
                supportingText = { Text("${draft.length} / ${KeywordList.MAX_LENGTH}") },
                isError = parsed.isEmpty(),
            )

            Text(
                text = if (parsed.isEmpty()) {
                    "Brak poprawnych haseł — nasłuch użyje „${KeywordList.DEFAULT}”."
                } else {
                    "Aktywne: ${parsed.joinToString(", ")}"
                },
                style = MaterialTheme.typography.bodySmall,
            )

            if (rejected.isNotEmpty()) {
                Text(
                    "Pominięte (dozwolone są tylko litery, spacje i apostrofy): " +
                        rejected.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                "Rzadkie lub nieanglojęzyczne słowa mogą nie zadziałać — model przyjmie je " +
                    "bez ostrzeżenia, ale nigdy ich nie rozpozna. Sprawdź każde nowe hasło.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scope.launch { prefs.setKeywords(draft) } },
                    enabled = dirty,
                ) { Text("Zapisz") }

                if (dirty) {
                    TextButton(onClick = { draft = stored }) { Text("Cofnij") }
                }
            }
        }
    }
}

@Composable
private fun RequirementCard(
    title: String,
    satisfied: Boolean,
    explanation: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (satisfied) "$title — OK" else "$title — brak",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(explanation, style = MaterialTheme.typography.bodySmall)
            if (!satisfied) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** Permissions are granted on other screens, so the state has to be re-read on return. */
@Composable
private fun RefreshOnResume(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private fun Context.readSetupState() = SetupState(
    accessibilityEnabled = isAccessibilityServiceEnabled(),
    microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED,
    batteryExempt = getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(packageName),
)

private fun Context.isAccessibilityServiceEnabled(): Boolean {
    val expected = "$packageName/${ShutterAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}
