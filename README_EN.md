# X AdFree

[简体中文](README.md)

An LSPosed ad-removal module for the X Android app, built with libxposed Modern API 102.

## Features

- Removes ads from the Home timeline.
- Removes ads from post detail pages and replies.
- Identifies and removes ads through the target app's data models.
- Does not modify the X APK, intercept network requests, run background services or polling, acquire wake locks, bundle native libraries, or use Frida.

## Compatibility

| Component | Requirement |
| --- | --- |
| Target app | X 12.3.1-release.0 (versionCode 312031000) |
| Android | Android 9.0 (API 28) or later |
| Framework | Official LSPosed with libxposed Modern API 102 support |
| Module version | 1.5.0 (versionCode 17) |

The module depends on internal class names, method signatures, and resource IDs from the target app. Compatibility with other X versions is not guaranteed.

X releases after 11.82.0 include `libpairipcore.so`. Before using this module, select X (`com.twitter.android`) under **LSPosed Settings → Restore inline hooks**. Without this option, X may terminate during a cold start.

## Installation

1. Download and install the APK from GitHub Releases.
2. Confirm that X is selected under the LSPosed inline-hook restoration setting described above.
3. Enable X AdFree in LSPosed. Its static scope contains only X.
4. Force-stop X and open it again.

The module has no settings screen. Its enabled state, scope, and inline-hook restoration option are managed through the LSPosed GUI.

## Building

The project requires JDK 17 and Android SDK 35. Gradle must be able to download `io.github.libxposed:api:102.0.0` from Maven Central.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

The maintainer's production builds use a project-specific release key stored locally in:

- `signing-private/release.p12`
- `signing-private/signing.properties`

Both files are excluded by `.gitignore` and are never committed to GitHub. When the local signing configuration exists, `assembleRelease` produces a signed release APK. Repository clones without the private key can still use `assembleDebug` for test builds.

The release key is required for Android in-place updates. Keep an encrypted backup of the complete `signing-private/` directory, and never delete, regenerate, or commit it. Earlier APKs used a test signature, so users must uninstall the previous build once when switching to this release key. Later production releases can then be installed as normal updates.

## License

This project is licensed under the [MIT License](LICENSE).

## Disclaimer

This project is intended for learning, research, and personal-device use only. It is not affiliated with or endorsed by X Corp., Twitter, or the LSPosed project. Ensure that your use complies with applicable law and relevant terms of service.
