package app.retag

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.retag.ui.BatchScreen
import app.retag.ui.BulkEditScreen
import app.retag.ui.EditorScreen
import app.retag.ui.LibraryScreen
import app.retag.ui.RetagTheme
import app.retag.ui.SettingsScreen
import app.retag.data.Store

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RetagTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshAccess()
    }
}

@Composable
fun App(vm: AppViewModel) {
    if (!vm.hasFolder) {
        FolderScreen(onPicked = { vm.setFolder(it) })
        return
    }
    LaunchedEffect(Unit) {
        if (!vm.scannedOnce) vm.loadLibrary()
    }
    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }
    val screen = vm.backStack.last()
    key(screen) {
        when (screen) {
            is Screen.Library -> LibraryScreen(vm)
            is Screen.Editor -> EditorScreen(vm, screen.path)
            is Screen.Batch -> BatchScreen(vm)
            is Screen.BulkEdit -> BulkEditScreen(vm, screen.paths)
            is Screen.Settings -> SettingsScreen(vm)
        }
    }
}

/** Opens Android's folder picker. Returns a launcher; call launch() on it. */
@Composable
fun rememberFolderPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onPicked(uri)
    }
    return {
        try {
            launcher.launch(Store.suggestedStart())
        } catch (e: Exception) {
            launcher.launch(null)
        }
    }
}

@Composable
private fun FolderScreen(onPicked: (Uri) -> Unit) {
    val pick = rememberFolderPicker(onPicked)
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Choose your music folder", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            "Retag only works inside the folder you pick, including its subfolders. " +
                "On the next screen open the folder with your music (for example Music) and tap \"Use this folder\", then Allow.",
            textAlign = TextAlign.Center,
        )
        Text(
            "Android doesn't let apps pick the whole storage or the Download folder, so keep your music in a folder such as Music.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = pick) { Text("Choose folder") }
    }
}
