# Public comparison sources · RailYatri and ixigo

Neither is a verified additional railway position feed. Both are selected-source network comparisons in `v2/mirror/PublicPageChecks.kt`; `ui/LiveViewModel.kt` isolates their claims from independently verified `Act` cells. Current access agreement, GPS origin and provider independence are **not established**. See [R76](../R76-position-source-evidence.md) and [R98](../R98-parallel-source-readiness.md).

## RailYatri

| Aspect | Actually requested / parsed | Used vs deliberately not used |
| --- | --- | --- |
| Wire | GET `https://livestatus.railyatri.in/api/v3/train_eta_data/<number>/<offset>.json?start_day=<offset>&user_id=-1&device_type_id=4&change_name=seo_train_name&lat=&lng=&claim_on_train=false`. Offset is selected IST origin day (0–2); account/device-type values are fixed by this app, not proof of a legitimate first-party client. HTML URL in the user's list is **not** this JSON endpoint. | One public request on selection/allowed survey; failures/cache do not become live data. Do not rotate pseudo-user values or spoof a GPS coordinate. |
| JSON | `success`, `train_number`, `train_start_date`, `source`, `destination`, `data_from`, `current_station_code`, `status`, `update_time`; prior samples reported `mntes` and `tmt` as upstream in different runs. Also prints ETA/ETD/previous rows in some variants. | Require selected train, date, endpoints, known code, arrived/departed status and recent update time. Only an amber **published station/status claim** appears; `update_time` is a provider publication clock, not station passage. Future ETA/ETD and historical rows are **not** accepted as official actuals or moving distance. |
| Extra fields | A date offset is genuinely part of this request, unlike the `.org.in` page; `lat=&lng=` are empty in the shipped URL. | No evidenced documented `km to next`, station-event timestamp or independently measured GPS in this response. “Real-time user GPS + smart ETA” in the candidate table is not a finding from this feed. |

## ixigo

| Aspect | Actually requested / parsed | Used vs deliberately not used |
| --- | --- | --- |
| Wire | GET `https://www.ixigo.com/trains/<number>/running-status`, generic website HTML headers. The candidate example `/trains/train-running-status/12951` is **not the path implemented** here. | Request/body success is source availability only; not proof of a run-bound observation. |
| HTML | `PublicPageChecks.page` strips scripts/styles and looks for visible train identity, optional selected-date phrases, statuses (`yet to start`, `reached destination`), `Last Updated`, and bounded station-code comparison excerpts after `Journey Timeline`. | A printed selected date that disagrees with the requested origin date is called out. Excerpts and first clocks may be timetable/ETA; they do **not** populate `Act` or locate the train. No dedicated ixigo event parser qualifies a per-stop actual. |
| Flexibility | Train number is the implemented URL input; selected origin date is checked only if visible in the returned page. | No observed/supported ixigo next-km, signed request variant, independent NTES licence or reliable GPS claim is established. Changing URL to a user-supplied example is a different request needing separate evidence, not an existing source capability. |
