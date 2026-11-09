# R163 · Google Where is my Train phone-app share intake (not a feed)

## Deep dive and boundary

The actual Google/Sigmoid Labs Android app is `com.whereismytrain.android`, **not** the similarly named `whereismytrain.org.in` website or the separate undocumented `.in/cache/live_status` web cache. Its [Google Play listing](https://play.google.com/store/apps/details?id=com.whereismytrain.android&hl=en_IN) explicitly advertises **“share current train location with your friends & family via the share feature”** and offline/onboard cell-tower location. Neither the listing nor the app's screens document an external machine-readable status API, response schema, permissions, or a contract permitting a third-party client to poll that telemetry. Offline position on one phone is not automatically a network response another phone can query.

Public GitHub search found a [third-party MCP](https://github.com/developeranaz/WhereIsMyTrain-Precise-MCP) claiming similarity to the phone app. Its own README calls its API **unofficial**, says its API and implementation are not published, warns it may disappear, and gives no provider authorization or station-event contract. We did not contact that endpoint, copy identifiers, reproduce its private protocol, inspect the phone app's internals or treat it as an integration path. A separate publicly documented app-to-app feature *does* exist: the user can choose to share a location through the system share sheet. The share's actual text/link shape is not documented, and Android's share intent does not authenticate its sender.

## Implementation

An exported `ACTION_SEND` + `text/plain` share target named **TrackTrain · preview shared status** receives only a **user-selected** share. It accepts at most 8192 characters, rejects empty/invalid/binary input without truncation, keeps it on the current screen, and displays it as untrusted plain text. It detects a link-only share and explicitly does not open or fetch the link. It never persists the share, logs its contents, sends it to a server, claims it came from Google, adds a source to the live selector, or promotes it to a station `Act`, train marker or ETA. Settings, selected-source refresh, R162 refusal pacing and the existing table do not change. An opt-in OLED screen uses the existing BatteryMode painting. The existing stable debug signing key is unchanged.

This is **readiness for a real share sample**, not a working automatic WIMT phone-app feed. It is intentionally not an escape hatch around the `.org.in` 403. A real phone must confirm that the WIMT share sheet lists TrackTrain and that it provides useful user-shareable text rather than just a marketing or web URL. The only current evidence is the advertised share feature, not a captured share payload.

## Next acceptance gate

1. Install R163 over the previous signed APK; in the actual WIMT Android app, open a train's status and use **Share current train location → TrackTrain · preview shared status**. Confirm the share reaches the preview; if not, report the WIMT share menu/sheet screenshot, MIME type if available and device version. Do not send PNR, ticket, phone number or personal location.
2. If willing, copy a **redacted** example of the preview. Before any parser/table change, determine whether it includes a train number, operating date, a named station, event clock, freshness and whether it is a current event or merely a link. A share that contains only a URL cannot establish intermediate arrivals or a live position.
3. For automatic direct WIMT app data, obtain a documented third-party access agreement and schema from the operator. Never represent the share path, websites or NTES as a phone-app response. PR #3 stays unmerged until real-device acceptance.
