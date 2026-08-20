# X AdFree

[简体中文](README.md)

An LSPosed ad-removal module for the X Android app, built with libxposed Modern API 102 and DexKit runtime resolution.

## Features

- Removes ads from the Home timeline (For You / Following).
- Removes ads from post detail pages and replies.
- Identifies ads through the target app's data models (URT promoted metadata, entryId prefixes, and the app's own ad predicate) with tri-state verdicts; anything uncertain is passed through, so normal posts are never dropped.
- Filters at a single convergence point in the URT data layer, which benefits every timeline-consuming surface at once, independent of UI layout.
- Locates target methods at runtime with DexKit using multi-feature fingerprints (string usage, method signature, type shape) and caches results per installed target identity; subsequent launches hit the cache without re-resolving.
- Built-in runtime witness: the first real invocation after hook installation must match the URT data shape, otherwise the hook unhooks itself and invalidates its cache entry, so a mis-fingerprinted candidate can never disturb unrelated flows.
- Does not modify the X APK, intercept network requests, run background services or polling, acquire wake locks, or use Frida. The only bundled native library is DexKit's own `libdexkit.so`, used only during resolution.

## Compatibility

| Component | Requirement |
| --- | --- |
| Target app | X 12.x series; verified on 12.3.1 and 12.17.0-release.0. Not bound to a single version — new minor versions are adapted at runtime |
| Android | Android 9.0 (API 28) or later |
| Framework | Official LSPosed with libxposed Modern API 102 support |
| Module version | 2.0.0 (versionCode 19) |

The module resolves its targets through feature fingerprints, shape verification, and a runtime witness instead of hard-coded class tables. If a future X refactor invalidates the fingerprints, the module fails open — X keeps working unfiltered — until an updated module release.

X releases after 11.82.0 include `libpairipcore.so`. Before using this module, select X (`com.twitter.android`) under **LSPosed Settings → Restore inline hooks**. Without this option, X may terminate during a cold start.

## How it works (short version)

1. When the X main process starts, the module records the installed target identity (package, APK and split sizes, signing certificate hash) as a stable token.
2. On first run, DexKit resolves the URT emit hook point, the timeline model interface, and the app's ad predicate through a four-tier fingerprint ladder (strong/weak/name/fallback); candidates are scored on orthogonal features, and ambiguous ones are promoted only after read-only probes observe real traffic.
3. Results are cached per identity (atomic writes, at most 5 targets, LRU eviction); on every later launch each cached target is re-verified as loadable and correctly shaped before reuse.
4. After installation the hook is validated by the runtime witness against the first real payload; ad detection (promoted metadata / entryId prefix / app predicate) votes with a tri-state verdict and only confirmed ads are removed.

See [docs/analysis-12.17.0.md](docs/analysis-12.17.0.md) for the full analysis.

## Installation

1. Download and install the APK from GitHub Releases.
2. Confirm that X is selected under the LSPosed inline-hook restoration setting described above.
3. Enable X AdFree in LSPosed. Its static scope contains only X.
4. Force-stop X and open it again. The first launch performs a one-time DexKit resolution (about one second); later launches hit the cache and take effect within tens of milliseconds.

The module has no settings screen. Its enabled state, scope, and inline-hook restoration option are managed through the LSPosed GUI.

## Building

The project requires JDK 17 and Android SDK 35. Gradle must be able to download `io.github.libxposed:api:102.0.0` and `org.luckypray:dexkit:2.0.6` from Maven Central.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Test APKs are written to `app/build/outputs/apk/debug/`. Signed builds for general users are available from GitHub Releases.

## License

This project is licensed under the [MIT License](LICENSE).

## Disclaimer

This project is intended for learning, research, and personal-device use only. It is not affiliated with or endorsed by X Corp., Twitter, or the LSPosed project. Ensure that your use complies with applicable law and relevant terms of service.
