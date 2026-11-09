package `in`.opentrack.india.ui

import `in`.opentrack.india.R
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import `in`.opentrack.india.core.Diary
import androidx.lifecycle.lifecycleScope
import `in`.opentrack.india.App
import `in`.opentrack.india.data.Prefs
import `in`.opentrack.india.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch
import `in`.opentrack.india.BuildConfig
import `in`.opentrack.india.data.RefDb
import `in`.opentrack.india.ui.TrackingSources
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.view.View
import androidx.appcompat.app.AppCompatDelegate

/**
 * Screen 3 of 3 — the controls that keep this app alive when something upstream changes.
 *
 * This is not a preferences screen for its own sake. Every field here is a response to a documented
 * failure mode: an endpoint that starts requiring a key (`overrideUrl`), a feed whose column order
 * shifts ([in.opentrack.india.remote.ErailWire] hint), and a data snapshot that ages out (refresh).
 * A user with no build tools can fix all three from this screen, which is the difference between a
 * side project that dies and one that survives.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private var stateFeedbackByAction = false

    /**
     * "Save to file" hands the log to the system's document picker, which answers with a content URI. The
     * pending target is remembered here rather than written to a bundle: the export is a one-shot gesture that
     * loses its meaning if the screen is recreated mid-picker, and a stale path would be worse than a lost tap.
     */


    private val exportDocument = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // `ACTION_CREATE_DOCUMENT` answers with the location the user chose, in `result.data`. Reading a
        // field that nothing ever assigned is how this became a button that always said "the picker was
        // closed", however carefully a file was picked - and a whole report then had to travel through a
        // share sheet instead. The null check stays in the branch: the compiler stops tracking `uri` when the
        // decision is hoisted into a boolean.
        val uri = result.data?.data
        val diary0 = Diary.get()
        runCatching { diary0?.awaitIdle() }
        val bytes = diary0?.text()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        // The null check has to be the branch condition itself: assigning it to a `cancelled` boolean reads
        // better and compiles not at all, because the compiler stops tracking `uri` through the indirection.
        when {
            bytes.isEmpty() -> toast(R.string.diag_nothing)
            uri == null || result.resultCode != RESULT_OK -> toast("Not saved - the picker was closed")
            else -> {
                val outcome = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                if (outcome.isSuccess) {
                    // Which file, where - a log exported to an unknown path is a log the user cannot attach.
                    val where = runCatching { uri.lastPathSegment ?: uri.toString() }.getOrDefault("file")
                    toast(getString(R.string.diag_saved) + " - " + where.take(64))
                } else toast("Could not write that file: " + (outcome.exceptionOrNull()?.javaClass?.simpleName ?: ""))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        val sources = TrackingSources.options
        binding.defaultTrackingSource.adapter = object : ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_dropdown_item, sources.map(TrackingSources::label)) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).also { BatteryMode.paint(it) }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also { BatteryMode.paint(it) }
        }
        binding.defaultTrackingSource.setSelection(sources.indexOf(
            TrackingSources.valid(prefs.defaultTrackingSource)), false)
        binding.defaultTrackingSource.contentDescription = "Default tracking source · " +
            TrackingSources.label(TrackingSources.valid(prefs.defaultTrackingSource))
        binding.defaultTrackingSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                sources.getOrNull(position)?.let {
                    prefs.defaultTrackingSource = it
                    binding.defaultTrackingSource.contentDescription =
                        "Default tracking source · ${TrackingSources.label(it)}"
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.batteryMode.isChecked = prefs.batteryMode
        binding.batteryMode.setOnCheckedChangeListener { _, enabled ->
            if (prefs.batteryMode != enabled) {
                prefs.batteryMode = enabled
                // Dark native widgets and popups, but retain the stored Appearance for exit.
                val mode = prefs.nightMode()
                if (AppCompatDelegate.getDefaultNightMode() != mode)
                    AppCompatDelegate.setDefaultNightMode(mode)
                else recreate()
            }
        }
        if (prefs.batteryMode) BatteryMode.paint(binding.root, window)

        val modes = listOf("Follow system", "Light", "Dark")
        binding.appearance.adapter = object : ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_dropdown_item, modes) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).also { BatteryMode.paint(it) }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also { BatteryMode.paint(it) }
        }
        binding.appearance.setSelection(prefs.appearance, false)
        binding.appearance.contentDescription = "Appearance · ${modes[prefs.appearance]}"
        binding.appearance.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != prefs.appearance && position in 0..2) {
                    prefs.appearance = position
                    binding.appearance.contentDescription = "Appearance · ${modes[position]}"
                    AppCompatDelegate.setDefaultNightMode(prefs.nightMode())
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.overrideUrl.setText(prefs.overrideUrl)
        binding.columnHint.setText(prefs.columnHint)
        binding.save.setOnClickListener {
            val url = binding.overrideUrl.text.toString().trim()
            val parsed = runCatching { android.net.Uri.parse(url) }.getOrNull()
            if (url.isNotBlank() &&
                (parsed?.scheme?.lowercase() !in listOf("http", "https") || parsed?.host.isNullOrBlank())
            ) {
                val why = "Enter a full http(s) address, or leave this blank. Nothing was saved."
                binding.overrideUrl.error = why
                stateFeedbackByAction = true
                binding.state.text = why
                // The validation lives in ON THIS DEVICE, above a long form. A
                // large-font device can leave it offscreen under the keyboard after
                // Save; bring the explanation into view rather than making the user
                // hunt for a reason nothing happened.
                (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                    ?.hideSoftInputFromWindow(binding.overrideUrl.windowToken, 0)
                binding.overrideUrl.clearFocus()
                binding.settingsScroll.post { binding.settingsScroll.smoothScrollTo(0, 0) }
                return@setOnClickListener
            }
            prefs.overrideUrl = url
            prefs.columnHint = binding.columnHint.text.toString().trim()
            Toast.makeText(applicationContext, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
        binding.clear.setOnClickListener {
            lifecycleScope.launch {
                (application as App).repository()?.clearCaches()
                stateFeedbackByAction = true
                binding.state.text = "Live cache cleared · saved timetable still available offline"
                Toast.makeText(applicationContext, "Live cache cleared", Toast.LENGTH_SHORT).show()
            }
        }
        binding.back.setOnClickListener { finish() }
        wireDiagnostics()
        // Both labels need the repository, which is a suspend lookup (it may still be extracting on a
        // first run), so they are set together from one coroutine rather than reached for inline.
        lifecycleScope.launch {
            val version = runCatching {
                (application as App).repository()?.dataVersion()
            }.getOrNull() ?: "not installed"
            binding.about.text = buildString {
                append("TrackTrain ").append(BuildConfig.VERSION_NAME)
                append(" · timetable: ").append(version).append("\n\n")
                append("No accounts, no API keys, no ads, no analytics. Live status is fetched ")
                append("directly from a public Indian Railways enquiry endpoint; everything else — ")
                append("station search, the ±24 h train list and every timetable — is answered here.")
            }
            // Installation completes asynchronously. It must not overwrite a newer,
            // actionable validation/cache message after the traveller pressed Save.
            if (!stateFeedbackByAction) binding.state.text = when {
                RefDb.isUsable(this@SettingsActivity) ->
                    "Built-in timetable installed · offline search ready"
                else -> "Built-in timetable not installed yet — it is extracted on first search"
            }
        }
    }

    /**
     * The collector's controls. Everything on this card is deliberately inert until the switch is on: no
     * buffer is filled, no file is created, and the state line tells the user how many bytes exist so that
     * "I turned it on and nothing happened" is answerable from the screen rather than from a bug report.
     */
    private fun wireDiagnostics() {
        val diary = Diary.get() ?: Diary.install(this)
        renderDiary(diary)
        binding.diagToggle.setOnClickListener {
            if (diary.enabled) {
                diary.disable()
                prefs.diaryEnabled = false
                toast("Collection stopped — nothing more is written")
            } else {
                diary.enable(this)
                prefs.diaryEnabled = true
                toast("Collection on: reproduce the problem, then Share or Save to file here")
            }
            renderDiary(diary)
        }
        binding.diagShare.setOnClickListener {
            runCatching { diary.awaitIdle() }
            val body = diary.text()
            val collected = diary.file()?.let { it.length() > 0L } == true
            if (body.isBlank() || !collected) {
                toast(R.string.diag_empty_hint)
            } else {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "TrackTrain diagnostic log")
                    putExtra(Intent.EXTRA_TEXT, body)
                }
                runCatching { startActivity(Intent.createChooser(send, "Share diagnostic log")) }
                    .onFailure { toast("No app can accept plain text on this device") }
            }
        }
        binding.diagSave.setOnClickListener {
            val saved = diary.file()?.let { it.length() > 0L } == true
            if (!saved) {
                toast(R.string.diag_empty_hint)
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, diary.exportName())
            }
            runCatching { exportDocument.launch(intent) }.onFailure { toast("This device has no file picker") }
        }
        binding.diagClear.setOnClickListener {
            diary.clear()
            renderDiary(diary)
            toast("Log deleted from this device")
        }
    }

    private fun renderDiary(diary: Diary) {
        binding.diagToggle.setText(
            if (diary.enabled) R.string.settings_diag_off else R.string.settings_diag_on,
        )
        val file = diary.file()
        binding.diagState.text = if (file == null || file.length() == 0L) {
            getString(R.string.diag_empty_hint)
        } else {
            val when0 = runCatching {
                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.lastModified()))
            }.getOrNull() ?: "?"
            (if (diary.enabled) "Collecting" else "Stopped") + " · " + file.length() + " bytes · last entry " +
                when0 + " · file: " + file.name
        }
    }

    private fun toast(message: String) = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    private fun toast(res: Int) = Toast.makeText(applicationContext, res, Toast.LENGTH_LONG).show()

}
