# 20689 / 14 October 2026: source audit behind R62

Evidence: user's R61 screenshots at 13:16, 13:21 and 13:22 IST; pasted OpenTrack diagnostic excerpt from 13:14–13:16 IST; bounded read-only `whereismytrain.in/cache/live_status` response at approximately 13:24 IST. The attached diary file was not available in the sandbox; this audit uses the pasted excerpt, not an invented continuation of that file.

| Field | OpenTrack NTES at 13:16 | Where is My Train **phone app** at 13:21 | WIMT **web-cache endpoint** at ~13:24 | Interpretation |
|---|---|---|---|---|
| Kadur (DRU) arrival | 12:28 | 12:57 | 12:27 | Disputed. Do not overwrite an official cell from the web cache or call either independently proven. |
| Kadur departure | 13:02 | 13:05 | 13:03 field, but its `updated_at` is 12:48 | Web cache's departure fails event-time check. |
| Kadur platform | 2 | 2 | 1 | Dynamic provider claims conflict; don't present as a guaranteed platform. |
| Birur (RRB) arrival/departure | 13:12 / 13:14 | 13:12 / 13:17 | arrival 13:13, departure field still static 12:04 | Arrival differs by 1m; web-cache departure is not observed. |
| Tarikere (TKE) arrival/departure | 13:40 / 13:42 **forecast**, not actual | 13:33 / 13:35 displayed on app | 12:30 / 12:32 `source: eta` placeholders | Web-cache `actual_*` field names can be projections. App times must not be silently assumed to be the cache response. |
| Position | NTES's last halt Birur + elapsed-time projection | about 4 km to Sivapur, updated seconds ago | `distance: 205.97`, `curStn: RRB` | Web cache is **not** the phone app's fresh GPS/cell-tower position. NTES isn't GPS either. |
| Static km | DRU 200, RRB 206, TKE 230; separate point map SPV 216, KRNH 220 | DRU 199, TKE 229 | DRU 198.74, RRB ~205.5, TKE 229.9 | Different source rounding/chainage. Keep labels saying *schedule km* versus *static map km*; don't compare them as a measured moving position. |

At 13:14–13:16, the owner's phone accepted 23,869-byte dated NTES envelopes in ~319–427 ms; 10 scheduled halts, 7 rows with reported minutes and feed age 2–4 min. It also fetched 10 scheduled halts + 31 untimed points from ConfirmTkt; RailGeo returned its maintenance notice. The identical response size and unchanged NTES minutes across repeated refreshes show that fast HTTP does **not** mean a newer railway event. The `LIVE` badge only says a same-run feed answered, not that every cell is independently corroborated.

A distinct R61 bug meant selecting the WIMT web-cache candidate changed the spinner but the adapter never displayed its `reports` map; the Act cells necessarily stayed NTES. R62 renders candidate annotations per station with an explicit label, still not Act, GPS or a position source. The name says *web cache*, not *Where is My Train app*, because the screenshots demonstrate those are different answers.

R62 also fixes an independent position error: `PositionEngine` added accumulated delay to the NEXT leg's travel duration, although the late departure timestamp already includes it. For Birur→Tarikere, seven minutes after 13:14 departure on a scheduled 26-minute/24-km leg should project 7/26 along it, **not** 7/(26+70). This is still only an elapsed-time projection and can disagree with a real phone location. The icon is now inside that leg of the table and withheld without a fresh departed NTES leg and usable route distances.

Platforms remain changeable feed reports, never guaranteed; point stopping status remains unknown unless a dated timetable confirms it. A single reported arrival/departure may not cause the other cell's scheduled time to be printed as `Act`. These rules have automated regression tests. This audit does **not** certify every number on every train: it documents which displayed numbers are dated feed facts, rounded static schedule values or estimates and identifies the unresolved disputed cells.
