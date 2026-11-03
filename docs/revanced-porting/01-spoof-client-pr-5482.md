# Deep Dive: ReVanced PR #5482 (`Spoof Client`)

## 1. Executive Summary

In mid-2025, Spotify introduced strict server-side entitlement enforcement for on-demand playback on mobile devices (`mft_disallow`), rendering client-side UI and product-state spoofing insufficient. Tracks selected on-demand by Free-tier users began stalling consistently after ~9.6 seconds because the native C++ engine (`liborbit-jni-spotify.so`) was denied AES-128 decryption keys from Spotify's Keymaster service.

To bypass this barrier, ReVanced developers `oSumAtrIX` and `drobotk` submitted **Pull Request #5482** (titled initially `feat(Spotify - Spoof client): Use background webview to play audio there`, later refined to `use ios login + librespot player`).

### The Spotify Connect Loophole
The fundamental breakthrough in PR #5482 stems from Spotify's multi-device business rules:
- **Mobile Free Tier**: Restricted to shuffle-only; on-demand playback is gated by Keymaster (`mft_disallow` = `"CANT_PLAY_ON_DEMAND"`).
- **Spotify Connect Targets (Speakers & Dongles)**: Spotify's backend **permits full on-demand pick-and-play** on smart speakers, desktop apps, and audio receivers, even for Free accounts!

PR #5482 exploited this asymmetry by launching an **in-process headless Spotify Connect receiver** (`xyz.gianlu.librespot`) directly inside Spotify's Android process, registering as an `AUDIO_DONGLE` on localhost Network Service Discovery (NSD/Zeroconf). When the user selects any track, playback routes to this internal receiver, which streams the audio, decodes it via hardware `MediaCodec`, and outputs it through Android's `AudioTrack`.

---

## 2. Commit Progression & Evolution

| Commit | Author | Message | Architectural Milestone |
|---|---|---|---|
| `6552b96` | `oSumAtrIX` | `use webview login + librespot player` | Initial attempt: spawned a hidden Android `WebView` to authenticate against Spotify Web Player, extracted web session tokens, and passed them to `librespot`. |
| `ed15482` | `drobotk` / `oSumAtrIX` | `use ios login + librespot player` | Shifted from heavy WebView scraping to direct iOS token forgery via protobuf requests to `https://clienttoken.spotify.com/v1/clienttoken`. |
| `45eff36` | `LisoUseInAIKyrios` | `feat(Spotify): Bump librespot to 1.6.6-SNAPSHOT + set playback to local (#5668)` | Upgraded `librespot-player` to `1.6.6-SNAPSHOT:thin` and added `ForceLocalPlaybackPatch` to preserve phone volume controls. |
| `ff8f3f2` | `LisoUseInAIKyrios` | `Update extensions/spotify/proguard-rules.pro` | Added ProGuard keep rules for relocated OkHttp and shaded librespot dependencies. |
| `b19a8db` | `oSumAtrIX` | `Apply suggestions from code review` | Final code-review polish across `ClientTokenService`, `AndroidZeroconfServer`, and smali fingerprints. |

### 2.1 The DMCA Takedown, Force-Push, and Branch Deletion
On September 12, 2025, PR #5482 was closed following a DMCA takedown notice:
1. **PR Closure**: Lead developer `oSumAtrIX` officially closed PR #5482:
   > *"Due to a DMCA takedown notice, the PR was closed, as a force push was needed to comply. If applicable, the PR may be reopened."*
2. **The `dev` Force-Push (`730b265` → `7f6093e`)**:
   - `oSumAtrIX` force-pushed the `dev` branch from commit `730b2658ac2966fd3f06286a44536e12356f3de1` to `7f6093ee66980ccea3715affbb21a5ed5bd65d0f` (tag `v5.36.0-dev.1`).
   - Commit `730b265` was rebased onto `dev` as commit `3bdb8dbce04c257a56465e7b4a27e8c235293bbb`.
3. **Branch Deletion**:
   - `oSumAtrIX` deleted the `fix/spotify/spoof-client` branch from GitHub.
   - The complete commit lineage and code were preserved on the GitLab mirror via `refs/merge-requests/5482/head` (`b19a8dbea7730914054874be8ab216399ce16c7d`).

---

## 3. Detailed Component Architecture

```mermaid
flowchart TD
    subgraph Spotify Android Process (Modified)
        UI["Spotify UI (Track Tapped)"] -->|Spotify Connect Handshake| LoopbackReceiver["AndroidZeroconfServer (AUDIO_DONGLE)"]
        
        subgraph Librespot Audio Bridge
            LoopbackReceiver --> Session["librespot Session (127.0.0.1)"]
            Session --> Player["librespot.player.Player"]
            Player -->|Vorbis Encoded Stream| Decoder["AndroidNativeDecoder (MediaCodec)"]
            Decoder -->|Raw PCM 16-bit 44.1kHz| Sink["AndroidSinkOutput (AudioTrack)"]
            Sink --> AudioHAL["Device Audio HAL / Speaker"]
        end
        
        subgraph Client Token Loopback Proxy
            NativeOrbit["liborbit-jni-spotify.so"] -->|Redirected /v1/clienttoken| NanoHTTPD["NanoHTTPD (Port 4345)"]
            NanoHTTPD --> TokenService["ClientTokenService"]
            TokenService -->|Outbound HTTPS (iOS Fingerprint)| SpotifyAuth["Spotify Client Token API"]
            SpotifyAuth -->|Signed iOS Token| TokenService
            TokenService --> NanoHTTPD
            NanoHTTPD --> NativeOrbit
        end

        MediaSession["Android MediaSession"] -->|ForceLocalPlaybackHook| VolControl["Local Hardware Volume Buttons"]
    end
```

---

## 4. Reverse Engineering the ReVanced Implementation

### 4.1 Native Binary Hex-Patching: `liborbit-jni-spotify.so`
Spotify's core networking resides inside the native shared object `liborbit-jni-spotify.so`. To redirect outbound token requests without recompiling C++, ReVanced patched the binary strings:

```kotlin
// patches/src/main/kotlin/app/revanced/patches/spotify/misc/fix/SpoofClientPatch.kt
listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").forEach { architecture ->
    "https://clienttoken.spotify.com/v1/clienttoken" to
            "http://127.0.0.1:$requestListenerPort/v1/clienttoken" inFile
            "lib/$architecture/liborbit-jni-spotify.so"
}
```

### 4.2 The Local Proxy Daemon: `RequestListener` (NanoHTTPD)
ReVanced bundled `NanoHTTPD` to listen on port `4345`. When `liborbit` attempts to reach `/v1/clienttoken`, the local listener intercepts the stream:

```java
// extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/RequestListener.java
class RequestListener extends NanoHTTPD {
    RequestListener(int port) { super(port); start(); }

    @Override
    public Response serve(IHTTPSession session) {
        if (!session.getUri().equals(CLIENT_TOKEN_API_PATH)) return INTERNAL_ERROR_RESPONSE;
        ClientTokenResponse response = serveClientTokenRequest(getInputStream(session));
        return newResponse(Response.Status.OK, response);
    }
}
```

### 4.3 iOS Token Forgery: `ClientTokenService`
The intercepted request is repackaged with official Apple iOS client parameters:

```java
// extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/ClientTokenService.java
class ClientTokenService {
    private static final String IOS_CLIENT_ID = "58bd3c95768941ea9eb4350aaa033eb3";
    private static final String IOS_CLIENT_VERSION = "iphone-9.0.58.558.g200011c";
    private static final String IOS_USER_AGENT = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)";

    static ClientTokenRequest newIOSClientTokenRequest(String deviceId) {
        return IOS_CLIENT_TOKEN_REQUEST
                .setClientData(ClientDataRequest.newBuilder()
                        .setClientVersion(IOS_CLIENT_VERSION)
                        .setClientId(IOS_CLIENT_ID)
                        .setConnectivitySdkData(ConnectivitySdkData.newBuilder()
                                .setDeviceId(deviceId)
                                .setPlatformSpecificData(PlatformSpecificData.newBuilder()
                                        .setIos(NativeIOSData.newBuilder()
                                                .setHwMachine("iPhone16,1")
                                                .setSystemVersion("17.7.2")))))
                .build();
    }
}
```

### 4.4 The Zeroconf Receiver: `AndroidZeroconfServer`
`AndroidZeroconfServer` publishes an mDNS / NSD service on `127.0.0.1`:
- Service type: `_spotify-connect._tcp.`
- Device type: `Connect.DeviceType.AUDIO_DONGLE`
- Device name: `"ReVanced"`

When Spotify's local Connect client discovers this device on localhost, it presents it in the "Connect to a device" picker and connects seamlessly.

### 4.5 Hardware Audio Decoding & PCM Output
To avoid high CPU load from software Vorbis decoding in Java, ReVanced bridged `librespot` to Android's native multimedia framework:
- **`AndroidNativeDecoder`**: Uses Android's hardware-accelerated `MediaCodec` (`audio/vorbis`) via `MediaExtractor` and `MediaDataSource`.
- **`AndroidSinkOutput`**: Writes decoded 16-bit 44.1kHz PCM audio frames directly to `android.media.AudioTrack` in `MODE_STREAM`.

### 4.6 Volume Control Interception: `ForceLocalPlaybackPatch`
When casting to a Spotify Connect device, Android normally switches `MediaSession` volume handling to remote (disabling hardware rocker buttons). ReVanced intercepted this in bytecode:

```smali
# Intercept MediaSession.setPlaybackToRemote()
invoke-virtual { v0, v1 }, Landroid/media/session/MediaSession;->setPlaybackToLocal(Landroid/media/AudioAttributes;)V
```

---

## 5. SpotXero Porting Design: Translating to Modern LibXposed

SpotXero can implement this loopback architecture with far greater elegance and stability than ReVanced's static APK repacking.

### 5.1 Eliminating Binary Hex-Patching via LibXposed
ReVanced was forced to hex-patch `liborbit-jni-spotify.so` because it operated via static APK modification. In SpotXero, we operate in-memory inside ART via **LibXposed (API 102+)**:

1. **Java/JNI Hooking**: Instead of modifying binary string tables in compiled `.so` files, SpotXero can hook `com.spotify.connectivity.ApplicationScopeConfiguration` setters (`setClientId`, `setDefaultHTTPUserAgent`) and intercept the network layer before it reaches JNI.
2. **In-Memory URL Rewriting**: Hook the Java-side HTTP client (OkHttp / Cronet transport) to redirect `/v1/clienttoken` calls directly to the in-process service without touching disk binaries.

### 5.2 Librespot Integration into SpotXero
SpotXero can bundle `xyz.gianlu.librespot:librespot-player:1.6.6-SNAPSHOT:thin` directly in its Gradle build:
- Use `AndroidNativeDecoder` for hardware `MediaCodec` decoding.
- Use `AndroidSinkOutput` for direct low-latency `AudioTrack` playback.
- Package as an optional engine (`ConnectLoopbackEngine`) activated via user preferences.

### 5.3 Dynamic DexKit Resolution for `ForceLocalPlayback`
Instead of static smali patterns, SpotXero uses `DexKitResolver` to dynamically find:
- The `MediaSession` wrapper calling `setPlaybackToRemote`.
- Automatically replace it with `setPlaybackToLocal(AudioAttributes)`.

---

## 6. Porting Feasibility Summary

| Component | ReVanced Implementation | SpotXero Implementation Strategy |
|---|---|---|
| **Binary Patching** | Crude `.so` string rewriting | **In-memory hook** via LibXposed (Clean, zero corruption) |
| **Token Proxy** | `NanoHTTPD` on port 4345 | In-process handler or loopback socket |
| **Connect Engine** | `librespot-player:thin` | Bundled in SpotXero as `ConnectLoopbackEngine` |
| **Audio Pipeline** | `MediaCodec` + `AudioTrack` | Reusable Java classes (`AndroidNativeDecoder`, `AndroidSinkOutput`) |
| **Volume Hook** | Static smali replacement | Dynamic runtime hook via `DexKitResolver` |
| **User Control** | Static patch toggle at build time | Dynamic Compose M3 toggle in SpotXero settings |
