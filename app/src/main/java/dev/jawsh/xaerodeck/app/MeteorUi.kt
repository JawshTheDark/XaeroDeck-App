package dev.jawsh.xaerodeck.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Meteor module UI shared between MeteorActivity and the map overlay. */

@Composable
fun MeteorCategoryTabs(mono: FontFamily, modules: List<DeckApi.MeteorModule>,
                       category: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val cats = listOf("") + modules.map { it.category }.distinct().sorted()
        for (c in cats) {
            val sel = c == category
            Text(c.ifEmpty { "ALL" }.uppercase(), fontFamily = mono, fontSize = 15.sp,
                color = if (sel) Hud.onAccent else Hud.sub,
                modifier = Modifier
                    .background(if (sel) Hud.accent else Hud.surface)
                    .border(1.dp, if (sel) Hud.accent else Hud.border)
                    .clickable { onSelect(c) }
                    .padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

@Composable
fun MeteorModuleCard(mono: FontFamily, m: DeckApi.MeteorModule, activeColor: Color, api: DeckApi) {
    val scope = rememberCoroutineScope()
    var active by remember(m.name, m.active) { mutableStateOf(m.active) }
    var expanded by remember(m.name) { mutableStateOf(false) }
    var settings by remember(m.name) { mutableStateOf<List<DeckApi.MeteorSetting>?>(null) }

    Row(Modifier.fillMaxWidth().background(Hud.surface)
        .border(1.dp, if (active) activeColor else Hud.border)) {
        Box(Modifier.width(4.dp).fillMaxHeight()
            .background(if (active) activeColor else Hud.surface))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable {
                    expanded = !expanded
                    if (expanded && settings == null) scope.launch {
                        settings = api.meteorSettings(m.name)
                    }
                }) {
                    Text(m.title.uppercase(), fontFamily = mono, fontSize = 14.sp,
                        color = if (active) activeColor else Hud.text,
                        fontWeight = FontWeight.Bold)
                    Text(m.description, fontFamily = mono, fontSize = 10.sp,
                        color = Hud.sub, maxLines = 1)
                }
                Text(if (expanded) "▴" else "▾", fontFamily = mono, fontSize = 20.sp,
                    color = Hud.sub, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                        .clickable {
                            expanded = !expanded
                            if (expanded && settings == null) scope.launch {
                                settings = api.meteorSettings(m.name)
                            }
                        })
                Switch(checked = active, onCheckedChange = { v ->
                    scope.launch {
                        if (api.meteorToggle(m.name, v)) active = v
                    }
                }, colors = SwitchDefaults.colors(
                    checkedThumbColor = activeColor, checkedTrackColor = Hud.surfaceHi,
                    uncheckedThumbColor = Hud.sub, uncheckedTrackColor = Hud.bg))
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                when (val s = settings) {
                    null -> Text("QUERYING…", fontFamily = mono, fontSize = 11.sp, color = Hud.sub)
                    else -> {
                        if (s.isEmpty()) Text("NO SETTINGS", fontFamily = mono, fontSize = 11.sp, color = Hud.sub)
                        var lastGroup = ""
                        for (setting in s) {
                            if (setting.group != lastGroup) {
                                lastGroup = setting.group
                                Spacer(Modifier.height(6.dp))
                                Text("── ${setting.group.uppercase()}", fontFamily = mono,
                                    fontSize = 10.sp, color = Hud.accent)
                            }
                            MeteorSettingRow(mono, m, setting, api)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MeteorSettingRow(mono: FontFamily, m: DeckApi.MeteorModule, s: DeckApi.MeteorSetting, api: DeckApi) {
    val scope = rememberCoroutineScope()
    fun apply(value: String) {
        scope.launch { api.meteorSetSetting(m.name, s.name, value) }
    }
    when {
        s.type == "label" -> {
            Text("${s.title.uppercase()}: ${s.value}", fontFamily = mono, fontSize = 11.sp,
                color = Hud.sub, modifier = Modifier.padding(vertical = 3.dp))
        }
        s.type == "bool" -> {
            var checked by remember(m.name + s.name) { mutableStateOf(s.value == "true") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(s.title.uppercase(), fontFamily = mono, fontSize = 12.sp, color = Hud.text)
                Switch(checked = checked, onCheckedChange = { checked = it; apply(it.toString()) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Hud.accent, checkedTrackColor = Hud.surfaceHi,
                        uncheckedThumbColor = Hud.sub, uncheckedTrackColor = Hud.bg))
            }
        }
        s.sliderMax > s.sliderMin -> {
            var value by remember(m.name + s.name) {
                mutableStateOf((s.value.toDoubleOrNull() ?: s.sliderMin).toFloat())
            }
            fun fmt(v: Double) = if (s.decimals == 0) v.roundToInt().toString()
            else "%.${s.decimals}f".format(v)
            Column(Modifier.padding(vertical = 2.dp)) {
                Text("${s.title.uppercase()}: ${fmt(value.toDouble())}", fontFamily = mono,
                    fontSize = 12.sp, color = Hud.text)
                Slider(value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { apply(fmt(value.toDouble())) },
                    valueRange = s.sliderMin.toFloat()..s.sliderMax.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Hud.accent, activeTrackColor = Hud.accent,
                        inactiveTrackColor = Hud.border))
            }
        }
        s.options.isNotEmpty() -> {
            var open by remember { mutableStateOf(false) }
            var value by remember(m.name + s.name) { mutableStateOf(s.value) }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(s.title.uppercase(), fontFamily = mono, fontSize = 12.sp, color = Hud.text)
                Box {
                    Text("$value ▾", fontFamily = mono, fontSize = 12.sp, color = Hud.accent,
                        modifier = Modifier.background(Hud.bg).border(1.dp, Hud.border)
                            .clickable { open = true }
                            .padding(horizontal = 10.dp, vertical = 5.dp))
                    DropdownMenu(expanded = open, onDismissRequest = { open = false },
                        modifier = Modifier.background(Hud.panel)) {
                        for (opt in s.options) {
                            DropdownMenuItem(text = {
                                Text(opt, fontFamily = mono, fontSize = 12.sp, color = Hud.text)
                            }, onClick = { value = opt; apply(opt); open = false })
                        }
                    }
                }
            }
        }
        else -> {
            var value by remember(m.name + s.name) { mutableStateOf(s.value) }
            Column(Modifier.padding(vertical = 2.dp)) {
                Text("${s.title.uppercase()} (${s.type})", fontFamily = mono, fontSize = 11.sp, color = Hud.sub)
                TextField(value = value, onValueChange = { value = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = mono, fontSize = 12.sp, color = Hud.text),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { apply(value) }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Hud.bg, unfocusedContainerColor = Hud.bg,
                        focusedIndicatorColor = Hud.accent, unfocusedIndicatorColor = Hud.border,
                        cursorColor = Hud.accent),
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
