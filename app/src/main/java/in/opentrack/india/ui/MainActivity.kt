package `in`.opentrack.india.ui

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.AdapterView
import android.widget.ArrayAdapter
import `in`.opentrack.india.data.Prefs
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import `in`.opentrack.india.App
import `in`.opentrack.india.R
import `in`.opentrack.india.core.Diary
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.databinding.ActivityMainBinding
import `in`.opentrack.india.domain.Suggestion
import `in`.opentrack.india.domain.TrainFinder
import `in`.opentrack.india.model.TrainHit

/**
 * Screen 1 of 3 — find a train: a station pair (or a train number/name), a date, and every run inside
 * the ±24 h window, from the on-device timetable.
 *
 * There is deliberately no navigation drawer, home carousel or tab bar: the app has one job, so the
 * launch screen *is* the search. Every extra surface is pixels to inflate and code to keep working
 * across Android versions, in an app whose main selling point is that it keeps working.
 *
 * The two input boxes are [SuggestBox]es, so a search can be carried by tapping a real station: typing
 * "Chenna" and pressing Find resolves to whatever the fuzzy matcher preferred, while a tap carries the
 * code the user actually looked at.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vm: MainViewModel
    private lateinit var adapter: TrainHitAdapter
    private var keepSearchOpen = false
    /** Editing never publishes results; only the visible Find button commits a draft. */
    private var draftSearch = false
    private var searchCollapsed = false
    private var sortMode = TrainResultsDisplay.Sort.SUGGESTED
    private var keyboardOpen = false

    /** With no hits, a weighted empty RecyclerView reserved a black strip above the IME.
     * Let the form card extend to the keyboard; never hide populated search results. */
    private fun updateKeyboardLayout(open: Boolean) {
        if (keyboardOpen == open) return
        keyboardOpen = open
        val editingEmpty = open && (draftSearch || vm.results.value?.hits?.isNotEmpty() != true)
        // Closing the IME after Edit must not resurrect old rows before Find.
        // An API 34 device exposed this race even though enterDraft hid the list.
        binding.results.visibility = if (SearchDraftVisibility.showResults(
                draftSearch, open, vm.results.value?.hits?.isNotEmpty() == true))
            View.VISIBLE else View.GONE
        binding.searchCardScroll.isFillViewport = editingEmpty
    }

    private fun enterDraft() {
        val hadResult = vm.results.value != null
        draftSearch = true
        keepSearchOpen = true
        setSearchCollapsed(false) // recalculate weighted space after hiding old results
        binding.resultsHead.visibility = View.GONE
        binding.resultsSummary.visibility = View.GONE
        binding.results.visibility = View.GONE
        if (hadResult) {
            binding.statusLine.visibility = View.VISIBLE
            binding.statusLine.text = "Edit your search, then tap Find."
        }
    }

    private fun firstSortedTrain() {
        (binding.results.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
    }

    private var batteryAtCreate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        batteryAtCreate = `in`.opentrack.india.data.Prefs(this).batteryMode
        if (batteryAtCreate) BatteryMode.paint(binding.root, window)

        val app = application as App
        vm = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(app) as T
            }
        )[MainViewModel::class.java]
        draftSearch = savedInstanceState?.getBoolean("draftSearch") ?: false

        adapter = TrainHitAdapter { hit ->
            vm.remember(hit)
            open(hit)
        }.apply { batteryMode = batteryAtCreate }
        binding.results.layoutManager = LinearLayoutManager(this)
        binding.results.adapter = adapter
        binding.results.itemAnimator = null // a live-list upgrade should not jump the edited list
        binding.results.setHasFixedSize(true)
        // adjustResize changes the visible frame when the IME opens. Without
        // this, the empty weighted results viewport sits between Find and the keyboard.
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = Rect()
            binding.root.getWindowVisibleDisplayFrame(visible)
            val screen = android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }
            val imeHeight = screen.y - visible.bottom
            updateKeyboardLayout(imeHeight > 150f * resources.displayMetrics.density)
        }
        val sorts = TrainResultsDisplay.Sort.entries
        binding.resultsSort.adapter = object : ArrayAdapter<String>(this, R.layout.item_sort_selected,
            listOf("Suggested", "Departure", "Arrival", "Duration")) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).also { BatteryMode.paint(it) }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getDropDownView(position, convertView, parent).also { BatteryMode.paint(it) }
        }
        binding.resultsSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val old = sortMode
                sortMode = sorts.getOrElse(position) { TrainResultsDisplay.Sort.SUGGESTED }
                binding.resultsSort.contentDescription = "Sort by ${sortMode.name.lowercase()}"
                val changed = old != sortMode && vm.results.value?.hits?.isNotEmpty() == true
                if (changed) firstSortedTrain()
                vm.results.value?.let { displaySortedResults(it, scrollToTop = changed) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        wireBoxes()
        binding.find.setOnClickListener { runSearch() }
        binding.homeBrand.setOnClickListener {
            // Re-selecting Stations used to clear the ViewModel report while leaving
            // old adapter rows visible. The search then took the empty-state 2/3
            // weight, pushing those orphaned rows to the bottom of the screen.
            // Returning Home in the SAME mode must not discard a valid result.
            if (vm.mode != MainViewModel.Mode.STATIONS) selectMode(MainViewModel.Mode.STATIONS)
            val hasResults = vm.results.value?.hits?.isNotEmpty() == true
            keepSearchOpen = hasResults
            setSearchCollapsed(false)
            if (hasResults) {
                (binding.results.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0)
                binding.results.post { binding.results.scrollToPosition(0) }
            }
            binding.searchCardScroll.post { binding.searchCardScroll.scrollTo(0, 0) }
        }
        binding.editSearch.setOnClickListener { enterDraft() }
        binding.swap.setOnClickListener { swap() }
        binding.dayBack.setOnClickListener { shiftDay(-1) }
        binding.dayFwd.setOnClickListener { shiftDay(1) }
        binding.dayNow.setOnClickListener {
            if (vm.results.value != null) enterDraft()
            vm.setDate(RailTime.todayEpochDay(), RailTime.istMinuteOfDay(System.currentTimeMillis()))
            renderDate()
        }
        binding.settings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.modeStations.setOnClickListener { selectMode(MainViewModel.Mode.STATIONS) }
        binding.modeTrain.setOnClickListener { selectMode(MainViewModel.Mode.TRAIN) }

        vm.phase.observe(this) { renderPhase(it) }
        vm.results.observe(this) { it?.let { r -> renderReport(r) } }
        vm.liveNote.observe(this) {
            // LiveData immediately dispatches its initial null. Before any search that is NOT a
            // blank live result: rendering composeStatus() here erased the visible "Unpacking…"
            // first-frame message while the timetable was still being extracted. Only a search
            // result has a status sentence for this observer to enrich.
            vm.results.value?.let { r ->
                binding.resultsTitle.contentDescription = composeStatus()
                updateResultsSummary(r)
                Diary.get()?.log("UISTATE") {
                    UiState.list(
                        r.from?.code ?: "?", r.to?.code ?: "?", r.hits.size, vm.liveNote.value, r.hits,
                    )
                }
            }
        }
        if (savedInstanceState == null) selectMode(MainViewModel.Mode.STATIONS)
        else renderMode(vm.mode)
        renderDate()
        restoreLast()
        vm.prepare()
        if (savedInstanceState?.getBoolean("searchCollapsed") == true) setSearchCollapsed(true)
        if (draftSearch) enterDraft()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("searchCollapsed", searchCollapsed)
        outState.putBoolean("draftSearch", draftSearch)
        super.onSaveInstanceState(outState)
    }

    private fun setSearchCollapsed(collapsed: Boolean) {
        searchCollapsed = collapsed
        binding.searchCardScroll.visibility = if (collapsed) View.GONE else View.VISIBLE
        binding.searchCompact.visibility = if (collapsed) View.VISIBLE else View.GONE
        // A weighted ScrollView left hundreds of empty pixels when Edit search reopened
        // above an existing list. Wrap its content instead, while bounding tall forms so
        // Find stays scrollable and the results retain a visible viewport.
        val editingResults = !collapsed && !draftSearch && vm.results.value?.hits?.isNotEmpty() == true
        (binding.searchCardScroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = if (editingResults) ViewGroup.LayoutParams.WRAP_CONTENT else 0
            weight = if (editingResults) 0f else 2f
            binding.searchCardScroll.layoutParams = this
        }
        binding.searchCardScroll.capWhenEditingResults = editingResults
    }

    /** Suggestions resolve a station or train number, but NEVER submit the form.
     * Even choosing the second station or the keyboard's Search action waits for Find. */
    private fun wireBoxes() {
        val stationSuggester = object : SuggestBox.Suggester {
            override fun suggest(
                query: String, then: (List<Suggestion>) -> Unit, miss: (String) -> Unit,
            ) {
                vm.suggestStations(query, { then(RowText.rows(it)) }, miss)
            }
        }
        val trainSuggester = object : SuggestBox.Suggester {
            override fun suggest(
                query: String, then: (List<Suggestion>) -> Unit, miss: (String) -> Unit,
            ) {
                vm.suggestTrains(query, { then(RowText.trainRows(it)) }, miss)
            }
        }
        listOf(binding.fromBox, binding.toBox).forEach { box ->
            box.suggester = stationSuggester
            box.onEdit = { enterDraft() }
            box.onPick = null
            box.onSearch = null
        }
        binding.trainBox.suggester = trainSuggester
        binding.trainBox.onEdit = { enterDraft() }
        binding.trainBox.onPick = null
        binding.trainBox.onSearch = null
        binding.fromBox.hint = getString(R.string.hint_from)
        binding.toBox.hint = getString(R.string.hint_to)
        binding.trainBox.hint = getString(R.string.hint_train)
    }

    /** Swap keeps the *resolved* codes with the names; a swap that lost the resolution would re-fuzzy. */
    private fun swap() {
        // Picking the second endpoint starts an asynchronous search. If its results land
        // just after the user taps Swap, renderReport must not collapse the fields before
        // the swapped values can be seen. Find below explicitly ends this edit mode.
        enterDraft()
        val (a, b) = binding.fromBox.text to binding.toBox.text
        val (ac, bc) = binding.fromBox.resolvedCode to binding.toBox.resolvedCode
        binding.fromBox.setTextShown(b); binding.fromBox.resolvedCode = bc
        binding.toBox.setTextShown(a); binding.toBox.resolvedCode = ac
        // Swap sits below both fields. On a short/large-font screen the new values
        // would otherwise be above the viewport, so bring them back into view.
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.searchCardScroll.post { binding.searchCardScroll.scrollTo(0, 0) }
    }

    /** Reopens the last search: the common case is "same pair, again tomorrow". */
    private fun restoreLast() {
        val entry = (application as App).prefsRecent().firstOrNull() ?: return
        val parts = entry.split('|')
        if (parts.size < 3) return
        if (binding.fromBox.text.isEmpty()) binding.fromBox.setTextShown(parts[1].uppercase())
        if (binding.toBox.text.isEmpty()) binding.toBox.setTextShown(parts[2].uppercase())
    }

    private fun selectMode(mode: MainViewModel.Mode) {
        if (vm.mode != mode) {
            vm.setMode(mode)
            adapter.submitList(emptyList()) // never leave old-mode rows under a new question
        }
        renderMode(mode)
    }

    private fun renderMode(mode: MainViewModel.Mode) {
        val stations = mode == MainViewModel.Mode.STATIONS
        binding.stationInputs.visibility = if (stations) View.VISIBLE else View.GONE
        binding.trainBox.visibility = if (stations) View.GONE else View.VISIBLE
        binding.trainLabel.visibility = if (stations) View.GONE else View.VISIBLE
        val hasResults = vm.results.value?.hits?.isNotEmpty() == true
        binding.resultsHead.visibility = if (hasResults && !draftSearch) View.VISIBLE else View.GONE
        binding.resultsSortRow.visibility = binding.resultsHead.visibility
        binding.resultsSummary.visibility = binding.resultsHead.visibility
        binding.modeStations.setBackgroundResource(if (stations) R.drawable.bg_segment_on else 0)
        binding.modeTrain.setBackgroundResource(if (stations) 0 else R.drawable.bg_segment_on)
        binding.modeStations.setTextColor(getColor(if (stations) R.color.text1 else R.color.text3))
        binding.modeTrain.setTextColor(getColor(if (stations) R.color.text3 else R.color.text1))
        if (batteryAtCreate) BatteryMode.paint(binding.root)
    }

    private fun shiftDay(delta: Int) {
        if (vm.results.value != null) enterDraft()
        val day = (vm.targetEpochDay + delta).coerceIn(
            RailTime.todayEpochDay() - 1, RailTime.todayEpochDay() + 1)
        vm.setDate(day, vm.targetMinuteOfDay)
        renderDate()
    }

    private fun renderDate() {
        val label = when (vm.targetEpochDay - RailTime.todayEpochDay()) {
            0L -> "Today"
            1L -> "Tomorrow"
            -1L -> "Yesterday"
            else -> RailTime.formatDate(vm.targetEpochDay)
        }
        binding.whenValue.text = "$label · ${RailTime.formatRailMinute(vm.targetMinuteOfDay)}"
        binding.whenValue.contentDescription = "${binding.whenValue.text}; timetable window " +
            "minus 24 to plus 24 hours; past journey actual times are not verified"
        binding.dayBack.isEnabled = vm.targetEpochDay > RailTime.todayEpochDay() - 1
        binding.dayFwd.isEnabled = vm.targetEpochDay < RailTime.todayEpochDay() + 1
        binding.dayNow.setBackgroundResource(
            if (vm.targetEpochDay == RailTime.todayEpochDay()) R.drawable.bg_segment_on else 0
        )
        binding.dayBack.setBackgroundResource(
            if (vm.targetEpochDay < RailTime.todayEpochDay()) R.drawable.bg_segment_on else 0
        )
        binding.dayFwd.setBackgroundResource(
            if (vm.targetEpochDay > RailTime.todayEpochDay()) R.drawable.bg_segment_on else 0
        )
        if (batteryAtCreate) BatteryMode.paint(binding.root)
    }

    private fun runSearch() {
        keepSearchOpen = false
        // Otherwise the keyboard covers the result/validation sentence below the search card.
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        val from = binding.fromBox.valueOrText()
        val to = binding.toBox.valueOrText()
        val train = binding.trainBox.valueOrText()
        if (vm.mode == MainViewModel.Mode.STATIONS && (from.length < 2 || to.length < 2)) {
            binding.statusLine.visibility = View.VISIBLE
            binding.statusLine.setText(R.string.err_need_two_stations)
            return
        }
        if (vm.mode == MainViewModel.Mode.TRAIN && train.length < 3) {
            binding.statusLine.visibility = View.VISIBLE
            binding.statusLine.setText(R.string.err_need_train_query)
            return
        }
        // Clear the previous journey only after Find has valid inputs. A late live
        // upgrade to the old result must not paint it over this pending search.
        draftSearch = false
        binding.resultsHead.visibility = View.GONE
        binding.resultsSummary.visibility = View.GONE
        adapter.submitList(emptyList())
        vm.search(from, if (vm.mode == MainViewModel.Mode.TRAIN) train else to)
    }

    private fun renderPhase(phase: MainViewModel.Phase) {
        when (phase) {
            is MainViewModel.Phase.Preparing -> {
                binding.statusLine.visibility = View.VISIBLE
                val pct = if (phase.total > 0) (phase.done * 100 / phase.total).toInt() else 0
                binding.statusLine.text = if (phase.total == 0L) {
                    "Unpacking the offline timetable…"
                } else {
                    getString(R.string.installing_data, pct)
                }
            }
            is MainViewModel.Phase.Ready -> {
                // Fill and reveal the installed-data chip before hiding the preparation message.
                // There must be no frame where both answers are empty on a fast first launch.
                binding.dataChip.text = if (phase.dataVersion.contains("UNVERIFIED"))
                    "Timetable · check before travel" else phase.dataVersion
                binding.dataChip.contentDescription = phase.dataVersion
                binding.dataChip.visibility = View.VISIBLE
                // A completed search also ends in Ready. Do not hide the very answer that
                // renderReport just wrote (especially "no trains" and unknown-station advice).
                binding.statusLine.visibility = if (draftSearch ||
                    vm.results.value?.hits?.isEmpty() == true) View.VISIBLE else View.GONE
                binding.find.isEnabled = true
            }
            is MainViewModel.Phase.Broken -> {
                binding.statusLine.visibility = View.VISIBLE
                binding.find.isEnabled = false
                binding.statusLine.text = phase.message
            }
            MainViewModel.Phase.Searching -> {
                binding.statusLine.visibility = View.VISIBLE
                binding.statusLine.setText(R.string.searching)
            }
        }
    }

    private fun renderReport(report: TrainFinder.Report) {
        // A previous query may finish while the person edits. Do not publish it
        // over the draft; only Find can display a result for the new fields.
        if (draftSearch) return
        // Find launches the search before SuggestBox hides the IME. If
        // that hide races the returned result, the still-focused editor can summon the
        // keyboard again and leave a real result row outside the accessible viewport.
        // Put focus on the screen, not an editor, when the report is ready.
        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.resultsHead.visibility = if (report.hits.isEmpty()) View.GONE else View.VISIBLE
        // A result may arrive before adjustResize reports the IME closing.
        binding.results.visibility = if (keyboardOpen && report.hits.isEmpty()) View.GONE else View.VISIBLE
        binding.searchCardScroll.isFillViewport = keyboardOpen && report.hits.isEmpty()
        binding.resultsSortRow.visibility = binding.resultsHead.visibility
        binding.resultsSummary.visibility = binding.resultsHead.visibility
        updateResultsSummary(report)
        displaySortedResults(report)
        if (report.hits.isNotEmpty()) {
            val pair = listOfNotNull(report.from?.code, report.to?.code).joinToString(" → ")
                .ifBlank { binding.trainBox.valueOrText() }
            if (!keepSearchOpen) setSearchCollapsed(true)
        } else setSearchCollapsed(false)
        val head = when (report.reason) {
            TrainFinder.Reason.OK -> {
                val pair = listOfNotNull(report.from?.code, report.to?.code).joinToString(" → ")
                getString(R.string.trains_between, report.hits.size,
                    report.from?.name ?: pair, report.to?.name ?: pair) +
                    (report.notRunningOnDate.takeIf { it > 0 }
                        ?.let { " · $it do not run on that date" } ?: "")
            }
            TrainFinder.Reason.STATION_NOT_FOUND -> getString(R.string.err_station_unknown)
            TrainFinder.Reason.NO_DIRECT_TRAINS ->
                getString(R.string.err_no_direct) + (report.notRunningHint?.let { " — $it" } ?: "")
            TrainFinder.Reason.WRONG_DATE ->
                report.notRunningHint ?: getString(R.string.err_wrong_date)
            TrainFinder.Reason.SAME_STATION -> getString(R.string.err_same_station)
        }
        statusHead = head
        binding.resultsTitle.text = if (report.hits.isNotEmpty()) "SEARCHED TRAINS" else head
        binding.resultsTitle.contentDescription = composeStatus()
        binding.statusLine.visibility = if (report.hits.isEmpty()) View.VISIBLE else View.GONE
        binding.statusLine.text = if (report.hits.isEmpty()) head else ""

        // The frontend captures itself (the owner's ask, 2026-10-12): the list states its rows and
        // its state line into the diary, and the window photographs itself - no screenshots.
        Diary.get()?.log("UISTATE") {
            UiState.list(
                report.from?.code ?: "?", report.to?.code ?: "?", report.hits.size,
                vm.liveNote.value ?: head, report.hits,
            )
        }
        UiShot.snap(this, "list")
    }

    private fun updateResultsSummary(report: TrainFinder.Report) {
        binding.resultsSummary.text = SearchResultSummary.line(
            report.hits.size, vm.liveNote.value, report.hits.map { it.sourceTag })
        binding.resultsSummary.contentDescription = binding.resultsSummary.text
    }

    private fun displaySortedResults(report: TrainFinder.Report, scrollToTop: Boolean = false) {
        val sortLabel = when (sortMode) {
            TrainResultsDisplay.Sort.SUGGESTED -> getString(R.string.sorted_by)
            TrainResultsDisplay.Sort.DEPARTURE -> "sorted by departure"
            TrainResultsDisplay.Sort.ARRIVAL -> "sorted by arrival"
            TrainResultsDisplay.Sort.DURATION -> "sorted by duration"
        }
        binding.resultsSub.text = sortLabel +
            (if (report.queryMillis > 0) " · ${report.queryMillis} ms on device" else "")
        adapter.nowMinute = vm.targetEpochMinute
        adapter.submitList(TrainResultsDisplay.sorted(report.hits, sortMode)) {
            // AsyncListDiffer may commit after the selection callback; reset only
            // for a deliberate sort, not for a later live-list refresh.
            if (scrollToTop) binding.results.post { firstSortedTrain() }
        }
    }

    /**
     * The header sentence plus the v2 live-state note. The note arrives AFTER the asset render (the
     * live fetch upgrades in place), so both the results and the note observers re-render through
     * this one composition - the screen never stitches two writers' output by string surgery.
     */
    private var statusHead: String = ""

    private fun composeStatus(): String =
        statusHead + (vm.liveNote.value?.let { " — $it" } ?: "")

    override fun onStart() {
        super.onStart()
        if (batteryAtCreate != `in`.opentrack.india.data.Prefs(this).batteryMode) recreate()
    }

    private fun open(hit: TrainHit) {
        setSearchCollapsed(true)
        startActivity(
            Intent(this, TrainActivity::class.java).apply {
                putExtra(TrainActivity.EXTRA_TRAIN_NO, hit.trainNo)
                putExtra(TrainActivity.EXTRA_FROM, hit.fromCode)
                putExtra(TrainActivity.EXTRA_TO, hit.toCode)
                putExtra(TrainActivity.EXTRA_ORIGIN_EPOCH_DAY, hit.originDateEpochDay)
            }
        )
    }
}
