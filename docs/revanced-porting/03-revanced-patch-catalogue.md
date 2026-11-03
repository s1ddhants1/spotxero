# ReVanced Spotify Patch Catalogue & Porting Matrix

## 1. Executive Summary

This document surveys the complete set of Spotify patches available in the [ReVanced Patches repository](file:///home/s1ddhants1/Projects/spotify/revanced-patches) (analyzed across branches `dev` and `fix/spotify/spoof-client`). Each patch is linked directly to its implementation files in the local clone and evaluated for porting into SpotXero's modern LibXposed (API 102+) architecture.

---

## 2. Comprehensive Patch Catalogue

### 2.1 `UnlockPremiumPatch` (`app.revanced.patches.spotify.misc`)
- **Category**: Core Feature / Entitlement Spoof
- **Source Files**:
  - Patcher: [`UnlockPremiumPatch.kt`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/patches/src/main/kotlin/app/revanced/patches/spotify/misc/UnlockPremiumPatch.kt)
  - Extension: [`UnlockPremiumPatch.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/UnlockPremiumPatch.java)
  - Fingerprints: [`Fingerprints.kt`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/patches/src/main/kotlin/app/revanced/patches/spotify/misc/Fingerprints.kt)
- **Mechanisms Implemented**:
  1. **Attribute Map Overrides (`overrideAttributes`)**:
     Intercepts `ProductStateProto.getMap()` and injects:
     - `ads` → `false`
     - `player-license` / `player-license-v2` → `"premium"`
     - `shuffle` → `false`
     - `on-demand` → `true`
     - `streaming` → `true`
     - `pick-and-shuffle` → `false`
     - `streaming-rules` → `""`
     - `nft-disabled` → `"1"`
     - `type` → `"premium"`
     - `tablet-free` → `false` (or `true` on tablets)
     - `can_use_superbird` → `true`
  2. **Station URI De-stationing (`removeStationString`)**:
     Intercepts URI/URL strings passed to context deserialization to strip `spotify:station:` prefixes, preventing the client from redirecting playlist taps into algorithmic radio stations.
  3. **Section Ad Filtering (`removeHomeSections`, `removeBrowseSections`)**:
     Publicizes `featureTypeCase_` and `sectionTypeCase_` on Protobuf `Section` objects. Iterates through the list of home and browse sections and removes:
     - `Section.VIDEO_BRAND_AD_FIELD_NUMBER`
     - `Section.IMAGE_BRAND_AD_FIELD_NUMBER`
     - `Section.BRAND_ADS_FIELD_NUMBER`
  4. **Context Menu Upsell Removal (`filterContextMenuItems`)**:
     Filters context menu items matching string resources `context_menu_remove_ads` ("Listen to music ad-free").
  5. **Pendragon Popup Ad Suppression**:
     Intercepts `PendragonJsonFetchMessageRequest` and `PendragonProtoFetchMessageListRequest` and replaces the RxJava `Single` with `Single.just(onErrorReturnValue)`.

---

### 2.2 `CustomThemePatch` (`app.revanced.patches.spotify.layout.theme`)
- **Category**: UI / Customization
- **Source Files**:
  - Patcher: [`CustomThemePatch.kt`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/patches/src/main/kotlin/app/revanced/patches/spotify/layout/theme/CustomThemePatch.kt)
  - Extension: [`CustomThemePatch.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/layout/theme/CustomThemePatch.java)
  - Fingerprints: [`Fingerprints.kt`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/patches/src/main/kotlin/app/revanced/patches/spotify/layout/theme/Fingerprints.kt)
- **Mechanisms Implemented**:
  - Hooks Spotify's internal color palette resolvers (e.g., `backgroundColor`, `surfaceColor`).
  - Replaces dark gray background tokens with pitch-black (`#000000`) for AMOLED energy savings.
  - Exposes color customization options for primary accents, nav-bars, and status-bar scrims.

---

### 2.3 `HideCreateButtonPatch` (`app.revanced.patches.spotify.layout.hide.createbutton`)
- **Category**: UI Cleanup
- **Source Files**:
  - Patcher: [`HideCreateButtonPatch.kt`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/patches/src/main/kotlin/app/revanced/patches/spotify/layout/hide/createbutton/HideCreateButtonPatch.kt)
  - Extension: [`HideCreateButtonPatch.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/layout/hide/createbutton/HideCreateButtonPatch.java)
  - Fingerprints: [`Fingerprints.kt`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/patches/src/main/kotlin/app/revanced/patches/spotify/layout/hide/createbutton/Fingerprints.kt)
- **Mechanisms Implemented**:
  - Hooks the navigation bar model builder.
  - Strips the '+' Create button from the bottom navigation bar to restore the cleaner legacy tab layout (Home, Search, Your Library).

---

### 2.4 `SanitizeSharingLinksPatch` (`app.revanced.patches.spotify.misc.privacy`)
- **Category**: Privacy
- **Source Files**:
  - Patcher: [`SanitizeSharingLinksPatch.kt`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/patches/src/main/kotlin/app/revanced/patches/spotify/misc/privacy/SanitizeSharingLinksPatch.kt)
  - Extension: [`SanitizeSharingLinksPatch.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/privacy/SanitizeSharingLinksPatch.java)
  - Fingerprints: [`Fingerprints.kt`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/patches/src/main/kotlin/app/revanced/patches/spotify/misc/privacy/Fingerprints.kt)
- **Mechanisms Implemented**:
  - Hooks URL sharing intent builders.
  - Strips telemetry and user tracking parameters (specifically `?si=...` tracking tokens) from generated share links.

---

### 2.5 `ForceLocalPlaybackPatch` (`app.revanced.patches.spotify.misc.volume`)
- **Category**: Audio & Volume Routing
- **Source Files**:
  - Patcher: [`ForceLocalPlaybackPatch.kt`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/patches/src/main/kotlin/app/revanced/patches/spotify/misc/volume/ForceLocalPlaybackPatch.kt)
  - Fingerprints: [`Fingerprints.kt`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/patches/src/main/kotlin/app/revanced/patches/spotify/misc/volume/Fingerprints.kt)
- **Mechanisms Implemented**:
  - Hooks `MediaSession.setPlaybackToRemote()` calls within Spotify's playback routing.
  - Rewrites the call to `MediaSession.setPlaybackToLocal(AudioAttributes)` with `USAGE_MEDIA`.
  - Ensures hardware volume rocker buttons control device media volume when casting to local/remote endpoints.

---

### 2.6 `SpoofClientPatch` (`app.revanced.patches.spotify.misc.fix`)
- **Category**: Spotify Connect Loopback & Client Authentication
- **Source Files**:
  - Patcher: [`SpoofClientPatch.kt`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/patches/src/main/kotlin/app/revanced/patches/spotify/misc/fix/SpoofClientPatch.kt)
  - Extension Service: [`SpoofClientPatch.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/SpoofClientPatch.java)
  - Token Service: [`ClientTokenService.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/ClientTokenService.java)
  - Zeroconf Server: [`AndroidZeroconfServer.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/AndroidZeroconfServer.java)
  - Decoder: [`AndroidNativeDecoder.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/AndroidNativeDecoder.java)
  - Audio Sink: [`AndroidSinkOutput.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/AndroidSinkOutput.java)
  - Loopback Proxy: [`RequestListener.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/RequestListener.java)
  - Build Config: [`build.gradle.kts`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/build.gradle.kts)
- **Mechanisms Implemented**:
  - In-process Spotify Connect receiver (`AUDIO_DONGLE` named `"ReVanced"`) running on `127.0.0.1` Zeroconf.
  - Android `MediaCodec` hardware Vorbis decoder and direct `AudioTrack` PCM output.
  - iOS client token forgery via loopback proxy.

---

### 2.7 `ChangeLyricsProviderPatch` (`app.revanced.patches.spotify.misc.lyrics`)
- **Category**: Third-Party Integration
- **Source Files**:
  - Patcher: [`ChangeLyricsProviderPatch.kt`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/patches/src/main/kotlin/app/revanced/patches/spotify/misc/lyrics/ChangeLyricsProviderPatch.kt)
- **Mechanisms Implemented**:
  - Intercepts Spotify's internal lyrics fetch requests.
  - Fetches synchronized lyrics from external third-party APIs (Genius, Musixmatch, or Netease) and maps them into Spotify's native lyrics UI component.

---

### 2.8 `FixFacebookLoginPatch` & `SpoofSignaturePatch` (`app.revanced.patches.spotify.misc.fix`)
- **Category**: APK Repacking Workarounds
- **Source Files**:
  - Patcher: [`FixFacebookLoginPatch.kt`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/patches/src/main/kotlin/app/revanced/patches/spotify/misc/fix/login/FixFacebookLoginPatch.kt)
  - Patcher: [`SpoofSignaturePatch.kt`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/patches/src/main/kotlin/app/revanced/patches/spotify/misc/fix/SpoofSignaturePatch.kt)
- **Mechanisms Implemented**:
  - Replaces `PackageInfo.signatures` returned to Facebook SDK with Spotify's original official release key hash.

---

## 3. Porting Feasibility & SpotXero Roadmap Matrix

| Patch Name | SpotXero Status | Porting Strategy |
|---|---|---|
| **`UnlockPremium` (Attributes)** | ✅ **Implemented** | Covered by [`ProductStateHook.kt`](file:///home/s1ddhants1/Projects/spotify/spotxero/app/src/main/kotlin/app/spotxero/hooks/ProductStateHook.kt). |
| **`UnlockPremium` (De-stationing)** | 🟡 **Ready to Port** | Implement `StationDestationHook` to strip `spotify:station:` context prefixes. |
| **`UnlockPremium` (Section Ads)** | ✅ **Implemented** | Covered by [`BrandAdsHook.kt`](file:///home/s1ddhants1/Projects/spotify/spotxero/app/src/main/kotlin/app/spotxero/hooks/BrandAdsHook.kt) and [`AdQueueFilterHook.kt`](file:///home/s1ddhants1/Projects/spotify/spotxero/app/src/main/kotlin/app/spotxero/hooks/AdQueueFilterHook.kt). |
| **`CustomTheme` (AMOLED Black)** | ✅ **Implemented** | Handled by [`ThemeHook.kt`](file:///home/s1ddhants1/Projects/spotify/spotxero/app/src/main/kotlin/app/spotxero/hooks/ThemeHook.kt). |
| **`HideCreateButton`** | 🟡 **Ready to Port** | Can be resolved via DexKit to filter bottom navigation items. |
| **`SanitizeSharingLinks`** | ✅ **Implemented** | Covered by [`CleanSharingLinksHook.kt`](file:///home/s1ddhants1/Projects/spotify/spotxero/app/src/main/kotlin/app/spotxero/hooks/CleanSharingLinksHook.kt). |
| **`ForceLocalPlayback`** | 🟡 **High Priority Port** | Resolve `setPlaybackToRemote` via DexKit and redirect to `setPlaybackToLocal(AudioAttributes)`. |
| **`SpoofClient` (Connect Loopback)** | 🚀 **Porting Blueprint Defined** | Full blueprint specified in [04-spotxero-porting-blueprint.md](04-spotxero-porting-blueprint.md) to enable on-demand pick-and-play via loopback Connect. |
| **`FixFacebookLogin` / `SpoofSignature`** | ⚪ **Not Needed** | SpotXero runs as an Xposed module on the original official APK; signature tampering does not exist. |
