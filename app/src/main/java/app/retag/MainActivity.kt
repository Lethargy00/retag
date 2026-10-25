package app.retag

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.retag.ui.BatchScreen
import app.retag.ui.BulkEditScreen
import app.retag.ui.EditorScreen
import app.retag.ui.LibraryScreen
import app.retag.ui.RetagTheme

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
        vm.refreshPermission()
    }
}

@Composable
fun App(vm: AppViewModel) {
    if (!vm.hasPermission) {
        PermissionScreen(onResult = { vm.refreshPermission() })
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
        }
    }
}

@Composable
private fun PermissionScreen(onResult: () -> Unit) {
    val ctx = LocalContext.current
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onResult() }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Retag needs access to your music files", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            "Tags are written directly into your audio files, so the app needs permission to change files on your storage. " +
                "On the next screen, turn on \"Allow access to manage all files\" for Retag, then come back.",
            textAlign = TextAlign.Center,
        )
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    )
                } catch (e: Exception) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else {
                legacyLauncher.launch(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                )
            }
        }) { Text("Grant access") }
    }
}
