package com.xarvis.ai

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xarvis.ai.ui.theme.XarvisCyan
import com.xarvis.ai.ui.theme.XarvisMuted
import com.xarvis.ai.ui.theme.XarvisTheme

/**
 * ☰ → Search folders: the folders XARVIS may search ("search my phone for ..."), picked with
 * Android's own folder picker. Android doesn't let apps pick the whole storage or the Download
 * folder itself, so Rex picks folders inside it.
 */
class FoldersActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val search = (application as XarvisApp).core.phoneSearch
        setContent {
            XarvisTheme {
                var folders by remember { mutableStateOf(search.folders()) }
                val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        try {
                            search.add(uri)
                        } catch (e: Exception) {
                            Toast.makeText(this, "Android didn't allow that folder: pick a folder inside it.", Toast.LENGTH_LONG).show()
                        }
                        folders = search.folders()
                    }
                }
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(16.dp)) {
                    TextButton(onClick = ::finish) { Text("‹ BACK", color = XarvisCyan) }
                    Text("SEARCH FOLDERS", style = MaterialTheme.typography.titleLarge, color = XarvisCyan)
                    Text(
                        "XARVIS searches only these folders, on this phone: file names and the text inside PDF, Word, Excel and text files. " +
                            "Then just ask, e.g. \"search my phone for passport\". Android doesn't allow picking the whole phone or Download itself; pick folders inside.",
                        style = MaterialTheme.typography.bodySmall, color = XarvisMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { pick.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("+  ADD FOLDER") }
                    Spacer(Modifier.height(12.dp))
                    if (folders.isEmpty()) Text("No folders yet.", color = XarvisMuted, modifier = Modifier.padding(top = 12.dp))
                    LazyColumn {
                        items(folders, key = { it.uri }) { f ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("📁  ${f.name}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                TextButton(onClick = { search.remove(f.uri); folders = search.folders() }) { Text("REMOVE") }
                            }
                            HorizontalDivider(color = XarvisMuted.copy(alpha = 0.2f))
                        }
                    }
                }
            }
        }
    }
}
