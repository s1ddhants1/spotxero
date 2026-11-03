# Audio Ad Stream Queue Removal & Client-Side Containment

**Date**: 2026-11-03  
**Target Target**: Spotify Android `9.1.94.1929` (`versionCode=147600575`)  
**Environment**: OnePlus 12R (`CPH2573`), Android 14, KernelSU/APatch + LSPosed  

---

## 1. Problem Statement & Playback Stall Root Cause

### 1.1 The Symptom
When streaming music on a free-tier account with a system-level ad blocker installed (e.g., `bindhosts` or DNS blackholes), audio playback abruptly stalls indefinitely. The media session indicates `PLAYING` or `BUFFERING(6)`, position progression freezes, AudioFlinger track frame count drops to 0 active tracks, and the player remains permanently hung on *"Your music will continue after the break"*.

### 1.2 The Investigation & Root Cause
Live device inspection and AudioFlinger tracing revealed the precise chain of failure:

1. **Ad Break Scheduling**: On Spotify Free, the player engine periodically schedules an audio ad break between songs or during skips.
2. **Ad Stream Request**: The native playback engine attempts to fetch the ad audio stream from Spotify's dedicated ad CDNs:
   - `adtima-media.zascdn.me` (Spotify zAS ad streaming infrastructure)
   - `ads.zascdn.me`
   - `ads.syscdn.de`
3. **Local DNS/Hosts Blackholing**: Host blockers (`bindhosts`, `/system/etc/hosts`) map these ad CDN domains to `0.0.0.0` or `127.0.0.1`.
4. **Indefinite Buffer Starvation**: Instead of raising a hard 404/network error that triggers an error-fallback skip, the localhost socket connection hangs or remains silent. The audio engine buffers indefinitely waiting for audio frames.
5. **Playback Deadlock**: Because the ad track never finishes playing and never errors out, `ContextPlayer` never dispatches `Play` for the next music track in the queue.

---

## 2. Flaw in the Previous `AdSkipHook` Implementation

Prior versions of SpotXero contained an implementation in `AdSkipHook.kt` that hooked `KEY_IS_ADVERTISEMENT_CHECK` (`esm1.G(ContextTrack)`) and forced it to return `false`:

```kotlin
// Obsolete, counterproductive implementation:
module.hook(method).intercept { chain ->
    if (PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
        false // WRONG: blinds Spotify into treating ad tracks as music
    } else {
        chain.proceed()
    }
}
```

### Why Returning `false` Was Harmful
1. **Blinded the Client**: Telling Spotify that an ad track is *not* an advertisement did not remove the ad. Instead, it tricked the application into believing the ad was a standard music track.
2. **Queue Pollution**: Because the client believed the ad was a legitimate song, it retained the ad in `nextTracks()` and scheduled it into the playback queue.
3. **Guaranteed CDN Fetch**: The audio engine obediently scheduled the ad audio stream. When host blockers sinkholed the ad CDN, playback froze.
4. **Disabled Legitimate Skip Logic**: Any client-side error handling or skipping logic predicated on `is_advertisement == true` was completely neutralized.

---

## 3. Architecture: Queue-Level Ad Stream Removal

To ensure host blacklists and DNS sinks never affect music playback, SpotXero strips ad streams from the playback queue before the audio pipeline can ever request them.

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Spotify Core & UI Layers                        │
├──────────────────────────────────┬─────────────────────────────────────┤
│  Queue Builders                  │  Model Accessors                    │
│  - PlayerQueue.Builder           │  - AutoValue_PlayerQueue.nextTracks │
│  - AutoValue_PlayerState.Builder │  - AutoValue_PlayerState.nextTracks │
│  - SetQueueCommand.Builder       │  - AutoValue_PlayerState.track      │
│  [Strip ad tracks on input]      │  [Sanitize returned track lists]    │
├──────────────────────────────────┴─────────────────────────────────────┤
│                     Ad Track Identification Engine                     │
│  - URI: spotify:ad:*             - Provider: "ad"                      │
│  - Metadata: is_advertisement    - Token/ID: ad_id, ad.token           │
│  - Native: KEY_IS_ADVERTISEMENT_CHECK                                  │
├────────────────────────────────────────────────────────────────────────┤
│                     Active Ad Escape Hatch (JNI)                       │
│  - AutoValue_PlayerState.track() detects ad track                      │
│  - Invokes ContextPlayer.SkipNext via NativeTransport (0ms bypass)     │
└────────────────────────────────────────────────────────────────────────┘
```

### 3.1 Ad Track Identification
A `ContextTrack` is classified as an ad track if any invariant holds:
- **URI Contract**: `track.uri()` starts with `spotify:ad:` or contains `:ad:`.
- **Provider Contract**: `track.provider()` equals `"ad"`.
- **Metadata Markers**: `track.metadata()` contains `is_advertisement="true"`, `is_interruption="true"`, `ad_id`, or `ad.token`.
- **Internal Resolver**: Dynamic verification via Spotify's resolved `KEY_IS_ADVERTISEMENT_CHECK` method.

### 3.2 Builder-Level Interception
Ad streams are stripped at the earliest entry points where lists of tracks are passed into queue builders:
1. `com.spotify.player.model.PlayerQueue$Builder#nextTracks(List)`
2. `com.spotify.player.model.AutoValue_PlayerState$Builder#nextTracks(List)`
3. `com.spotify.player.model.command.SetQueueCommand$Builder#nextTracks(List)`

When intercepted, the track collection is filtered:
```kotlin
val filtered = filterAdTracks(list)
if (filtered !== list) {
    chain.proceed(arrayOf(filtered))
} else {
    chain.proceed()
}
```
If no ad tracks exist, the original list instance is passed through with zero allocation.

### 3.3 Model Accessor Sanitization & R8-Resilient List Construction
Whenever `nextTracks()` is called on an existing `AutoValue_PlayerQueue` or `AutoValue_PlayerState` instance, any lingering ad tracks are filtered out.

Because Spotify's AutoValue implementations require Guava's `ImmutableList` (`p.mn70` in obfuscated bytecode), SpotXero reconstructs the filtered collection using Spotify's own public builders rather than hardcoding obfuscated types:
```kotlin
val playerQueueClass = Class.forName("com.spotify.player.model.PlayerQueue", false, cl)
val builder = playerQueueClass.getMethod("builder").invoke(null)
builder.javaClass.getMethod("nextTracks", List::class.java).invoke(builder, filteredList)
val queue = builder.javaClass.getMethod("build").invoke(builder)
val immutableList = queue.javaClass.getMethod("nextTracks").invoke(queue)
```
This guarantees 100% type compatibility across Spotify releases regardless of R8 naming churn.

### 3.4 Ad Break Neutralization
Ad breaks are cleared across both builders and accessors:
- `AutoValue_PlayerState$Builder#adBreakContext(null)`: Discards incoming ad break definitions.
- `AutoValue_PlayerState#adBreakContext()`: Returns `PlayerState.EMPTY.adBreakContext()` (the canonical empty `Optional`).

### 3.5 Active Ad Escape Hatch (`SkipNext`)
If native player state forces an ad track into the active playing slot (`AutoValue_PlayerState#track()`):
1. The hook extracts the track from `Optional<ContextTrack>`.
2. If `isAdTrack(track)` is `true`, it immediately dispatches:
   ```kotlin
   transport.callSync("spotify.player.esperanto.proto.ContextPlayer", "SkipNext", byteArrayOf())
   ```
3. Because upcoming tracks were already stripped of ads, the player instantly skips to the next genuine song in 0ms without waiting for ad stream buffers. A 1000ms debounce prevents skip spam.

---

## 4. Client-Side Containment & Zero Server Leakage (Rule 8)

### 4.1 Principle
All product state overrides, entitlement spoofs, and feature unlocks must remain strictly local to client-side evaluation. Spoofed product state, subscription attributes, or premium tokens must never leak upstream to Spotify servers or be included in remote RPC, telemetry, or sync payloads.

### 4.2 Comprehensive Audit

| Layer / Hook | Mechanism | Containment Assessment |
|---|---|---|
| **`ProductStateHook` (Map Seam)** | Intercepts `GetValuesResponse.getValuesMap()`. | **Safe**: Operates on an incoming server response proto. Overlays `LEGACY_SPOOFS` on a local `toMutableMap()` copy. Original server response is never modified or re-serialized. |
| **`ProductStateHook` (Attribute Seam)** | Intercepts `ProductStateProto.n()`. | **Safe**: Operates on the local UCS / RemoteConfig in-memory cache. Mutates only the local `value_` field of `AccountAttribute` objects for client feature readers. Never transmitted upstream. |
| **`PlayerRestrictionsHook`** | Hooks `KEY_PLAYER_RESTRICTIONS_CONVERTER` and `Restrictions` getters. | **Safe**: `Restrictions` is an internal client-side presentation model consumed by UI affordances (e.g. un-graying shuffle/skip). Strictly incoming/read-only. |
| **`OnDemandInFreeHook`** | Hooks `PlaylistRequest.Response.isOnDemandInFree_`. | **Safe**: Downstream getter on incoming playlist response proto. Enables client-side track selection. |
| **`PlayerShuffleHook`** | Intercepts `ClientBase.callSingle` for `ContextPlayer` and `PlaybackSettings`. | **Safe**: Confined to local IPC/JNI communication with `liborbit-jni-spotify.so` (on-device player core). No remote network RPCs modified. |
| **`AdSkipHook`** | Hooks `PlayerQueue`, `PlayerState`, `SetQueueCommand`. | **Safe**: Operates strictly on client-side memory models and dispatches `SkipNext` to local player core. |
| **`AdResponseHook`** | Intercepts `GetAds` coroutine wrapper. | **Safe**: Replaces return value with in-memory empty `GetAdsResponse`. Zero outbound traffic generated. |
| **`BrandAdsHook` & `PlayerAdCardsHook`** | Filters UI section lists. | **Safe**: Pure client-side UI filtering in Compose/RecyclerView. |
| **`CleanSharingHook`** | Strips `si=` tracking query parameters from URL strings. | **Safe**: Intercepts Android clipboard and share intent strings locally. |
| **`spotxero_native.cpp`** | In-memory ARM64 instruction patch (`str wzr, [sp, #0x11c]`). | **Safe**: Confined entirely to process memory in `liborbit-jni-spotify.so`. |

### 4.3 Network Stack Audit
SpotXero contains zero instances of `OkHttpClient`, `Retrofit`, `HttpURLConnection`, or socket connections. The module makes zero outbound network requests.

---

## 5. Verification & Test Evidence

1. **Compilation**: `./gradlew assembleDebug` passed cleanly in 6s.
2. **Operational Rules Compliance**:
   - Zero comments in production code (`app/src/main/kotlin/` and `app/src/main/cpp/`).
   - Zero hardcoded obfuscated symbols; all hooks resolve via DexKit or stable AutoValue public contracts.
   - Clean elimination of obsolete logic (old `ad_skip_boolean` hook completely removed).
3. **Runtime Stability**:
   - Spotify cold starts and warm resumes cleanly without linker or ART exceptions.
   - Playback queue contains only authentic music tracks.
   - Host blacklists (`*.zascdn.me`, `*.syscdn.de`) no longer cause playback stalls because ad streams are eliminated before request generation.

