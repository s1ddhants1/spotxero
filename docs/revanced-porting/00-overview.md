# ReVanced Spotify Patches → SpotXero Porting Overview

## 1. Executive Summary

This documentation suite establishes the technical analysis and concrete engineering blueprint for porting [ReVanced Patches](https://gitlab.com/revanced/revanced-patches) capabilities into [SpotXero](file:///home/s1ddhants1/Projects/spotify/spotxero) using Modern LibXposed (API 102+) and runtime DexKit indexing.

Historically, Spotify client modifications relied on client-side attribute spoofing (e.g. `AccountAttribute` / `ProductStateProto`). As Spotify enforced server-side **`mft_disallow`** ("Mobile Free Tier Disallow") via Keymaster AES-128 cryptographic gating, on-demand playback on mobile free accounts began stalling at ~9.6 seconds.

In response, ReVanced developed **Pull Request #5482** (`feat(Spotify - Spoof client)`). Rather than attempting to patch the obfuscated native C++ player (`liborbit-jni-spotify.so`), PR #5482 introduced a breakthrough loopback mechanism:
1. **The Spotify Connect Bypass**: Spotify's backend permits on-demand pick-and-play streaming for **Spotify Connect devices** (e.g. smart speakers, audio dongles) even on Free-tier accounts.
2. **In-Process Loopback Receiver**: An embedded headless Spotify Connect player (`librespot`) is launched inside the app process as an `AUDIO_DONGLE` on localhost Zeroconf.
3. **Decoupled Audio Pipeline**: When a track is selected, audio streams over Spotify Connect into a custom Android `MediaCodec` Vorbis decoder (`AndroidNativeDecoder`) and `AudioTrack` PCM sink (`AndroidSinkOutput`), playing directly out of the device speaker.
4. **Client Token Spoofing**: In-flight client token requests are redirected to an in-process proxy daemon that acquires Apple iOS tokens, bypassing mobile account constraints.

This guide provides the complete analysis and step-by-step engineering architecture to port and elevate this stack into SpotXero.

---

## 2. Architecture Comparison: ReVanced vs SpotXero

| Architecture Dimension | ReVanced Implementation | SpotXero Porting Target |
|---|---|---|
| **Framework** | Static DEX/Smali transformer (`dexlib2`) + repackaged APK | Modern **LibXposed (API 102+)** runtime hook |
| **Target Resolution** | Static opcode pattern matching at compile time | Dynamic runtime bytecode indexing via **DexKit** (`DexKitResolver`) |
| **Binary Patching** | Crude hex-patching of strings inside `liborbit-jni-spotify.so` | **In-memory hook** at JNI / network boundary (zero binary corruption) |
| **Audio Playback** | Embedded `librespot` + `AndroidNativeDecoder` (`MediaCodec`) | Native LibXposed service hosting `librespot-player:thin` |
| **Volume Control** | Smali rewrite of `MediaSession.setPlaybackToRemote` | Dynamic hook via `ForceLocalPlaybackHook` |
| **App Integrity** | Requires disabling signature checks and Play Integrity | **Original untouched APK**: unmodified signatures & Google Play compatibility |

---

## 3. Engineering Blueprint & Porting Roadmap

Translating ReVanced's static patch into SpotXero's dynamic runtime architecture significantly improves stability and clean design:
- **No Binary Tampering**: Instead of hex-patching `liborbit-jni-spotify.so` across multiple ABIs (which breaks checksums and crashes on unsupported architectures), SpotXero hooks `ApplicationScopeConfiguration` and the Java HTTP/JNI transport in memory.
- **Dynamic Method Resolution**: Instead of hardcoding minified smali class names that break on every Spotify release, SpotXero uses `DexKitResolver` to find `MediaSession` and connectivity entry points dynamically.
- **User-Controlled Toggles**: Features are exposed through SpotXero's Jetpack Compose Material 3 UI with instant hot-reloading.

---

## 4. Documentation Suite Index

| Document | Title | Description |
|---|---|---|
| [01](01-spoof-client-pr-5482.md) | **Deep Dive: ReVanced PR #5482 (`Spoof Client`)** | Complete architectural breakdown of PR #5482: binary hex-patching, `NanoHTTPD` daemon, `ClientTokenService`, embedded `librespot` player, `AndroidNativeDecoder`, and the Spotify Connect exemption mechanics. |
| [02](02-tablet-tier-and-mft-disallow.md) | **Tablet Tier Analysis & `mft_disallow` Keymaster Mechanics** | Breakdown of `STREAMING_RULE_TABLET_FREE`, why client-side `tablet-free` spoofing fails against Keymaster, and why the Spotify Connect loopback succeeds where tablet spoofing fails. |
| [03](03-revanced-patch-catalogue.md) | **ReVanced Spotify Patch Catalogue & Porting Matrix** | Complete survey of all other ReVanced Spotify patches (`UnlockPremium`, `CustomTheme`, `HideCreateButton`, `SanitizeSharingLinks`, `ForceLocalPlayback`), evaluated for SpotXero. |
| [04](04-spotxero-porting-blueprint.md) | **SpotXero Engineering Specification: Connect Loopback & Spoof Client** | Step-by-step technical implementation blueprint for porting the Connect Loopback Engine, `librespot` bridging, `ClientTokenHook`, and `ForceLocalPlaybackHook` into SpotXero. |

---

## 5. Repository References & Historical Context

- **Repository Location**: `/home/s1ddhants1/Projects/spotify/revanced-patches`
- **Active Branches**:
  - `fix/spotify/spoof-client` (`b19a8dbea7730914054874be8ab216399ce16c7d`): Head of GitLab Merge Request 5482 containing the complete `SpoofClientPatch`, `librespot` integration, and native binary hex-patching.
  - `dev` (`12487bc071590ab5d844049ba08c59c534e3dcf9`): Tracking `origin/dev` from `https://gitlab.com/revanced/revanced-patches.git`.
- **Target App Version**: Spotify Android `9.1.94.1929` (`versionCode=147600575`).

### Historical Timeline: PR #5482 DMCA Force-Push & Branch Deletion
On September 12, 2025, ReVanced received a DMCA takedown notice targeting client spoofing and Spotify Connect modifications:
1. Lead developer `oSumAtrIX` closed PR #5482.
2. The `dev` branch was force-pushed from `730b2658ac2966fd3f06286a44536e12356f3de1` to `7f6093ee66980ccea3715affbb21a5ed5bd65d0f` (`v5.36.0-dev.1`) to purge the PR commits from repository history.
3. The `fix/spotify/spoof-client` branch was deleted from GitHub.
4. Commit `730b265` was rebased onto `dev` as commit `3bdb8dbce04c257a56465e7b4a27e8c235293bbb`.
5. The full PR #5482 codebase was preserved and fetched into our workspace from the GitLab mirror via `refs/merge-requests/5482/head`.
