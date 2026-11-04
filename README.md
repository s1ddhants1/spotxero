<div align="center">
<img src="art/spotxero_logo_dark.svg" width="100" alt="SpotXero Logo"/>
<h1>SpotXero</h1>

<p>An Xposed module for Spotify on Android </p>

<a href="https://github.com/s1ddhants1/spotxero/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/s1ddhants1/spotxero/build.yml?style=for-the-badge&logo=githubactions&logoColor=white&label=CI" alt="CI Status" /></a>
<a href="https://github.com/s1ddhants1/spotxero/releases"><img src="https://img.shields.io/github/v/release/s1ddhants1/spotxero?style=for-the-badge&color=1ED760&logo=android&label=Release" alt="Release Version" /></a>
<a href="https://github.com/s1ddhants1/spotxero/releases/tag/nightly"><img src="https://img.shields.io/badge/Nightly-Pre--release-1DB954?style=for-the-badge&logo=github" alt="Nightly Build" /></a>
<a href="https://github.com/s1ddhants1/spotxero/blob/master/LICENSE"><img src="https://img.shields.io/badge/License-MIT-10b981?style=for-the-badge" alt="License" /></a>
<img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026--37)-f59e0b?style=for-the-badge&logo=android" alt="Android API Support" />
<img src="https://img.shields.io/badge/Framework-LSPosed%20%7C%20LibXposed%20%7C%20LSPatch-8b5cf6?style=for-the-badge" alt="LSPosed / LibXposed / LSPatch" />
<img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-06b6d4?style=for-the-badge&logo=jetpackcompose" alt="Jetpack Compose Material 3" />

</div>

## Compatibility

| Requirement           | Supported Details                                                                                                                    |
| :-------------------- | :----------------------------------------------------------------------------------------------------------------------------------- |
| **Target App**        | [Spotify: Music and Podcasts](https://play.google.com/store/apps/details?id=com.spotify.music) (`com.spotify.music`)                 |
| **Android Version**   | Android 8.0 (API 26) through Android 16+ (API 37)                                                                                    |
| **Rooted Frameworks** | [LSPosed](https://github.com/LSPosed/LSPosed) (v2.0.0+), [Vector](https://github.com/JingMatrix/Vector), or LibXposed (API 101/102+) |
| **Rootless Setup**    | [LSPatch](https://github.com/JingMatrix/LSPatch) (Integrated Mode and Manager Mode)                                                  |
| **Architecture**      | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` (compatible with 16 KB page-size kernels)                                                |

---

## Installation & Setup

### 1. Download SpotXero

- **Obtainium (Recommended)**: Automatically track and install updates by adding SpotXero to [Obtainium](https://github.com/ImranR98/Obtainium):

<p>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https%3A%2F%2Fgithub.com%2Fs1ddhants1%2Fspotxero">
    <img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="80">
  </a>
</p>

Or enter the repository URL manually:

```text
https://github.com/s1ddhants1/spotxero
```

- **Manual Download**: Grab the latest APK from [Releases](https://github.com/s1ddhants1/spotxero/releases) or bleeding-edge [Nightly Builds](https://github.com/s1ddhants1/spotxero/releases/tag/nightly).

### 2. Activation

- **Rooted (LSPosed / Vector)**:
  1. Open LSPosed / Vector Manager.
  2. Enable the **SpotXero** module and add **Spotify** (`com.spotify.music`) to its scope.
  3. Force stop Spotify or reboot your device.
  4. Open SpotXero to use the Compose companion UI. Spotify behavior hooks are not included yet.

- **Non-Rooted (LSPatch)**:
  1. Patch the Spotify APK with [LSPatch](https://github.com/JingMatrix/LSPatch) in **Integrated Mode**, selecting the SpotXero APK as the module.
  2. Install the patched Spotify APK.
  3. The generic LibXposed entry loads in the patched app; Spotify behavior hooks are not included yet.

---

## Building from Source

```bash
git clone https://github.com/s1ddhants1/spotxero.git
cd spotxero

# Build APKs
./gradlew assembleRelease
```

Compiled APKs are output to `app/build/outputs/apk/release/` and `app/build/outputs/apk/debug/`.

## Credits & License

- **Author**: [s1ddhants1](https://github.com/s1ddhants1)
- **Frameworks**: [LibXposed](https://github.com/libxposed), [LSPosed](https://github.com/LSPosed/LSPosed), [LSPatch](https://github.com/JingMatrix/LSPatch)
- **License**: [MIT License](LICENSE)

**Disclaimer**: SpotXero is an independent, open-source project intended strictly for educational study and security research. "Spotify" is a registered trademark of Spotify AB. SpotXero is not affiliated with, endorsed by, or sponsored by Spotify AB.
