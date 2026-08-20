package dev.jawsh.xaerodeck.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MeteorActivity : ComponentActivity() {
    private lateinit var api: DeckApi
    private var activeColor = Color(0xFF7EE787)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = DeckApi(filesDir)
        api.baseUrl = intent.getStringExtra("baseUrl") ?: ""
        val prefs = getSharedPreferences("deck", MODE_PRIVATE)
        api.token = prefs.getString("token", "") ?: ""
        activeColor = Color(prefs.getInt("activeColor", 0xFF55FF88.toInt()))
        setContent { MeteorScreen() }
    }

    @Composable
    private fun MeteorScreen() {
        val mono = FontFamily.Monospace
        var modules by remember { mutableStateOf<List<DeckApi.MeteorModule>>(emptyList()) }
        var loaded by remember { mutableStateOf(false) }
        var category by remember { mutableStateOf("") }
        var filter by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            modules = api.meteorModules()
            loaded = true
        }

        Column(Modifier.fillMaxSize().background(Hud.bg).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("‹ MAP", fontFamily = mono, fontSize = 17.sp, color = Hud.accent,
                    modifier = Modifier.background(Hud.surface).border(1.dp, Hud.border)
                        .clickable { finish() }.padding(horizontal = 16.dp, vertical = 12.dp))
                Text("▚ METEOR CONTROL", fontFamily = mono, fontSize = 14.sp,
                    color = Hud.text, fontWeight = FontWeight.Bold)
                TextField(value = filter, onValueChange = { filter = it },
                    placeholder = { Text("SEARCH…", fontFamily = mono, fontSize = 11.sp, color = Hud.sub) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = mono, fontSize = 13.sp, color = Hud.text),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Hud.surface, unfocusedContainerColor = Hud.surface,
                        focusedIndicatorColor = Hud.accent, unfocusedIndicatorColor = Hud.border,
                        cursorColor = Hud.accent),
                    modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))

            MeteorCategoryTabs(mono, modules, category) { category = it }
            Spacer(Modifier.height(8.dp))

            if (!loaded) {
                Text("▚ QUERYING…", fontFamily = mono, fontSize = 12.sp, color = Hud.sub)
            } else if (modules.isEmpty()) {
                Text("▚ METEOR NOT REACHABLE\nREMOTE-CONTROL MODULE ON? TOKEN SET?",
                    fontFamily = mono, fontSize = 13.sp, color = Hud.orange)
            }

            val visible = modules
                .filter { category.isEmpty() || it.category == category }
                .filter { filter.isEmpty() || it.title.contains(filter, true) || it.name.contains(filter, true) }
                .sortedBy { it.title.lowercase() }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visible, key = { it.name }) { m -> MeteorModuleCard(mono, m, activeColor, api) }
            }
        }
    }
}
