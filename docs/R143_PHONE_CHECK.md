# R143 · follow-up on the 20689 screenshots

The rolling [debug test APK](https://github.com/tarungittarun/Checktrain02/releases/download/latest-apk/app-debug.apk) is R143 **only when** its release description names versionCode **72**, versionName **1.0.71-r143**. Earlier rolling bytes are not evidence of this fix.

1. Train 20689, 20 October: after the source has answered, leave the route open for **more than 30 seconds** and Refresh. The Bhadravati report must have **one** locomotive: a departure cue just beyond Bhadravati, not another train on the Bhadravati dot. If no source departure is reported, the one marker belongs **at** the reported halt. Neither marker is a measured present position or live distance; the clock and age remain dated by the last source event.
2. Toggle More/Less, then Refresh in both states. The 40dp source dropdown and Auto switch must be fully inside the blue card, not cut across the bottom. The first selected-source check establishes the card size; later Refresh and background comparisons cannot grow or shrink it. A longer source explanation scrolls inside the evidence area rather than pushing the controls down. Only More/Less changes the card height.
3. The source chooser remains available even if both sources have no response or the phone is offline. The normal summary has no invisible 36dp warning row between the header and the provenance. When a selected source fails, an actual warning shares the evidence area of the **expanded** card, or shows CHECK FAILED in the compact header. It must not silently turn a page-check clock into train movement or obscure the delay/source controls.
4. Confirm the timetable, stations and ACTUAL headings remain visible and the route scroll is usable. Verify on the owner's phone: emulator pass is not phone-app telemetry parity.

PR #3 stays open until the owner confirms the APK works on their device.
