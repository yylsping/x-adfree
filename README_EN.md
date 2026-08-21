# X AdFree

[简体中文](README.md)

An LSPosed ad-removal module for the X Android app, built with libxposed Modern API 102 and DexKit runtime resolution.

## Features

- Removes ads from the Home timeline (For You / Following).
- Removes ads from post detail pages and replies.
- Identifies ads through the target app's data models (URT promoted metadata, entryId prefixes, and the app's own ad predicate) with tri-state verdicts; anything uncertain is passed through, minimizing the chance of normal posts being dropped (not an absolute guarantee).
- Filters at a single convergence point in the URT data layer, which benefits every timeline-consuming surface at once, independent of UI layout.
- Locates target methods at runtime with DexKit using multi-feature fingerprints (string usage, method signature, type shape) and caches results per installed target identity; subsequent launches hit the cache without re-resolving.
- Built-in runtime witness: the first real invocation after hook installation must match the URT data shape, otherwise the hook unhooks itself and invalidates its cache entry, so a mis-fingerprinted candidate can never disturb unrelated flows.
- Does not modify the X APK, intercept network requests, run background services or polling, acquire wake locks, or use Frida. The only bundled native library is DexKit's own `libdexkit.so`, used only during resolution.

## Compatibility

| Component | Requirement |
| --- | --- |
| Target app | Dynamically adapted to X 12.x minor versions; currently verified on 12.17.0-release.0 (12.3.1 is retained only as a historical compatibility seed, not re-tested on the 2.0.x architecture) |
| Android | Android 9.0 (API 28) or later |
| Framework | Official LSPosed with libxposed Modern API 102 support |
| Module version | 2.0.2 (versionCode 21) |

The module resolves its targets through feature fingerprints, shape verification, and a runtime witness instead of hard-coded class tables. **Engineering definition of "minor-version agnostic"**: ordinary X minor upgrades need no per-version hook table; while business semantics and call structure remain recognizable, runtime DexKit + Verifier + Witness relocate targets automatically; a generational redesign may require re-analysis and a Resolver update. Zero-maintenance compatibility with arbitrary future versions is not promised. If a future X refactor invalidates every fingerprint, the module fails open — X keeps working unfiltered — until an updated module release.

X releases after 11.82.0 include `libpairipcore.so`. Before using this module, select X (`com.twitter.android`) under **LSPosed Settings → Restore inline hooks**. Without this option, X may terminate during a cold start.

## How it works (short version)

1. When the X main process starts, the module records the installed target identity (package, APK and split sizes, signing certificate hash, versionCode) as a stable token; versionCode is a cache-invalidation factor only, never a behavior branch.
2. On first run, DexKit discovers emit candidates through seven entries (five high-entropy business strings, a structural entry, and a historical seed) and scores them on orthogonal features; ambiguous or weak tops receive up to five read-only probes and are promoted only after at least two real invocations with a healthy shaped-element ratio. The timeline model interface and the app's own ad predicate are resolved alongside.
3. The app's own boolean ad predicate only contributes a weight below the removal threshold until a runtime semantic witness correlates it with independent evidence on real items — a single mis-resolved helper can never delete normal content, and a contradicting helper is disabled outright.
4. Results are cached per identity (atomic replace, at most 5 identities, LRU eviction); on every later launch each cached target is re-verified with the exact same rules as a fresh resolution before reuse.
5. After installation the hook is validated by the runtime witness against the first real payload; mismatches truly unhook the interceptor and invalidate that cache entry. Ad detection votes with a tri-state verdict and only confirmed ads are removed. Filtering replaces ArrayList outputs only; unknown List implementations pass through unfiltered (fail-open).
6. The bootstrap state machine is serialized on one worker thread; READY/DEGRADED are frozen terminal states. The 20s bootstrap watchdog covers the resolve phase only, and the probe window has its own independent 30s deadline.

See [docs/analysis-12.17.0.md](docs/analysis-12.17.0.md) for the full analysis (fingerprint matrix, state machine, discovery matrix).

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
