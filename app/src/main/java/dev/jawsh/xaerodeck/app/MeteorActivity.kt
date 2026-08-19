package dev.jawsh.xaerodeck.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Full-screen Meteor control panel: category rail + searchable module cards,
 * with each module's settings expanding inline inside its card.
 */
class MeteorActivity : AppCompatActivity() {
    private lateinit var api: DeckApi
    private lateinit var moduleList: LinearLayout
    private lateinit var chipRow: LinearLayout
    private var modules: List<DeckApi.MeteorModule> = emptyList()
    private var category = ""
    private var filter = ""

    private val bg = 0xFF101214.toInt()
    private val card = 0xFF1A1F27.toInt()
    private val accent = 0xFF7FB8E8.toInt()
    private val textCol = 0xFFE8EEF4.toInt()
    private val subCol = 0xFF8899AA.toInt()
    private var activeColor = 0xFF55FF88.toInt()

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = DeckApi(filesDir)
        api.baseUrl = intent.getStringExtra("baseUrl") ?: ""
        val prefs = getSharedPreferences("deck", MODE_PRIVATE)
        api.token = prefs.getString("token", "") ?: ""
        activeColor = prefs.getInt("activeColor", 0xFF55FF88.toInt())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(24, 24, 24, 0)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "‹ Map"
            textSize = 18f
            setTextColor(accent)
            setPadding(8, 8, 30, 8)
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "Meteor"
            textSize = 20f
            setTextColor(textCol)
        }
        val search = EditText(this).apply {
            hint = "Search…"
            setHintTextColor(subCol)
            setTextColor(textCol)
            setSingleLine()
        }
        topRow.addView(back)
        topRow.addView(title)
        topRow.addView(search, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = 40 })
        root.addView(topRow)

        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chipScroll.addView(chipRow)
        root.addView(chipScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12 })

        moduleList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 40)
        }
        val scroll = ScrollView(this)
        scroll.addView(moduleList)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                filter = s.toString()
                renderModules()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        lifecycleScope.launch {
            modules = api.meteorModules()
            if (modules.isEmpty()) {
                val empty = TextView(this@MeteorActivity).apply {
                    text = "Meteor not reachable.\nIs the game running with the latest XaeroDeck?"
                    setTextColor(subCol)
                    textSize = 16f
                    setPadding(8, 60, 8, 0)
                }
                moduleList.addView(empty)
                return@launch
            }
            renderChips()
            renderModules()
        }
    }

    private fun renderChips() {
        chipRow.removeAllViews()
        val cats = listOf("") + modules.map { it.category }.distinct().sorted()
        for (c in cats) {
            val chip = TextView(this)
            chip.text = c.ifEmpty { "All" }
            chip.textSize = 14f
            val selected = c == category
            chip.setTextColor(if (selected) 0xFF102030.toInt() else textCol)
            chip.setBackgroundColor(if (selected) accent else card)
            chip.setPadding(30, 14, 30, 14)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = 12
            chip.layoutParams = lp
            chip.setOnClickListener {
                category = c
                renderChips()
                renderModules()
            }
            chipRow.addView(chip)
        }
    }

    @SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
    private fun renderModules() {
        moduleList.removeAllViews()
        for (m in modules.sortedBy { it.title.lowercase() }) {
            if (category.isNotEmpty() && m.category != category) continue
            if (filter.isNotEmpty() &&
                !m.title.contains(filter, true) && !m.name.contains(filter, true)) continue

            val stripe = View(this)
            val cardInner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(22, 8, 28, 8)
            }
            val cardView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(card)
                addView(stripe, LinearLayout.LayoutParams(8, LinearLayout.LayoutParams.MATCH_PARENT))
                addView(cardInner, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 10
            cardView.layoutParams = lp

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(this).apply {
                text = m.title
                textSize = 17f
            }
            fun applyActiveLook(active: Boolean) {
                title.setTextColor(if (active) activeColor else textCol)
                stripe.setBackgroundColor(if (active) activeColor else card)
            }
            applyActiveLook(m.active)
            val desc = TextView(this).apply {
                text = m.description
                textSize = 12f
                setTextColor(subCol)
                maxLines = 1
            }
            titleBox.addView(title)
            titleBox.addView(desc)
            header.addView(titleBox, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val expandArrow = TextView(this).apply {
                text = "▾"
                textSize = 18f
                setTextColor(subCol)
                setPadding(20, 0, 20, 0)
            }
            header.addView(expandArrow)
            val sw = Switch(this)
            sw.isChecked = m.active
            sw.setOnCheckedChangeListener { _, v ->
                lifecycleScope.launch {
                    if (api.meteorToggle(m.name, v)) applyActiveLook(v)
                    else sw.isChecked = !v
                }
            }
            header.addView(sw)
            cardInner.addView(header)

            val settingsBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(8, 4, 8, 12)
            }
            cardInner.addView(settingsBox)

            var loaded = false
            val toggleExpand = {
                if (settingsBox.visibility == View.VISIBLE) {
                    settingsBox.visibility = View.GONE
                    expandArrow.text = "▾"
                } else {
                    settingsBox.visibility = View.VISIBLE
                    expandArrow.text = "▴"
                    if (!loaded) {
                        loaded = true
                        loadSettings(m, settingsBox)
                    }
                }
            }
            header.setOnClickListener { toggleExpand() }

            moduleList.addView(cardView)
        }
    }

    @SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
    private fun loadSettings(m: DeckApi.MeteorModule, box: LinearLayout) {
        val loading = TextView(this).apply {
            text = "Loading…"
            setTextColor(subCol)
        }
        box.addView(loading)
        lifecycleScope.launch {
            val settings = api.meteorSettings(m.name)
            box.removeAllViews()
            if (settings.isEmpty()) {
                box.addView(TextView(this@MeteorActivity).apply {
                    text = "No settings"
                    setTextColor(subCol)
                })
                return@launch
            }
            var lastGroup = ""
            for (s in settings) {
                if (s.group != lastGroup) {
                    lastGroup = s.group
                    box.addView(TextView(this@MeteorActivity).apply {
                        text = s.group.uppercase()
                        setTextColor(accent)
                        textSize = 11f
                        setPadding(0, 18, 0, 6)
                    })
                }
                box.addView(settingRow(m, s))
            }
        }
    }

    @SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
    private fun settingRow(m: DeckApi.MeteorModule, s: DeckApi.MeteorSetting): View {
        fun apply(value: String, feedback: TextView? = null) {
            lifecycleScope.launch {
                val ok = api.meteorSetSetting(m.name, s.name, value)
                feedback?.setTextColor(if (ok) subCol else 0xFFFF5555.toInt())
            }
        }

        return when {
            s.type == "label" -> {
                TextView(this).apply {
                    text = "${s.title}: ${s.value}"
                    textSize = 15f
                    setTextColor(subCol)
                    setPadding(0, 6, 0, 6)
                }
            }

            s.type == "bool" -> {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val label = TextView(this).apply {
                    text = s.title
                    textSize = 15f
                    setTextColor(textCol)
                }
                row.addView(label, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val sw = Switch(this)
                sw.isChecked = s.value == "true"
                sw.setOnCheckedChangeListener { _, v -> apply(v.toString()) }
                row.addView(sw)
                row.setPadding(0, 6, 0, 6)
                row
            }

            s.sliderMax > s.sliderMin -> {
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val label = TextView(this).apply {
                    textSize = 15f
                    setTextColor(textCol)
                }
                val steps = 200
                val cur = s.value.toDoubleOrNull() ?: s.sliderMin
                fun fmt(v: Double) = if (s.decimals == 0) v.roundToInt().toString()
                else "%.${s.decimals}f".format(v)
                label.text = "${s.title}: ${fmt(cur)}"
                val seek = SeekBar(this)
                seek.max = steps
                seek.progress = (((cur - s.sliderMin) / (s.sliderMax - s.sliderMin)) * steps)
                    .roundToInt().coerceIn(0, steps)
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    var v = cur
                    override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {
                        v = s.sliderMin + (s.sliderMax - s.sliderMin) * p / steps
                        label.text = "${s.title}: ${fmt(v)}"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) = apply(fmt(v))
                })
                col.addView(label)
                col.addView(seek)
                col.setPadding(0, 6, 0, 6)
                col
            }

            s.options.isNotEmpty() -> {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(this).apply {
                    text = s.title
                    textSize = 15f
                    setTextColor(textCol)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val spinner = Spinner(this)
                spinner.adapter = ArrayAdapter(this,
                    android.R.layout.simple_spinner_dropdown_item, s.options)
                val cur = s.options.indexOfFirst { it.equals(s.value, true) }
                if (cur >= 0) spinner.setSelection(cur)
                var first = true
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        if (first) { first = false; return }
                        apply(s.options[pos])
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
                row.addView(spinner)
                row.setPadding(0, 6, 0, 6)
                row
            }

            else -> {
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val label = TextView(this).apply {
                    text = s.title
                    textSize = 15f
                    setTextColor(textCol)
                }
                val edit = EditText(this).apply {
                    setText(s.value)
                    setTextColor(textCol)
                    setSingleLine()
                    imeOptions = EditorInfo.IME_ACTION_DONE
                }
                edit.setOnEditorActionListener { v, _, _ ->
                    apply(v.text.toString(), label)
                    false
                }
                col.addView(label)
                col.addView(edit)
                col.setPadding(0, 6, 0, 6)
                col
            }
        }
    }
}
