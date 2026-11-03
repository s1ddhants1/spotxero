# SpotXero Engineering Specification: Connect Loopback & Spoof Client

## 1. Executive Summary

This document specifies the concrete engineering blueprint for porting the **Spotify Connect Loopback & Client Spoof** architecture from [ReVanced Patches](file:///home/s1ddhants1/Projects/spotify/revanced-patches) into [SpotXero](file:///home/s1ddhants1/Projects/spotify/spotxero).

### The Objective
Bypass the ~9.6-second on-demand audio playback stall (`mft_disallow`) on Free-tier accounts by:
1. Spawning an in-process, headless **Spotify Connect receiver** (`AUDIO_DONGLE`) via `librespot` on `127.0.0.1` Network Service Discovery (NSD/Zeroconf).
2. Routing on-demand track playback to this local Connect receiver, exploiting the fact that Spotify servers permit full on-demand pick-and-play to Connect targets.
3. Decoding Vorbis audio via hardware-accelerated `MediaCodec` and streaming PCM frames directly to `AudioTrack`.
4. Restoring physical device volume buttons via a `ForceLocalPlaybackHook` on `MediaSession`.
5. Intercepting client token retrieval in memory to obtain iOS tokens without hex-patching `.so` binaries.

---

## 2. ReVanced Source References in Workspace

All porting specifications cite and adapt source code from [`/home/s1ddhants1/Projects/spotify/revanced-patches`](file:///home/s1ddhants1/Projects/spotify/revanced-patches):

- **Connect Loopback Engine**: [`SpoofClientPatch.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/SpoofClientPatch.java)
- **Zeroconf Server**: [`AndroidZeroconfServer.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/AndroidZeroconfServer.java)
- **Hardware Vorbis Decoder**: [`AndroidNativeDecoder.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/AndroidNativeDecoder.java)
- **PCM Audio Sink**: [`AndroidSinkOutput.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/AndroidSinkOutput.java)
- **Token Service**: [`ClientTokenService.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/ClientTokenService.java)
- **Volume Hook**: [`ForceLocalPlaybackPatch.kt`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/patches/src/main/kotlin/app/revanced/patches/spotify/misc/volume/ForceLocalPlaybackPatch.kt)
- **Protobuf Definitions**: [`clienttoken_http.proto`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/proto/app/revanced/extension/spotify/misc/fix/clienttoken_http.proto)
- **Build Configurations**: [`build.gradle.kts`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/build.gradle.kts) and [`proguard-rules.pro`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/proguard-rules.pro)

---

## 3. High-Level Architecture in SpotXero

```mermaid
flowchart TD
    subgraph SpotXero Module Boundary
        Prefs["PreferencesManager (KEY_CONNECT_LOOPBACK)"] --> Engine["ConnectLoopbackService"]
        Engine --> Zeroconf["AndroidZeroconfServer (AUDIO_DONGLE)"]
        Zeroconf --> LibrespotSession["xyz.gianlu.librespot (Session)"]
        LibrespotSession --> Player["librespot.player.Player"]
        Player --> Decoder["AndroidNativeDecoder (MediaCodec)"]
        Decoder --> Sink["AndroidSinkOutput (AudioTrack)"]
        Sink --> Speaker["Device Hardware Speaker"]
    end

    subgraph Spotify Host Boundary (Unholy Runtime)
        SpotifyUI["Spotify Client UI"] -->|Casts Track to 'SpotXero'| Zeroconf
        SpotifyMedia["MediaSession"] --> VolHook["ForceLocalPlaybackHook"]
        VolHook -->|setPlaybackToLocal(AudioAttributes)| DeviceVol["Hardware Volume Rocker"]
        
        NativeOrbit["liborbit-jni"] --> TokenHook["ClientTokenHook"]
        TokenHook -->|Serves iOS Token| NativeOrbit
    end
```

---

## 4. Component Implementation Specifications

### 4.1 Component 1: `ForceLocalPlaybackHook`
When casting to a Spotify Connect device, Spotify normally calls `MediaSession.setPlaybackToRemote()`, detaching device volume buttons. SpotXero can hook this dynamically using DexKit.

#### DexKit Resolution Strategy
Resolve the method in Spotify's playback routing that calls `setPlaybackToRemote`:
- Search for classes with methods referencing `setPlaybackToRemote(Landroid/media/VolumeProvider;)V`.
- Hook the method before execution.
- Invoke `MediaSession.setPlaybackToLocal(AudioAttributes)` with `AudioAttributes.USAGE_MEDIA` instead.

```kotlin
// SpotXero Hook Implementation Concept
class ForceLocalPlaybackHook : BaseHook() {
    override fun hook(classLoader: ClassLoader) {
        val targetMethod = DexKitResolver.resolveForceLocalPlaybackMethod(classLoader)
        hookBefore(targetMethod) { param ->
            val mediaSession = param.thisObject as? MediaSession ?: return@hookBefore
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            mediaSession.setPlaybackToLocal(audioAttributes)
            param.result = null // suppress setPlaybackToRemote
        }
    }
}
```

---

### 4.2 Component 2: `ConnectLoopbackService` & Audio Pipeline
Adapted from ReVanced's [`SpoofClientPatch.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/SpoofClientPatch.java), the engine runs inside the Spotify process:

1. **Decoder Registration**:
   Register [`AndroidNativeDecoder.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/AndroidNativeDecoder.java) with `Decoders.registerDecoder(SuperAudioFormat.VORBIS, AndroidNativeDecoder::class.java)`.
2. **Audio Sink Configuration**:
   Configure `PlayerConfiguration.AudioOutput.CUSTOM` pointing to [`AndroidSinkOutput.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/AndroidSinkOutput.java).
3. **Zeroconf Server Initialization**:
   Launch [`AndroidZeroconfServer.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/AndroidZeroconfServer.java) with `Connect.DeviceType.AUDIO_DONGLE` and device name `"SpotXero"`.
4. **Session Listener**:
   When `sessionChanged` triggers, close any previous player instance and instantiate `Player(configuration, session)`.

---

### 4.3 Component 3: `ClientTokenHook` (Replacing Hex-Patching)
ReVanced's compile-time hex-patch on `liborbit-jni-spotify.so` is replaced in SpotXero with an in-memory hook:

1. **Option A: Hook `ApplicationScopeConfiguration` (Java)**:
   Hook `setDefaultHTTPUserAgent` and `setClientId` to pass the iOS User-Agent and Client ID (`58bd3c95768941ea9eb4350aaa033eb3`).
2. **Option B: Hook Java OkHttp / Cronet URL Builder**:
   Intercept outbound requests directed to `https://clienttoken.spotify.com/v1/clienttoken` and rewrite them to loopback or serve the response directly via [`ClientTokenService.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/ClientTokenService.java).

---

## 5. Gradle Dependencies for SpotXero

To support this engine in `spotxero/app/build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/Emiferpro/librespot-java")
        credentials {
            username = "..."
            password = "..."
        }
    }
}

dependencies {
    implementation("xyz.gianlu.librespot:librespot-player:1.6.6-SNAPSHOT:thin") {
        exclude(group = "xyz.gianlu.librespot", module = "librespot-sink")
        exclude(group = "com.lmax", module = "disruptor")
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
```

---

## 6. SpotXero UI & Preferences Integration

Expose this capability cleanly in SpotXero's Jetpack Compose preferences:

- **Key**: `KEY_CONNECT_LOOPBACK` (`"pref_connect_loopback"`)
- **Title**: *"Spotify Connect Loopback Engine"*
- **Description**: *"Enables in-process Spotify Connect receiver to bypass mobile on-demand playback restrictions."*
- **Lifecycle**: Toggling dynamically starts or stops `AndroidZeroconfServer` without requiring a full app restart.

---

## 7. Step-by-Step Porting Checklist

- [ ] **Step 1: Import Audio Pipeline**: Port [`AndroidNativeDecoder.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/AndroidNativeDecoder.java) and [`AndroidSinkOutput.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/AndroidSinkOutput.java) into `app/src/main/kotlin/app/spotxero/engine/`.
- [ ] **Step 2: Port Zeroconf Server**: Port [`AndroidZeroconfServer.java`](file:///home/s1ddhants1/Projects/spotify/revanced-patches/extensions/spotify/src/main/java/app/revanced/extension/spotify/misc/fix/AndroidZeroconfServer.java) and adapt logging to SpotXero logger.
- [ ] **Step 3: Implement `ForceLocalPlaybackHook`**: Write DexKit resolver signature for `setPlaybackToRemote` and hook `MediaSession`.
- [ ] **Step 4: Implement `ClientTokenHook`**: Hook client token acquisition in memory.
- [ ] **Step 5: Add UI Toggle**: Add preference row in `PlaybackSettingsScreen.kt`.
- [ ] **Step 6: Real Device Verification**: Verify on physical OnePlus 12R that on-demand track streaming continues smoothly past 9.6s.

