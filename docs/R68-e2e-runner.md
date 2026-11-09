# Device-runner image failure · 14 October 2026

Replacement e2e `38622589226` did not get as far as app tests on API 34: GitHub's runner had **zero** installed Android system images, and `sdkmanager` could not install the `google_apis` Android 34 x86_64 image or its x86 fallback. KVM was available; no emulator was booted. This is **untested**, not an app success or failure. Earlier R66 failures on a booted emulator (readiness/search and JaCoCo) remain unresolved until an emulator test completes after the readiness correction.

`tools/ci/emulator_boot.sh` now emits bounded reasons from *both* architecture attempts as GitHub check annotations. The raw job logs/artifacts redirect to blob hosts that have returned `EOF` here, so a generic "could not install image" left the cause unknowable. Never mark a no-device row green; use the new annotations to decide whether retrying later or changing the runner image is justified.
