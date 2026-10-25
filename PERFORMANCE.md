# Native Android verification

Measured on 25 October 2026 using the optimized Kotlin / Compose release APK (R8 and resource shrinking enabled).

| Check | Result |
| --- | --- |
| APK size | 1,808,234 bytes, including all four supported ABIs |
| Native domain tests | 9 passed, including a 10,000-level tree |
| Android 16 instrumented tests | 3 passed: 5,000-topic lazy list and navigation; forms and backup; API sync and conflict recovery |
| Android release lint | 0 errors; 18 advisory warnings |
| Upgrade | Installed over the debug build; the 5,000-topic database remained intact |
| Release startup | 1,248 ms cold activity launch in the hardware-rendered emulator |
| Scrolling | 241 frames; median 17 ms, p95 20 ms, p99 31 ms; 8 janky frames (3.32%) |
| Memory after scrolling | Approximately 60 MB total PSS |

Environment: isolated Android 16 x86-64 emulator, four virtual CPU cores, 2 GB RAM, 1344 × 2992 display, hardware graphics on an NVIDIA RTX 3050 laptop GPU. Scrolling used fifteen 250 ms vertical gestures across 5,000 top-level topics; Android `dumpsys gfxinfo` and `dumpsys meminfo` supplied the measurements. These are one-run observations, not device-independent performance guarantees.

A software-rendered SwiftShader run was substantially slower (48 ms median, 58.58% janky frames). Hardware mode changed only the emulator renderer, using the same APK and database. This illustrates why software-emulator frame rates cannot be treated as phone results.

**The physical Poco X6 running Android 16 has not been tested.** Evaluate the release APK on that phone before making claims about its frame rate, startup or battery use. The APK contains no React Native runtime, Hermes bundle or WebView.
