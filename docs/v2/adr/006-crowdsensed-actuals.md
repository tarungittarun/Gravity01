# ADR-006: crowdsensed per-stop actuals (the WIMT path)

Status: ACCEPTED — build started. The owner's standing authorization ("change whatever you want,
architecture, source or anything", first message of the session, re-demanded after the keyless server
doors were measured shut from both machines) is the decision this ADR was reserved for. Every keyless
fetchable server door is now measured dead with evidence (NTES closed on both machines; erail WAF 403
to all identities; api.erail.in DNS-dead on both machines; railradar 401). The crowdsourced path is the
only one that does not depend on a server the railways can blanket-block: the witnesses are ON the train.

## Context (all measured, not assumed, 2026-10-12)

- NTES closes on **CI runners and on the owner's own phone**, every transport (diag logs 09:50/10:13 IST).
- erail's live-status endpoint **403s every header identity** from CI (runs 548a5db→a339ab6); its tile
  endpoint has **never carried per-stop times** (v1, LiveProvider).
- railradar is **401 keyless**; its key is paid.
- The last untested door (erail's endpoint from erail's own WebView engine) is wired into the shipping
  probe as of today and may still close.
- Where Is My Train (100M+ installs, Google) started by scraping NTES, but its engine is
  **crowdsourcing**: travellers' phones on trains contribute GPS/cell-tower context — "a Google Traffic
  equivalent for trains" (founder interview, theindianotes 2025-08; Wikipedia). Reddit consensus says
  the same: WIMT "uses data given by users".

## Proposal

Travellers who ARE on a train opt in to a **reporter mode**: one bounded report per halt — station
context + coarse GPS + observed departure/arrival, anonymous, no account. Waiting travellers read the
same stream for their train. Relay: a **public, keyless, account-less pub/sub** (ntfy.sh-class service)
— not our server, not keyed, not an account; named in the provenance line as a third-party dependency
and degraded honestly when it is unreachable.

Honesty guards (non-negotiable): reports render as "reported by travellers (N witnesses, last 10 min)",
never as official data; plausibility-checked against the timetable (a report claiming 90 min early is
displayed as suspect or dropped); the screen always keeps the timetable column beside the reported one.

## Consequences

+ The only remaining path that does not depend on a server the railways can blanket-block: the witnesses
  are already past every firewall — they are ON the train.
+ Zero infrastructure of ours; zero keys; zero accounts.
− A third-party relay dependency (labelled, and the screen must say "reports unavailable" when it is down).
− Cold-start: useless until reports exist (WIMT took two years; we will be honest that this is a
  network-effects feature).
− Abuse: liars exist; multi-witness weighting + plausibility bounds keep one liar from owning a screen.
− Privacy: coarse location, opt-in, anonymous, only while reporting is on; stated in plain words on the toggle.

## Decision requested from the owner

(a) **Approve**: implement reporter mode + keyless relay + traveller-report column next.
(b) **Decline**: the app keeps the honest dash and the measured sentences forever.


## Amendment (2026-10-12, evening): reader default flipped to ON

Shipped off-by-default per this ADR; flipped on-by-default the same day on the acceptance
authority's explicit direction: "Priority is to get actual arrival and departure time. Work
for that, do anything." Rationale: reading is passive and renders nothing until a REAL report
exists (the reader column stays empty otherwise — the screen never shows "no reports" noise
per stop); publishing still sits behind the toggle. The privacy consequence is unchanged:
reports carry station code + minute, nothing personal, to a public topic.
