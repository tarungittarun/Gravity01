# OpenTrack India — test card

> **NEW 2026-10-12 — the live station-pair list has landed. Before anything below, do this 90-second check:**
> 1. Search **SMET → MYS** (Shivamogga → Mysuru). Rows appear instantly, then upgrade: several gain
>    "· via erail" — including **16221 Kuvempu Express**, which this app has never shown before.
> 2. The top line ends with a live sentence ("live list via erail · erail data stamp 2026-8-6…").
> 3. Airplane mode, search again: the top line must say **"no live list reached this device — every
>    time below is the timetable"**. Never silent, never looking live.
> 4. **THE BIG ONE — 60 seconds that unlocks live arrival/departure times:** the app can only get
>    "actual" times from the railway's own live feed, and only YOUR phone can reach it (our servers
>    can't — by design we have none; the railway blocks datacentres). So: **Settings → turn ON
>    diagnostics → go back → open any train that is running right now → wait ~20 s on its screen →
>    Settings → Save to file → PASTE the text into the chat** (attachments arrive empty — paste).
>    That one paste shows us exactly what the railway's feed sends your phone, and the next build
>    reads it properly.
> 5. Then continue with the full card below — Part 1 and Part 2 as before.


Hand this to anyone. Nothing here needs a technical background. Roughly 4 minutes for the first two parts,
1 minute more only if live running status does not appear.

**What the app is supposed to do:** find trains between two stations for yesterday, today or tomorrow with no
internet at all, and — when the railway's public feed answers — show where a train actually is right now,
which stops it has cleared, and how late each upcoming stop is expected to be.

**Download (Android 7.0 or newer, about 10.6 MB):**
<https://github.com/tarungittarun/Checktrain02/releases/download/latest-apk/app-debug.apk>

On the download page, check the file size reads **10,591,177 bytes** — if it is smaller, you have an older
build and the results will not mean anything. Tap the file, then *Install anyway* if the phone warns you about
apps from unknown sources (it is a test build, not signed by Play). No account, no login, no key, no ads, no
tracking. Timetables are inside the app, so the whole thing works in airplane mode.

---

## Part 1 — Offline search (should always pass)

1. Open the app. In the two boxes, type a station or a city — **three letters starts suggesting**. Try
   `Bang` → pick **KSR Bengaluru (SBC)**, and `Chenn` → pick **MGR Chennai Central (MAS)**.
2. Tap **−1d**, **now** and **+1d** once each. The date under the boxes must change and the list must change
   with it — that is the whole point of the ±24 h window.
3. Tap **Find trains**.
   - You should see a list of trains for that day, each with names like *… Express*, and a count.
   - If the count is 0, the app must say so in words. A silently empty list is a bug — report it.
4. Turn on **airplane mode** and search again. It must still work. That is the offline promise.

## Part 2 — Live running status (this is the part we need your help with)

1. Pick a train that is running **right now** — not tomorrow, not last week. Trains that leave your city within
   the next hour or two are best. Tap **Track** on its row.
2. Look at the pill under the big headline.

| What you see | What it means |
| --- | --- |
| **LIVE · running status** + a delay like *18 min late* | Working. This is what everyone should see. |
| **OFFLINE · schedule only** | Live data did not reach this phone. The times shown are the timetable, labelled as such. **This is the bug we want.** |
| Anything that shows a delay but the pill says OFFLINE | **Serious** — tell us immediately with a screenshot. The app is built so this cannot happen. |

3. Scroll the stop list. For each upcoming stop the *LIVE / expected* column should either hold a time marked
   **projected**/**reported**, or a dash with the word **timetable**. It must never repeat a schedule minute as
   if it were a real one.
4. Tap **Show full route** then **Show my segment**. The row count must change and a line under the table must
   say how many halts are hidden.
5. Now compare with whatever you normally use (NTES, RailYatri, ixigo, or the website
   [enquiry.indianrail.gov.in/ntes](https://enquiry.indianrail.gov.in/ntes/)) for the **same train at the same
   minute**. Write down the difference in minutes, if any.

## Part 3 — Only if the pill says OFFLINE: hand us the log (1 minute)

1. Go **Back**, tap **Data source**.
2. Tap **Turn on collection** under *Diagnostic log*. The line below should start saying *Collecting · … bytes*.
3. Open the same train again, count to 10, then tap **Refresh** once. It should not feel like it is thinking
   for half a minute: the app now stops waiting at about 7 seconds per source and tells you that it did.
4. Go back to **Data source** → **Save to file** and pick a folder (this is the path that now works: the button
   used to report "the picker was closed" whatever you chose, because it read a value nothing ever assigned).
   **Share** still works too, and either way send the whole file - the useful part is in the middle, not the
   top.
5. One glance at the lines that start **`[TLS]`** tells you what the report will be read for: `handshake ok`
   means the encrypted layer completed and the argument is about the request; `fail:...` on that line means the
   connection never got to the railway at all, and no change to this app can fix that minute. If the line above
   it (`[TCP]`) also failed twice, the network - not the app, not the railway - is the finding.
6. Two more answers, in the same minute, both take 15 seconds:
   - In **Chrome** on that phone, open `https://enquiry.indianrail.gov.in/ntes/`. Does the page load? (Yes +
     "OFFLINE in the app" is the single most useful answer you can give us.)
   - Turn **Wi-Fi off**, use mobile data only, tap **Refresh** once. Does the pill change? **And with Wi-Fi still
     off, does that same NTES page in Chrome still show the running status?** That one pair separates "the
     railway is refusing this app" from "your mobile carrier is refusing the railway", and it is the last
     network variable left open.

### This build specifically (round 7)

The app now asks the railway **twice, by two different routes**: once as an app, once as a browser — and if the
browser is the one that gets through, it keeps using it. So while you do steps 2–4 above, watch for two things
and put them in the same reply:

- Under the pill, a sentence that ends **"(asked from the site's own page, the way its app does)"** — it means
  your phone is the first where we proved which half of the problem is which. **You should not see it yet**: it
  only appears once the railway has actually sent a real answer, and that is the thing being tested.
- **Two things you reported are fixed in this build:** the train screen no longer waits ~20-40 seconds
  (the app now stops waiting at about 7 seconds and tells you it did), and **Save to file** now saves —
  it was reading a value nothing ever set, so it always claimed you had cancelled the picker.
- **One question that could end this in a minute:** does that phone have any app that filters other apps'
  traffic — NetGuard, AdGuard, a VPN, an anti-virus with a firewall, a "data saver"? If yes, turn it off (or
  allow this app) and tap **Refresh**. Here is why that is the right question: on your last log the app's own
  Java requests were hung up on mid-handshake to `enquiry.indianrail.gov.in` **and** to `one.one.one.one`,
  while a request to `api.railradar.in` from the same app got a real `HTTP 401` answer. A railway WAF that
  also refuses Cloudflare's own reachability check, but not a Cloudflare-hosted railway API, would be a very
  strange beast; a filter on the phone that keys on the *client* rather than the app is the shape that fits.
  We are not asking you to uninstall anything — just to try once with the filter paused, then say which one
  it was.
- In the log, lines like `[BROWSER] POST enquiry.indianrail.gov.in via enquiry.indianrail.gov.in -> HTTP 200
  application/json body=4213B in 812ms`. The `HTTP` number and the word after it are what every earlier round
  was missing; send the file so we get them.
- Whether the live column ever fills with a real minute (a time marked **reported** or **projected**) after
  waiting the 40 seconds and pressing **Refresh**. That is the pass condition for this round, and if it happens,
  nothing else about the feed matters — tell us and stop.

The log lines we read first start with `[BROWSER]` and `[PROBE]`; they now carry the server's own answer, so
please send the file rather than a screenshot of the screen.

The log lists web addresses, results and timings. It does **not** contain your name, phone number, location,
accounts, search history or any message content, and it never leaves the phone unless you press Share.
**Turn collection off** when you are done, if you would rather it recorded nothing.

## Reply in these three lines

```
1) Phone / Android version, and Wi-Fi or mobile data:
2) Train number I checked, and the pill I saw (LIVE or OFFLINE):
3) Difference vs NTES in minutes, plus the log if it said OFFLINE:
```

---

### Small things that are also worth a look

- Rotate the phone, and try one-hand reach: **Back**, **Refresh**, **Track** must be tappable without
  stretching.
- Settings → *Display size* at the largest, then open a train: text must not be cut off or overlapping.
- Tap a suggested station you did not type (arrow-key style suggestions must insert a real station code, not
  free text).
- Search the same pair twice — the second search should feel instant, and **Data source** → *Clear live cache*
  must not break anything.
- A train you know runs on a different day: set **−1d** and search. Yesterday's run should be there too.
