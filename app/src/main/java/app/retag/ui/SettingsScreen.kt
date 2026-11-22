package app.retag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.retag.AppViewModel
import app.retag.data.Naming
import app.retag.data.Prefs
import app.retag.rememberFolderPicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val pickFolder = rememberFolderPicker { vm.setFolder(it) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("Music folder")
            Text(vm.folderName.ifBlank { "None" }, style = MaterialTheme.typography.titleMedium)
            Text(
                "Retag only sees and changes files inside this folder and its subfolders.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = pickFolder) { Text("Change folder") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionLabel("Online lookups")
            SwitchRow("Prefer English titles and names", Prefs.preferEnglish) { Prefs.preferEnglish = it }
            Text(
                "When an album or artist also has an English (or Latin-letter) version online, use that instead of the original script, " +
                    "for example Hikaru Utada instead of 宇多田ヒカル. Matching your files still works with either.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionLabel("Rename files")
            SwitchRow("Rename files when saving tags", Prefs.renameOn) { Prefs.renameOn = it }
            PatternField(
                label = "File name pattern",
                value = Prefs.renamePattern,
                default = Prefs.DEFAULT_RENAME,
                onChange = { Prefs.renamePattern = it },
            )
            Preview("Example: " + (Naming.fileBase(Naming.SAMPLE) ?: "(keeps the current name)") + ".mp3")

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionLabel("Sort into folders")
            SwitchRow("Move files into folders when saving tags", Prefs.organizeOn) { Prefs.organizeOn = it }
            PatternField(
                label = "Folder pattern",
                value = Prefs.folderPattern,
                default = Prefs.DEFAULT_FOLDERS,
                onChange = { Prefs.folderPattern = it },
            )
            Preview(
                "Example: " + (listOf(vm.folderName.ifBlank { "Music" }) + Naming.folders(Naming.SAMPLE)).joinToString("/") + "/"
            )
            Text(
                "Empty folders left behind are removed. Missing artist or album becomes \"Unknown artist\" / \"Unknown album\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionLabel("How patterns work")
            Text(
                "%title% %artist% %albumartist% %album% %year% %track% %disc% %genre% are replaced with the song's tags. " +
                    "%track% is always two digits (01, 02…). %albumartist% uses the artist when it's empty, which keeps albums together.\n\n" +
                    "Put optional parts in [square brackets]: they're left out when a field inside is empty. " +
                    "\"%title%[ - %year%]\" gives \"Song - 1999\", or just \"Song\" if there's no year.\n\n" +
                    "In folder patterns, / starts a new folder.\n\n" +
                    "Renaming happens when you save in the editor, edit many files, or auto-tag. " +
                    "For songs you already have: long-press to select, then ⋮ → Rename & organize.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatternField(label: String, value: String, default: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in Naming.TOKENS) {
            AssistChip(onClick = { onChange("$value%$t%") }, label = { Text(t) })
        }
        if (value != default) {
            TextButton(onClick = { onChange(default) }) { Text("Reset") }
        }
    }
}

@Composable
private fun Preview(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}
