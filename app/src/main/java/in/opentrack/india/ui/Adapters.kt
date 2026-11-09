package `in`.opentrack.india.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt
import `in`.opentrack.india.R
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.RouteBetween
import `in`.opentrack.india.domain.Suggestion
import `in`.opentrack.india.domain.TitleCase
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StationHit
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.TrainHit
import `in`.opentrack.india.model.TrainSummary
import `in`.opentrack.india.v2.mirror.OrgWebsiteStatus

/**
 * Search results. Two rows of information per hit and nothing else — the decision a traveller makes
 * here is "does this train work for me", which needs train identity, times, duration and whether it
 * actually runs on the date they picked.
 *
 * The right-hand tag is the one thing a printed timetable cannot tell you: how long until it goes. It
 * is computed from the same rail-minute arithmetic the search used, so it cannot disagree with the times
 * beside it.
 */
class TrainHitAdapter(private val onClick: (TrainHit) -> Unit) :
    ListAdapter<TrainHit, TrainHitAdapter.Holder>(DIFF) {

    /** Minute-of-IST-epoch for "now", carried by the ViewModel. UI never reads the wall clock itself. */
    var nowMinute: Long = 0L
    var batteryMode: Boolean = false

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val dep: TextView = view.findViewById(R.id.hitDep)
        val arr: TextView = view.findViewById(R.id.hitArr)
        val no: TextView = view.findViewById(R.id.hitNo)
        val name: TextView = view.findViewById(R.id.hitName)
        val meta: TextView = view.findViewById(R.id.hitMeta)
        val days: TextView = view.findViewById(R.id.hitDays)
        val tag: TextView = view.findViewById(R.id.hitTag)
        val go: TextView = view.findViewById(R.id.hitGo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_train_hit, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val hit = getItem(position)
        val ctx = holder.itemView.context
        holder.dep.text = RailTime.formatRailMinuteBare(hit.boardMinute)
        holder.arr.text = RailTime.formatRailMinute(hit.alightMinute)
        holder.no.text = buildString {
            append(hit.trainNo)
            hit.trainType?.let { append(" · ").append(RowText.type(it)) }
        }
        holder.name.text = RowText.of(hit.trainName, keep = setOf(hit.fromCode, hit.toCode))
        holder.meta.text = buildString {
            append(hit.fromCode).append(" → ").append(hit.toCode)
            if (hit.distanceKm > 0) append(" · ").append(hit.distanceKm).append(" km")
            append(" · ").append(RailTime.formatDuration(hit.durationMin))
            if (RailTime.dayOffsetOf(hit.alightMinute) > RailTime.dayOffsetOf(hit.boardMinute)) {
                append(" · arrives next day")
            }
            // The provenance suffix rides the meta line: a row must say whether its time is the
            // timetable's or a live source's (v2 slice 1 - "offline never looks live").
            append(" · ").append(hit.sourceTag ?: "timetable")
        }
        holder.days.text = TrainResultsDisplay.frequency(hit.runDays)
        holder.days.contentDescription = if (hit.runDays == null)
            "Listed for the selected date; operating weekdays not verified by a schedule"
            else "Runs " + RowText.dayNames(hit.runDays) + "; Sunday through Saturday, underlined days operate"
        val delta = hit.boardEpochMinute - nowMinute
        holder.tag.text = TrackModel.formatGap(delta.toInt())
        holder.tag.setBackgroundResource(
            when {
                delta in -20L..0L -> R.drawable.bg_badge_problem
                delta > 0 && delta <= 180 -> R.drawable.bg_pill_accent
                else -> R.drawable.bg_pill
            }
        )
        holder.tag.setTextColor(ctx.getColor(
            when {
                delta in -20L..0L -> R.color.late
                delta in 1L..180L -> R.color.accent
                else -> R.color.text2
            }
        ))
        holder.itemView.setOnClickListener { onClick(hit) }
        if (batteryMode) BatteryMode.paint(holder.itemView)
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TrainHit>() {
            override fun areItemsTheSame(a: TrainHit, b: TrainHit) =
                a.trainNo == b.trainNo && a.originDateEpochDay == b.originDateEpochDay &&
                    a.fromCode == b.fromCode && a.toCode == b.toCode

            override fun areContentsTheSame(a: TrainHit, b: TrainHit) = a == b
        }
    }
}

/**
 * The route table. Primary clocks and marker come from [TrackModel.Stop]. A separately
 * labelled website past-card comparison may fill a gap beside a stop, but never
 * replaces an NTES actual or moves the selected-source locomotive.
 *
 * Per row: scheduled arrival/departure in the dim column, live-or-expected in the bright one, and a tag
 * naming which of the two the bright column is. A projected minute and a confirmed minute have to be
 * distinguishable at a glance — that distinction is the whole reason a passenger opens this app instead
 * of a timetable PDF.
 */
class StopAdapter : ListAdapter<TrackModel.Stop, StopAdapter.Holder>(DIFF) {
    /** Set only on the opt-in path. Standard row binding is otherwise unchanged. */
    var batteryMode: Boolean = false

    var savedOfficialClocks: Set<String> = emptySet()
        set(value) {
            if (field != value) {
                val changed = (field + value).map { it.substringBefore(':') }.toSet()
                field = value
                rebindCodes(*changed.toTypedArray())
            }
        }
    var websiteComparisonSaved: Boolean = false
        set(value) {
            if (field != value) { field = value; rebindCodes(*websiteComparisonTimes.keys.toTypedArray()) }
        }

    /** Activity supplies the explanation/retry; this adapter never invents map dots. */
    var onMapUnavailable: ((String) -> Unit)? = null
    var mapSource: String? = null
    var mappedLegStarts: Set<String> = emptySet()
    /** Map kilometres are a separate axis from a timetable's kilometres. */
    var mappedAnchorKm: Map<String, Int> = emptyMap()

    private var expandedCode: String? = null
    private var routePoints: Map<String, List<StopRow>> = emptyMap()
    private var projectedLeg: RouteBetween.LegProjection? = null
    /** Only the selected website's validated past cards, never a background comparison. */
    var websiteIntermediateReports: Map<String, LiveStopFields> = emptyMap()
        set(value) {
            if (field != value) {
                field = value
                // A same-anchor page can add an intermediate event while all
                // scheduled halt rows remain identical. Rebind its mapped leg.
                rebindCodes(*routePoints.keys.toTypedArray())
            }
        }
    /** Selected site's printed clocks, separate from qualified train events. */
    var websiteCardTimes: Map<String, OrgWebsiteStatus.SiteClocks> = emptyMap()
        set(value) {
            if (field != value) { field = value; notifyDataSetChanged() }
        }
    /** Only a recent, same-route website past card for a *missing* NTES cell.
     * Never copied to the official column, marker or stored reported minutes. */
    var websiteComparisonTimes: Map<String, OrgWebsiteStatus.SiteClocks> = emptyMap()
        set(value) {
            if (field != value) {
                val previous = field
                val changed = (previous.keys + value.keys).filter { previous[it] != value[it] }
                field = value
                rebindCodes(*changed.toTypedArray())
            }
        }
    /** Last page's departure suggestion is a labelled note, not an ACTUAL clock. */
    var websitePendingDeparture: Pair<String, Int>? = null
        set(value) {
            if (field != value) { field = value; notifyDataSetChanged() }
        }
    /** Only a later-published, retained suggestion may trigger a route cue. */
    var websiteTentativeDeparture: Pair<String, Int>? = null
        set(value) {
            if (field != value) { field = value; notifyDataSetChanged() }
        }
    var sourceNameForMarker: String = "NTES"
        set(value) {
            if (field != value) { field = value; if (projectedLeg != null) notifyDataSetChanged() }
        }
    var legWebsiteEventMinute: Int? = null
        set(value) {
            if (field != value) { field = value; if (projectedLeg != null) notifyDataSetChanged() }
        }
    /** Age changes only the marker/leg captions. Rebinding the entire route every
     * minute spends layout and GPU work on dozens of unchanged stop cells. */
    private fun rebindCodes(vararg codes: String?) {
        codes.filterNotNull().distinct().forEach { code ->
            val index = currentList.indexOfFirst { it.code.equals(code, ignoreCase = true) }
            if (index >= 0) notifyItemChanged(index)
        }
    }

    var legAgeSeconds: Long? = null
        set(value) {
            if (field?.div(60) != value?.div(60)) {
                field = value
                rebindCodes(projectedLeg?.fromCode, haltMarker?.code)
            } else field = value
        }

    fun departureCueAnchor(): String? = projectedLeg?.takeIf {
        DepartureCue.isCue(it.markerKind) }?.fromCode

    fun setProjectedLeg(leg: RouteBetween.LegProjection?) {
        if (projectedLeg == leg) return
        projectedLeg = leg
        if (DepartureCue.isCue(leg?.markerKind) &&
            leg?.fromCode.equals(haltMarker?.code, ignoreCase = true)) haltMarker = null
        notifyDataSetChanged()
    }

    /** A group is attached to the preceding scheduled halt. No point becomes a timed halt. */
    fun setRoutePoints(groups: Map<String, List<StopRow>>) {
        if (routePoints == groups) return
        routePoints = groups
        if (expandedCode?.let { code -> code !in groups &&
                projectedLeg?.takeIf { it.fromCode == code }?.points.isNullOrEmpty() } == true)
            expandedCode = null
        notifyDataSetChanged()
    }

    /** The halt the mirror's actuals say the train is at or making for - tinted, never silent. */
    var haltMarker: TableMarker.Halt? = null
        set(value) {
            // A refresh/age tick may reinstall the reported halt marker AFTER a
            // departure cue is on the leg. One train cannot occupy both places.
            val unique = value?.takeUnless { marker ->
                DepartureCue.isCue(projectedLeg?.markerKind) &&
                    projectedLeg?.fromCode.equals(marker.code, ignoreCase = true)
            }
            if (field != unique) {
                val old = field?.code
                field = unique
                rebindCodes(old, unique?.code)
            }
        }

    var highlightCode: String? = null
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged() // the timetable rows themselves may be unchanged
            }
        }

    /** Per-stop source annotations, including manual uncorroborated candidates. A source
     * selection can leave the row objects unchanged, so explicitly rebind visible rows. */
    var reports: Map<String, String> = emptyMap()
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val row: View = view.findViewById(R.id.stopRow)
        val inlineLegGroup: LinearLayout = view.findViewById(R.id.inlineLegGroup)
        val inlineLeg: InlineLegView = view.findViewById(R.id.inlineLeg)
        val inlineLegCaption: TextView = view.findViewById(R.id.inlineLegCaption)
        val inlineLegAge: TextView = view.findViewById(R.id.inlineLegAge)
        val routeToggle: TextView = view.findViewById(R.id.routeToggle)
        val details: LinearLayout = view.findViewById(R.id.stopDetails)
        val primaryTime: TextView = view.findViewById(R.id.stopPrimaryTime)
        val timeType: TextView = view.findViewById(R.id.stopTimeType)
        val stopToggle: TextView = view.findViewById(R.id.stopToggle)
        val dot: View = view.findViewById(R.id.stopDot)
        val trainMarker: StopRailMarkerView = view.findViewById(R.id.stopTrainMarker)
        val marker: TextView = view.findViewById(R.id.haltMarker)
        val markerAge: TextView = view.findViewById(R.id.stopMarkerAge)
        val eventCaption: TextView = view.findViewById(R.id.stopEventCaption)
        val name: TextView = view.findViewById(R.id.stopName)
        val meta: TextView = view.findViewById(R.id.stopMeta)
        val status: TextView = view.findViewById(R.id.stopStatus)
        val report: TextView = view.findViewById(R.id.stopReport)
        val websitePastComparison: TextView = view.findViewById(R.id.websitePastComparison)
        val schedArr: TextView = view.findViewById(R.id.schedArr)
        val schedDep: TextView = view.findViewById(R.id.schedDep)
        val schedDay: TextView = view.findViewById(R.id.schedDay)
        val liveArr: TextView = view.findViewById(R.id.liveArr)
        val liveDep: TextView = view.findViewById(R.id.liveDep)
        val liveDay: TextView = view.findViewById(R.id.liveDay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_stop, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val s = getItem(position)
        val ctx = holder.itemView.context
        // A recycled holder must never carry the battery sketch into normal
        // mode or turn a formerly qualified site event into a new train icon.
        holder.trainMarker.batteryOutline = false
        holder.inlineLeg.batteryOutline = false
        holder.name.text = RowText.of(s.name)
        holder.name.setTextColor(ctx.getColor(colorFor(s)))
        holder.meta.text = buildString {
            append(s.code)
            if (s.source == TrackModel.MinuteSource.REPORTED) {
                val saved = listOfNotNull(
                    "arr".takeIf { "${s.code}:arr" in savedOfficialClocks },
                    "dep".takeIf { "${s.code}:dep" in savedOfficialClocks })
                if (saved.isNotEmpty()) append(" · NTES saved ").append(saved.joinToString("/"))
            }
            if (s.showKm) append(" · ").append(s.kmFromOrigin).append(" km")
            if (s.isBoarding) append(" · ").append(ctx.getString(R.string.tag_board))
            else if (s.isAlighting) append(" · ").append(ctx.getString(R.string.tag_alight))
            s.platform?.takeIf { it.isNotBlank() }?.let {
                append(if (s.source == TrackModel.MinuteSource.SITE_REPORTED) " · site pf "
                    else " · feed pf ").append(it)
            }
        }
        // Normal mode keeps its existing leg cue. Battery mode must not paint
        // that cue a quarter-leg beyond an officially reported departure: pin
        // its one white sketch to the LAST timed halt instead.
        val batteryCue = if (batteryMode &&
            projectedLeg?.fromCode.equals(s.code, true) &&
            DepartureCue.isCue(projectedLeg?.markerKind) &&
            (s.source == TrackModel.MinuteSource.REPORTED && s.showDep != null ||
             s.source == TrackModel.MinuteSource.SITE_REPORTED && s.showArr != null))
            TableMarker.Halt(s.code, "Last reported stop", s.source == TrackModel.MinuteSource.SITE_REPORTED)
            else null
        val marker = haltMarker?.takeIf { it.code.equals(s.code, true) } ?: batteryCue
        // The train icon and age already identify the last report. Keep its
        // evidence available to accessibility without a redundant banner.
        holder.marker.visibility = View.GONE
        holder.marker.text = ""
        holder.marker.contentDescription = marker?.caption ?: ""
        val ageLabel = marker?.takeUnless { it.caption.startsWith("RailYatri station claim") }
            ?.let { legAgeSeconds?.let(LegGeometry::ageLabel) }
        holder.markerAge.text = legAgeSeconds?.takeIf { ageLabel != null }
            ?.let(LegGeometry::compactAgeLabel) ?: ""
        holder.markerAge.visibility = if (ageLabel == null) View.GONE else View.VISIBLE
        holder.markerAge.setTextColor(ctx.getColor(if (legAgeSeconds?.let {
                it >= SourceFreshness.STALE_AFTER_MS / 1_000L } == true) R.color.late else R.color.live))
        holder.markerAge.contentDescription = ageLabel?.let { "Last reported event $it" } ?: ""
        holder.marker.setTextColor(ctx.getColor(R.color.live))
        val verified = s.source == TrackModel.MinuteSource.REPORTED
        val website = s.source == TrackModel.MinuteSource.SITE_REPORTED
        val suggestedDep = websiteTentativeDeparture?.takeIf { website &&
            it.first.equals(s.code, true) }?.second
        val pendingDep = websitePendingDeparture?.takeIf { website &&
            it.first.equals(s.code, true) }?.second
        val siteCard = websiteCardTimes[s.code]
        val stationClaim = reports[s.code]?.startsWith("RailYatri root claims") == true
        holder.status.text = when {
            pendingDep != null && siteCard == null -> "Site dep ${RouteClockUi.clock(pendingDep)}? · unconfirmed"
            website && s.showDep != null && s.showArr == null ->
                "Website arrival not validated · departure shown"
            website && s.showDep != null -> "Departed · later position unknown"
            website -> "Arrived · departure unknown"
            stationClaim -> "At station claimed · time unknown"
            verified && s.isAnchor && s.showDep != null -> "Departed station · later position unknown"
            verified && s.showDep != null -> "Departed · reported"
            verified && s.isAnchor && s.state == TrackModel.State.PASSED -> "Departed · time unknown"
            verified && s.showArr != null -> "Arrived at station · departure unknown"
            s.state == TrackModel.State.PASSED -> "Past on schedule · actual unknown"
            s.state == TrackModel.State.HERE -> "At stop on schedule · unconfirmed"
            s.state == TrackModel.State.NEXT && s.source == TrackModel.MinuteSource.PROJECTED ->
                "Next · estimated, not confirmed"
            s.state == TrackModel.State.NEXT -> "Next · schedule only"
            s.source == TrackModel.MinuteSource.PROJECTED -> "Estimated · not reported"
            else -> "Scheduled"
        }
        // Reported clocks have provenance in their content descriptions. Do not
        // repeat unhelpful prose below every halt; preserve exceptional warnings.
        holder.status.visibility = if (s.state == TrackModel.State.CANCELLED ||
            s.state == TrackModel.State.SKIPPED || stationClaim ||
            pendingDep != null && siteCard == null)
            View.VISIBLE else View.GONE
        holder.status.contentDescription = if (pendingDep != null)
            "Website suggests departure ${RouteClockUi.clock(pendingDep)}; unconfirmed, not an actual departure"
        else holder.status.text.toString()
        holder.status.setTextColor(ctx.getColor(when {
            pendingDep != null -> R.color.warn
            stationClaim || website || verified -> R.color.live
            s.source == TrackModel.MinuteSource.PROJECTED -> R.color.warn
            else -> R.color.text3
        }))
        val siteComparison = WebsiteGapUi.caption(s, websiteComparisonTimes[s.code], websiteComparisonSaved)
        holder.websitePastComparison.text = siteComparison.orEmpty()
        holder.websitePastComparison.visibility = if (siteComparison == null) View.GONE else View.VISIBLE
        holder.websitePastComparison.contentDescription = siteComparison?.let {
            "$it. Website past card; run date inferred from timetable. Not an NTES actual or current position."
        } ?: ""
        holder.report.text = reports[s.code].orEmpty()
        holder.report.visibility = View.GONE // Source explanations belong in Source details.
        // Two concise pairs are always present: dim timetable at left, selected
        // source at right. Arrows mean arrival / departure; missing reports stay —.
        fun time(minute: Int?): String = RouteClockUi.clock(minute)
        val isFirst = position == 0 && s.schedArr == null
        val isLast = position == itemCount - 1 && s.schedDep == null
        holder.schedArr.text = if (isFirst) "START" else time(s.schedArr)
        holder.schedDep.text = if (isLast) "END" else time(s.schedDep)
        val day = RouteClockUi.departureDay(s.schedDep, s.schedArr)
        holder.schedDay.text = day.orEmpty()
        holder.schedDay.visibility = if (day == null) View.GONE else View.VISIBLE
        holder.schedDay.contentDescription = day?.let { "Run ${it.replace("day", "day ")}" } ?: ""
        if (!batteryMode) {
            DirectionArrowDrawable.attach(holder.schedArr, arriving = true, reported = false)
            DirectionArrowDrawable.attach(holder.schedDep, arriving = false, reported = false)
        }
        val reported = s.source != TrackModel.MinuteSource.SCHEDULE
        val late: Boolean = (s.delay ?: 0) >= 3
        fun siteTime(minute: Int?): String = when {
            minute == null -> "—"
            siteCard?.forecast == true -> "~${time(minute)}"
            else -> time(minute)
        }
        holder.liveArr.text = if (isFirst) "START" else if (siteCard != null)
            siteTime(siteCard.arrivalMin) else time(s.showArr.takeIf { reported })
        holder.liveDep.text = if (isLast) "END" else if (siteCard != null)
            siteTime(siteCard.departureMin) else if (suggestedDep != null)
            "${time(suggestedDep)}?" else time(s.showDep.takeIf { reported })
        val actualDay = RouteClockUi.departureDay(
            siteCard?.departureMin ?: (suggestedDep ?: s.showDep).takeIf { reported },
            siteCard?.arrivalMin ?: s.showArr.takeIf { reported })
        holder.liveDay.text = actualDay.orEmpty()
        holder.liveDay.visibility = if (actualDay == null) View.GONE else View.VISIBLE
        holder.liveDay.contentDescription = actualDay?.let { "Reported run ${it.replace("day", "day ")}" } ?: ""
        if (!batteryMode) {
            DirectionArrowDrawable.attach(holder.liveArr, arriving = true, reported = reported)
            DirectionArrowDrawable.attach(holder.liveDep, arriving = false, reported = reported)
        }
        val evidence = when (s.source) {
            TrackModel.MinuteSource.SITE_REPORTED -> "Website claim"
            TrackModel.MinuteSource.PROJECTED -> "Estimate"
            TrackModel.MinuteSource.REPORTED -> "Reported"
            else -> "Not reported"
        }
        holder.schedArr.contentDescription = if (isFirst) "Start of route, scheduled departure ${s.schedDep?.let(RailTime::formatRailMinute) ?: "unknown"}"
            else "Scheduled arrival ${s.schedArr?.let(RailTime::formatRailMinute) ?: "unknown"}"
        holder.schedDep.contentDescription = if (isLast) "End of route, scheduled arrival ${s.schedArr?.let(RailTime::formatRailMinute) ?: "unknown"}"
            else "Scheduled departure ${s.schedDep?.let(RailTime::formatRailMinute) ?: "unknown"}"
        holder.liveArr.contentDescription = if ("${s.code}:arr" in savedOfficialClocks && reported)
            "Earlier accepted NTES arrival ${s.showArr?.let(RailTime::formatRailMinute) ?: "unknown"}; saved for this run, not a new event"
            else if (isFirst) "Start of route; $evidence departure ${s.showDep?.let(RailTime::formatRailMinute) ?: "unknown"}"
            else if (siteCard != null) "Website ${if (siteCard.forecast) "forecast" else "displayed"} arrival " +
                (siteCard.arrivalMin?.let(RailTime::formatRailMinute) ?: "unknown")
            else "$evidence arrival ${s.showArr?.takeIf { reported }?.let(RailTime::formatRailMinute) ?: "unknown"}"
        holder.liveDep.contentDescription = if ("${s.code}:dep" in savedOfficialClocks && reported)
            "Earlier accepted NTES departure ${s.showDep?.let(RailTime::formatRailMinute) ?: "unknown"}; saved for this run, not a new event"
            else if (isLast) "End of route; $evidence arrival ${s.showArr?.let(RailTime::formatRailMinute) ?: "unknown"}"
            else if (siteCard != null) "Website ${if (siteCard.forecast) "forecast" else "displayed"} departure " +
                (siteCard.departureMin?.let(RailTime::formatRailMinute) ?: "unknown") +
                "; last card is not a current position"
            else if (suggestedDep != null)
            "Unconfirmed website departure suggestion ${time(suggestedDep)}; not actual"
            else "$evidence departure ${s.showDep?.takeIf { reported }?.let(RailTime::formatRailMinute) ?: "unknown"}"
        // Late = red (the colour a traveller scans for); on-time actual = green; estimate = amber.
        // A pure-timetable row stays grey in both - no number pretends to be news.
        val color = when {
            !reported -> R.color.text2
            s.source == TrackModel.MinuteSource.PROJECTED ||
                s.source == TrackModel.MinuteSource.SITE_REPORTED -> R.color.warn
            else -> R.color.live
        }
        holder.liveArr.setTextColor(ctx.getColor(if (siteCard != null) R.color.warn else color))
        holder.liveDep.setTextColor(ctx.getColor(if (siteCard != null) R.color.warn else color))
        val hasShownTime = (reported && (s.showArr != null || s.showDep != null)) ||
            siteCard?.arrivalMin != null || siteCard?.departureMin != null
        val primary = if (hasShownTime) s.showDep ?: s.showArr else s.schedArr ?: s.schedDep
        holder.primaryTime.text = primary?.let(RailTime::formatRailMinute) ?: "—"
        // Source attribution stays in the selector/badge and each clock's accessibility text.
        // A '?' remains on an unconfirmed departure; never repeat WEB beside the times.
        holder.timeType.text = ""
        holder.timeType.visibility = View.GONE
        holder.timeType.setTextColor(ctx.getColor(R.color.warn))
        holder.details.visibility = View.GONE
        val points = routePoints[s.code].orEmpty()
        val hasPoints = points.isNotEmpty() || projectedLeg?.takeIf {
            it.fromCode.equals(s.code, true) }?.points?.isNotEmpty() == true
        // Tapping the station reveals dots. A second button under the report clocks
        // displaced their pair above the scheduled clocks on large-font phones.
        holder.stopToggle.visibility = View.GONE
        holder.stopToggle.text = if (expandedCode == s.code) "Hide ▴" else "Stations ▾"
        holder.dot.setBackgroundResource(dotFor(s))
        holder.dot.visibility = if (marker == null) View.VISIBLE else View.GONE
        holder.trainMarker.visibility = if (marker == null) View.GONE else View.VISIBLE
        holder.trainMarker.ageSeconds = if (marker?.code.equals(s.code, true) &&
            marker?.caption?.startsWith("RailYatri station claim") != true) legAgeSeconds else null
        holder.trainMarker.contentDescription = marker?.caption ?: ""
        // Keep the next stop directly after the compact leg. The former 34dp
        // projected-row spacer looked like missing content on a phone.
        val nextKm = currentList.getOrNull(position + 1)?.kmFromOrigin
        val betweenKm = nextKm?.takeIf { it >= 0 && s.kmFromOrigin >= 0 &&
            it >= s.kmFromOrigin }?.minus(s.kmFromOrigin)
        val screenDp = ctx.resources.displayMetrics.heightPixels / ctx.resources.displayMetrics.density
        val hasTrainLeg = projectedLeg?.fromCode.equals(s.code, true)
        val gapPx = betweenKm?.let {
            (LegGeometry.stopGapDp(it, screenDp) * ctx.resources.displayMetrics.density).toInt()
        } ?: 0
        holder.row.setPadding(holder.row.paddingLeft,
            ctx.resources.getDimensionPixelSize(R.dimen.gap_xs),
            holder.row.paddingRight,
            ctx.resources.getDimensionPixelSize(R.dimen.gap_xs) + if (hasTrainLeg || hasPoints || nextKm == null) 0 else gapPx)
        // The stop dot and its label already mark the reported halt. Full-width
        // row bands made this look like a data table, not a continuous route.
        holder.row.setBackgroundColor(0x00000000)
        val nextStop = currentList.getOrNull(position + 1)
        val leg = projectedLeg?.takeIf { it.fromCode.equals(s.code, true) }
        val disclosed = expandedCode == s.code && hasPoints
        // Pure presentation of the SAME row that already owns the locomotive.
        // A website comparison or a timetable dot cannot gain a reported clock.
        val captionMinute = (s.showDep ?: s.showArr).takeIf {
            (expandedCode == null || batteryMode) && marker != null &&
                (s.source == TrackModel.MinuteSource.REPORTED && !marker.website ||
                 s.source == TrackModel.MinuteSource.SITE_REPORTED && marker.website)
        }
        holder.eventCaption.visibility = if (captionMinute == null) View.GONE else View.VISIBLE
        holder.eventCaption.text = captionMinute?.let { minute ->
            if (batteryMode) {
                val event = if (marker?.website == true) "site arrival" else if (s.showDep != null)
                    "NTES departure" else "NTES arrival"
                "At ${RowText.of(s.name)} · as of ${RailTime.formatRailMinuteBare(minute)}" +
                    "\n$event · ${legAgeSeconds?.let(LegGeometry::compactAgeLabel) ?: "age unknown"}"
            } else if (marker?.website == true)
                "Last site arrival · ${RailTime.formatRailMinuteBare(minute)}"
            else "At ${RowText.of(s.name)} · as on ${RailTime.formatRailMinuteBare(minute)}"
        }.orEmpty()
        holder.eventCaption.setTextColor(ctx.getColor(if (marker?.website == true) R.color.warn else R.color.text2))
        holder.eventCaption.contentDescription = captionMinute?.let {
            "${if (marker?.website == true) "Website reports" else "Last reported at"} " +
                "${RowText.of(s.name)}, event time ${RailTime.formatRailMinute(it)}. " +
                "Present position unknown."
        }.orEmpty()
        val available = if (points.isEmpty()) leg?.points.orEmpty() else points.mapIndexed { index, point ->
            val matching = leg?.points?.firstOrNull { it.code.equals(point.code, true) }
            val kmFraction = nextStop?.let { next ->
                val fromKm = mappedAnchorKm[s.code.uppercase()]
                val toKm = mappedAnchorKm[next.code.uppercase()]
                if (fromKm != null && toKm != null && toKm > fromKm &&
                    point.schedKm > fromKm && point.schedKm < toKm)
                    (point.schedKm - fromKm).toFloat() / (toKm - fromKm) else null
            }
            val fraction = matching?.fraction ?: kmFraction ?: (index + 1f) / (points.size + 1f)
            // Do not give the historical 16227 corridor invented passing clocks.
            // Other accepted maps retain their existing explicitly ~ approximate
            // schedule interpolation; they never become actual or live times.
            val planned = point.arrivalMin ?: if (mapSource?.startsWith("Offline historical") == true)
                null else nextStop?.let { next ->
                val from = s.schedDep ?: s.schedArr
                val to = next.schedArr ?: next.schedDep
                if (kmFraction != null && from != null && to != null && to >= from)
                    (from + (to - from) * kmFraction).roundToInt() else null
            }
            val web = websiteIntermediateReports[point.code]
            // A timetable's halt flag, or a validated arrival+later departure,
            // establishes a stop. An arrival-only non-halt is a website passage
            // claim, displayed as such, never an independently verified Act.
            val stopped = point.isHalt || (web?.actualArrivalMin != null &&
                web.actualDepartureMin != null && web.actualDepartureMin > web.actualArrivalMin)
            RouteBetween.LegPoint(point.code, point.name, point.schedKm, fraction,
                planned, point.arrivalMin == null && planned != null, stopped,
                web?.actualArrivalMin,
                web?.actualDepartureMin.takeIf { stopped })
        }
        if (captionMinute != null) {
            val mapFrom = mappedAnchorKm[s.code.uppercase()]
            val mapTo = nextStop?.let { mappedAnchorKm[it.code.uppercase()] }
            val firstPoint = available.firstOrNull { mapFrom != null && mapTo != null &&
                it.mapKm > mapFrom && it.mapKm < mapTo }
            val boundName = firstPoint?.name ?: nextStop?.name
            val fromKm = if (firstPoint != null) mapFrom else s.kmFromOrigin
            val boundKm = firstPoint?.mapKm ?: nextStop?.kmFromOrigin
            val staticKm = if (fromKm != null && fromKm >= 0 && boundKm != null && boundKm > fromKm)
                boundKm - fromKm else null
            if (boundName != null && staticKm != null) {
                holder.eventCaption.text = holder.eventCaption.text.toString() +
                    "\n$staticKm km route gap to ${RowText.of(boundName)}"
                holder.eventCaption.contentDescription = holder.eventCaption.contentDescription.toString() +
                    " Static route gap $staticKm kilometres to ${RowText.of(boundName)}, not live remaining distance."
            }
        }
        // A mapped leg has a visible, full-width rail even while its station names
        // are folded. Previously only the stop row was tappable until expansion.
        val displayedLeg = leg?.copy(points = available) ?: if (hasPoints && nextStop != null)
            RouteBetween.LegProjection(s.name, nextStop.name, 0f, null, s.kmFromOrigin,
                s.code, nextStop.code, available) else null
        val mapUnavailable = nextStop != null && available.isEmpty() &&
            s.code.uppercase() !in mappedLegStarts
        holder.inlineLegGroup.visibility = if (displayedLeg != null) View.VISIBLE else View.GONE
        if (displayedLeg == null) {
            // Recycled children must not retain the previous leg's disclosure.
            holder.inlineLegCaption.visibility = View.GONE
            holder.routeToggle.visibility = View.GONE
            holder.inlineLegAge.visibility = View.GONE
        }
        holder.inlineLeg.projection = displayedLeg
        holder.inlineLeg.historicalMap = mapSource?.startsWith("Offline historical") == true
        holder.inlineLeg.compactStatic = leg == null
        holder.inlineLeg.showLabels = disclosed
        // A pinned halt uses the locomotive IN its station row. Do not draw a
        // second locomotive 24dp down in the next leg (the apparent third dot).
        holder.inlineLeg.showTrain = leg != null &&
            !(leg.fraction == 0f && marker?.code.equals(s.code, true))
        holder.inlineLeg.distanceKm = nextStop?.let { (it.kmFromOrigin - s.kmFromOrigin).coerceAtLeast(0) } ?: 0
        holder.inlineLeg.ageSeconds = if (leg != null &&
            leg.markerKind != RouteBetween.MarkerKind.PROJECTED) legAgeSeconds else null
        holder.inlineLeg.siteEventMinute = if (leg?.markerKind ==
            RouteBetween.MarkerKind.SITE_INTERMEDIATE) legWebsiteEventMinute else null
        if (displayedLeg != null) {
            val web = leg?.markerKind == RouteBetween.MarkerKind.WEB_CACHE_CANDIDATE
            val site = leg?.markerKind == RouteBetween.MarkerKind.SITE_INTERMEDIATE
            val siteHalt = leg?.markerKind == RouteBetween.MarkerKind.SITE_LAST_STOP
            val reportedHalt = leg?.markerKind == RouteBetween.MarkerKind.REPORTED_HALT
            val stationClaim = leg?.markerKind == RouteBetween.MarkerKind.STATION_CLAIM
            val departureCue = DepartureCue.isCue(leg?.markerKind)
            // The cue is a state, not a kilometre: name the first mapped point
            // while folded. Expanded names make this sentence redundant.
            val firstPoint = available.firstOrNull { it.fraction > 0f && it.fraction < 1f &&
                mappedAnchorKm[s.code.uppercase()]?.let { from -> it.mapKm > from } == true }
            val caption = if (departureCue && !disclosed) {
                val bound = firstPoint?.name ?: nextStop?.name
                val fromKm = if (firstPoint != null) mappedAnchorKm[s.code.uppercase()]
                    else s.kmFromOrigin
                val gap = (firstPoint?.mapKm ?: nextStop?.kmFromOrigin)?.let { to ->
                    fromKm?.let { from -> (to - from).takeIf { from >= 0 && it > 0 } }
                }
                MarkerDistanceLabel.next(bound, gap,
                    // A provisional website departure is not an observed train event.
                    if (suggestedDep != null) s.showArr else s.showDep ?: s.showArr,
                    holder.inlineLeg.ageSeconds?.let {
                        it >= SourceFreshness.STALE_AFTER_MS / 1_000L } == true)
            } else ""
            holder.inlineLegCaption.text = caption
            holder.inlineLegCaption.visibility = if (caption.isEmpty()) View.GONE else View.VISIBLE
            holder.inlineLegCaption.setTextColor(ctx.getColor(R.color.text2))
            holder.inlineLegCaption.contentDescription = if (caption.isEmpty()) "" else
                "$caption. This is a static station-to-station distance, not live remaining kilometres."
            holder.inlineLegAge.text = holder.inlineLeg.ageSeconds?.let { seconds ->
                if (seconds < 60) "Just now" else TableMarker.elapsedLabel(seconds * 1_000L) + " ago"
            }.orEmpty()
            // The age belongs beside the locomotive, not on a separate line near stop names.
            holder.inlineLegAge.visibility = View.GONE
            holder.inlineLegAge.contentDescription = if (holder.inlineLegAge.text.isEmpty()) ""
                else "Last reported event ${holder.inlineLegAge.text}"
            holder.routeToggle.text = when {
                disclosed -> "Hide ▴"
                else -> "Stations ▾"
            }
            holder.routeToggle.contentDescription = when {
                disclosed -> "Hide intermediate stations"
                else -> "Show ${available.size} intermediate stations"
            }
            holder.routeToggle.visibility = if (available.isNotEmpty()) View.VISIBLE else View.GONE
            holder.routeToggle.setOnClickListener {
                toggleStations(s.code, position, holder)
            }
            val pointDescription = available.joinToString("; ") { point ->
                val clock = point.passageMinute?.let {
                    (if (point.approximate) "approximately " else "scheduled ") +
                        RailTime.formatRailMinute(it)
                } ?: "passage time unavailable"
                val actual = point.siteArrivalMin?.let {
                    ", website ${if (point.isHalt) "arrival" else "passage"} claim " +
                        RailTime.formatRailMinute(it)
                } ?: ", no validated site passage"
                "${RowText.of(point.name)} (${point.code}), $clock$actual" +
                    (if (point.mapKm >= 0) ", ${point.mapKm} km" else ", distance unavailable")
            }
            holder.inlineLeg.contentDescription = if (leg == null)
                "Intermediate stations on ${mapSource ?: "the static route"}; no train-position claim. " +
                    pointDescription
            else buildString {
                append(caption)
                append(holder.inlineLeg.ageSeconds?.let {
                    " · reported event ${LegGeometry.ageLabel(it)}"
                } ?: " · event time unknown")
                if (departureCue) {
                    append(" · symbolic departure icon only; no measured distance; not GPS from ")
                    append(displayedLeg.from).append(" towards ")
                    append(firstPoint?.name ?: displayedLeg.to)
                    if (suggestedDep != null) append("; website departure unconfirmed")
                } else {
                    append(" · not GPS · ${displayedLeg.from} to ${displayedLeg.to} ")
                    append("${(displayedLeg.fraction * 100).toInt()} percent of scheduled leg by ")
                    append(when {
                        stationClaim -> "unverified station claim (no passage time or measured distance)"
                        reportedHalt || siteHalt -> "last reported halt (no measured distance since)"
                        site -> "static map of the site-reported station"
                        web -> "web-cache km claim"
                        else -> "estimated distance"
                    })
                }
                if (site && holder.inlineLeg.siteEventMinute != null)
                    append(" · website-published event ")
                        .append(RailTime.formatRailMinute(holder.inlineLeg.siteEventMinute!!))
                if (pointDescription.isNotEmpty())
                    append(" · intermediate stations from ").append(mapSource ?: "static map")
                        .append(": ").append(pointDescription)
            }
        }
        fun onLegTap() {
            `in`.opentrack.india.core.Diary.get()?.log("ROUTE_UI") {
                "tap leg ${s.code}->${nextStop?.code ?: "end"} " +
                    "points=${available.size} expanded=$disclosed map=${if (mapUnavailable) "unavailable" else "available"}"
            }
            if (hasPoints) toggleStations(s.code, position, holder)
            else if (mapUnavailable) onMapUnavailable?.invoke(s.code)

        }
        holder.row.setOnClickListener { onLegTap() }
        holder.row.isClickable = hasPoints || mapUnavailable
        // The line, dots, blank leg and caption are all one tap target, not only
        // the station name or tiny Hide button. A second tap folds the same leg.
        holder.inlineLegGroup.isClickable = hasPoints || mapUnavailable
        holder.inlineLegGroup.setOnClickListener { onLegTap() }
        holder.inlineLeg.isClickable = hasPoints || mapUnavailable
        holder.inlineLeg.setOnClickListener { onLegTap() }
        if (batteryMode) {
            // Keep both arrival/departure rows legible without a four-times-per-row
            // animated arrow. A/D are literal text, not a moving icon.
            for (v in listOf(holder.schedArr, holder.liveArr)) {
                (v.compoundDrawables[0] as? DirectionArrowDrawable)?.stop()
                v.setCompoundDrawables(null, null, null, null)
                v.text = "A ${v.text}"
            }
            for (v in listOf(holder.schedDep, holder.liveDep)) {
                (v.compoundDrawables[0] as? DirectionArrowDrawable)?.stop()
                v.setCompoundDrawables(null, null, null, null)
                v.text = "D ${v.text}"
            }
            // The same qualified site intermediate/stop event as the normal
            // route, but rendered as STATIC white strokes. Do not restore the
            // map canvas, forecast, timetable, provisional departure or GPS.
            val siteLegOutline = leg?.markerKind == RouteBetween.MarkerKind.SITE_INTERMEDIATE &&
                legWebsiteEventMinute != null && legAgeSeconds != null &&
                holder.inlineLeg.showTrain
            holder.inlineLeg.batteryOutline = siteLegOutline
            holder.inlineLegGroup.visibility = if (siteLegOutline ||
                holder.inlineLegCaption.text.isNotEmpty()) View.VISIBLE else View.GONE
            holder.routeToggle.visibility = View.GONE
            holder.inlineLeg.visibility = if (siteLegOutline) View.VISIBLE else View.GONE
            if (siteLegOutline) holder.inlineLeg.contentDescription =
                "Last website report at ${leg?.nearestPoint ?: "intermediate"}, as of " +
                    "${RailTime.formatRailMinute(legWebsiteEventMinute!!)}, " +
                    "${LegGeometry.ageLabel(legAgeSeconds!!)}; present position unknown"
            holder.dot.visibility = View.GONE
            val stoppedReport = marker != null && captionMinute != null && legAgeSeconds != null
            holder.trainMarker.batteryOutline = stoppedReport
            holder.trainMarker.visibility = if (stoppedReport) View.VISIBLE else View.GONE
            holder.trainMarker.contentDescription = if (stoppedReport)
                "Last ${if (marker!!.website) "site" else "NTES"} report at ${s.name}, " +
                    "as of ${RailTime.formatRailMinute(captionMinute!!)}, " +
                    "${LegGeometry.ageLabel(legAgeSeconds!!)}; present position unknown" else ""
            holder.markerAge.visibility = if (stoppedReport) View.VISIBLE else View.GONE
            holder.marker.visibility = View.GONE
            holder.row.setPadding(holder.row.paddingLeft,
                ctx.resources.getDimensionPixelSize(R.dimen.gap_xs), holder.row.paddingRight,
                ctx.resources.getDimensionPixelSize(R.dimen.gap_xs))
        }
        if (batteryMode) BatteryMode.paint(holder.itemView)
        holder.row.contentDescription = "${s.name}; " +
            "${holder.schedArr.contentDescription}; ${holder.schedDep.contentDescription}; " +
            "${holder.liveArr.contentDescription}; ${holder.liveDep.contentDescription}" +
            when {
                hasPoints -> "; tap to ${if (disclosed) "hide" else "show"} " +
                    "${available.size} intermediate stations"
                mapUnavailable -> "; intermediate map unavailable; tap for details"
                else -> ""
            }
    }

    private fun toggleStations(code: String, fallbackPosition: Int, holder: Holder) {
        val previous = expandedCode
        expandedCode = if (previous == code) null else code
        // Show the result on this very tap, even if RecyclerView is deferring a
        // change animation/rebind while the user scrolls the long train route.
        val opened = expandedCode == code
        holder.inlineLeg.showLabels = opened
        // The station names themselves replace the folded between-points sentence.
        if (DepartureCue.isCue(holder.inlineLeg.projection?.markerKind))
            holder.inlineLegCaption.visibility = if (opened) View.GONE else View.VISIBLE
        holder.routeToggle.text = if (opened) "Hide ▴" else "Stations ▾"
        holder.routeToggle.contentDescription = if (opened)
            "Hide intermediate stations" else "Show intermediate stations"
        holder.inlineLeg.requestLayout()
        holder.inlineLeg.invalidate()
        `in`.opentrack.india.core.Diary.get()?.log("ROUTE_UI") {
            "leg $code ${if (opened) "opened" else "closed"}; inline points=" +
                (holder.inlineLeg.projection?.points?.size ?: 0)
        }
        if (previous != null && previous != code) currentList.indexOfFirst { it.code == previous }
            .takeIf { it >= 0 }?.let(::notifyItemChanged)
        notifyItemChanged(holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
            ?: fallbackPosition)
    }

    /**
     * One short word under the live column, naming what that column holds.
     *
     * The rule the user asked for is exactly this: a stop the train has covered is scheduled *and*
     * actual, a stop still ahead is scheduled *and* expected. Colour separates them in the columns
     * themselves, which is the fast read; the word is the slow read, and it is what makes a screenshot
     * or a colour-blind passenger unambiguous. "no report" and "timetable" are spelled out rather than
     * left blank because a passed stop with no actual minute, and a screen with no feed at all, are
     * statements about the data — and a dash reads like the app gave up.
     */
    private fun tagFor(s: TrackModel.Stop, ctx: android.content.Context): String = when {
        s.state == TrackModel.State.CANCELLED -> ctx.getString(R.string.tag_cancelled)
        s.state == TrackModel.State.SKIPPED -> ctx.getString(R.string.tag_skipped)
        s.state == TrackModel.State.HERE -> ctx.getString(R.string.tag_here)
        // The one row where counting down beats labelling, and the header repeats it anyway.
        s.state == TrackModel.State.NEXT ->
            s.minutesFromNow?.let { TrackModel.formatGap(it) } ?: ctx.getString(R.string.tag_due)
        s.showArr == null && s.showDep == null -> ctx.getString(R.string.tag_no_report)
        s.source == TrackModel.MinuteSource.PROJECTED -> ctx.getString(R.string.tag_expected)
        s.source == TrackModel.MinuteSource.SITE_REPORTED -> "Site report"
        s.source == TrackModel.MinuteSource.SCHEDULE -> ctx.getString(R.string.tag_timetable)
        else -> ctx.getString(R.string.tag_actual)
    }

    private fun tagColorFor(s: TrackModel.Stop): Int = when {
        s.state == TrackModel.State.HERE && s.source == TrackModel.MinuteSource.REPORTED &&
            s.showArr != null && s.showDep == null -> R.color.warn
        s.state == TrackModel.State.HERE && s.source == TrackModel.MinuteSource.REPORTED -> R.color.accent
        s.state == TrackModel.State.CANCELLED || s.state == TrackModel.State.SKIPPED -> R.color.late
        s.state == TrackModel.State.NEXT -> R.color.text1
        s.source == TrackModel.MinuteSource.PROJECTED ||
            s.source == TrackModel.MinuteSource.SITE_REPORTED -> R.color.warn
        s.source == TrackModel.MinuteSource.SCHEDULE -> R.color.text3
        s.showArr != null || s.showDep != null -> R.color.text2
        else -> R.color.text3
    }

    /** A source does not recolour the route. The row's Site/Act wording and the
     * hero convey confidence; identical reported stop states get identical dots. */
    private fun hasStopEvent(s: TrackModel.Stop): Boolean =
        s.source == TrackModel.MinuteSource.REPORTED ||
            s.source == TrackModel.MinuteSource.SITE_REPORTED

    internal fun dotFor(s: TrackModel.Stop): Int = when (s.state) {
        // An arrival without departure is an at-station report, not a passed
        // halt. Give it the ring used for HERE on both NTES and the website.
        TrackModel.State.HERE -> if (hasStopEvent(s))
            R.drawable.dot_here else R.drawable.dot_later
        TrackModel.State.PASSED -> if (hasStopEvent(s))
            R.drawable.dot_passed else R.drawable.dot_later
        TrackModel.State.CANCELLED, TrackModel.State.SKIPPED -> R.drawable.dot_problem
        else -> R.drawable.dot_later
    }

    private fun colorFor(s: TrackModel.Stop): Int = when {
        s.state == TrackModel.State.CANCELLED || s.state == TrackModel.State.SKIPPED -> R.color.late
        s.state == TrackModel.State.HERE && hasStopEvent(s) &&
            s.showArr != null && s.showDep == null -> R.color.warn
        s.state == TrackModel.State.HERE && hasStopEvent(s) -> R.color.accent
        s.source == TrackModel.MinuteSource.SCHEDULE -> R.color.text2
        s.state == TrackModel.State.LATER -> R.color.text2
        else -> R.color.text1
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TrackModel.Stop>() {
            override fun areItemsTheSame(a: TrackModel.Stop, b: TrackModel.Stop) = a.seq == b.seq
            override fun areContentsTheSame(a: TrackModel.Stop, b: TrackModel.Stop) = a == b
        }
    }
}

/**
 * Name casing and the small vocabularies the rows print.
 *
 * Named for what it does to a row rather than for the casing rule it delegates to: `TitleCase` is a
 * top-level object in `domain`, and a second type of that name in the same package as `Suggestion` once
 * made the pair unreadable (the checker flagged it the moment they coexisted).
 */
object RowText {
    fun of(raw: String, keep: Set<String> = emptySet()): String = TitleCase.of(raw, keep)

    fun rows(hits: List<StationHit>): List<Suggestion> = hits.map { Suggestion.ofStation(it) }
    fun trainRows(hits: List<TrainSummary>): List<Suggestion> = hits.map { Suggestion.ofTrain(it) }

    /** "SUPERFAST_EXPRESS" -> "Superfast Exp"; the abbreviations timetables use, kept short. */
    fun type(raw: String): String {
        val t = of(raw)
        return when {
            t.equals("Superfast Express", true) -> "SF Exp"
            t.equals("Mail/Express", true) -> "Exp"
            else -> if (t.length > 12) t.substringBefore(' ') else t
        }
    }

    fun dayNames(mask: Int): String {
        val names = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val bits = ArrayList<String>(7)
        for (i in 0..6) if (mask and (1 shl (6 - i)) != 0) bits += names[i]
        return when (bits.size) {
            7 -> "daily"
            0 -> "not scheduled"
            else -> bits.joinToString("/")
        }
    }
}
