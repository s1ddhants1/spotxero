# Feature: Audio Ad Blocking

**Sources**:
- Morphe: [`BrandAdsPatch`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/ads/BrandAdsPatch.kt), [`PlayerAdCardsPatch`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/ads/PlayerAdCardsPatch.kt), [`Esperanto.java`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/extensions/extension/src/main/java/app/spicetify/extension/spotify/extensions/Esperanto.java) (player state with `is_advertisement` flag)
- Desktop Spicetify: [`adblock.js`](https://github.com/rxri/spicetify-extensions/blob/main/adblock/adblock.js)
- SpotXero existing: [`PremiumHookTemplate`](file:///home/s1ddhants1/Projects/spotify/spotxero/app/src/main/kotlin/app/spotxero/hooks/PremiumHookTemplate.kt)

**Porting difficulty**: ⚠️ Medium-High — multi-layered approach across several ad surfaces

---

## Ad Delivery Architecture on Android Spotify

Spotify's ad pipeline on Android has these layers:

```
┌─────────────────────────────────────────────────────────────┐
│  Product State Service                                      │
│  Keys: ads, catalogue, product, type                        │
│  Controls: whether the client even requests ads             │
├─────────────────────────────────────────────────────────────┤
│  Ad Settings (Esperanto)                                    │
│  Services: spotify.ads.esperanto.settings.proto.Settings    │
│            spotify.ads.esperanto.slots.proto.Slots          │
│            spotify.ads.esperanto.testing.proto.Testing      │
│  Controls: slot enable/disable, server endpoints, intervals │
├─────────────────────────────────────────────────────────────┤
│  Ad Managers                                                │
│  AudioAdManager, BillboardAdManager, LeaderboardAdManager,  │
│  SponsoredPlaylistManager, EmbeddedAdManager, VTOManager    │
│  Controls: per-format ad lifecycle and rendering            │
├─────────────────────────────────────────────────────────────┤
│  Ad Core Connector (InStream API)                           │
│  Methods: clearSlot(), subscribeToSlot()                    │
│  Controls: real-time slot population and clearing           │
├─────────────────────────────────────────────────────────────┤
│  Player State (ContextPlayer/GetState)                      │
│  Field: ContextTrack.metadata["is_advertisement"]           │
│  Observable: live stream of current track state             │
├─────────────────────────────────────────────────────────────┤
│  UI Rendering                                               │
│  Home sections (casita), Browse sections (browsita),        │
│  Now Playing ad cards (scrollsita), Compose views           │
│  Controls: visual ad surfaces                               │
└─────────────────────────────────────────────────────────────┘
```

---

## Strategy Overview

Five defense layers, from most impactful to most surgical:

| Layer | Strategy | Source | Impact | Complexity |
|---|---|---|---|---|
| L1 | Product state override | adblock.js | 🔴 Highest — kills ad requests at source | Medium |
| L2 | Ad manager disabling | adblock.js | 🟠 High — disables all ad format handlers | Medium |
| L3 | Ad slot manipulation | adblock.js | 🟡 Medium — clears/disables individual slots | High |
| L4 | Player state ad skip | Morphe Esperanto | 🟡 Medium — skips ads that slip through | High |
| L5 | Visual ad filtering | Morphe patches | 🟢 Supplementary — hides UI remnants | Low |

**L5 is already documented** in [03-hide-brand-ads.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/03-hide-brand-ads.md) and [04-hide-player-ad-cards.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/04-hide-player-ad-cards.md). This document covers L1–L4.

---

## Layer 1: Product State Override

### What adblock.js Does

```javascript
await d.putOverridesValues({
    pairs: { ads: "0", catalogue: "premium", product: "premium", type: "premium" }
});
// Subscribes to changes and re-applies:
d.subValues({ keys: ["ads", "catalogue", "product", "type"] }, () => configureAdManagers());
```

This makes Spotify's client believe it's running as a premium account for ad-decision purposes. The `ProductStateService` is the single source of truth for feature gating.

### Android Translation

On Android, the product state is managed by `UserAPI._product_state` / `ProductStateAPI`. The equivalent class is the **product state service** that Spotify queries to decide entitlements.

**DexKit Resolution:**

```kotlin
// The product state is stored/read through a service that handles key-value pairs.
// Key strings to search for: "ads", "catalogue", "product", "type"
// The service class name is likely stable: com.spotify.productstate.* or similar

// Approach 1: Hook the getter that reads product state values
dexKit.findMethod {
    matcher {
        usingStrings("ads", "catalogue", "product")
        // Method signature: takes a String key, returns a String value
        paramTypes("java.lang.String")
        returnType("java.lang.String")
    }
}

// Approach 2: Hook putOverridesValues equivalent — the method that writes overrides
// On Android, this might be putValues / setValues on the product state provider
dexKit.findMethod {
    matcher {
        usingStrings("product_state", "overrides")
        // Or find by the protobuf service name
    }
}
```

**Hook Implementation:**

```kotlin
// Option A: Override at the read site — most robust
module.hook(productStateGetMethod).after { param ->
    if (!PreferencesManager.isEnabled(PrefKeys.KEY_BLOCK_ADS, true)) return@after
    val key = param.args[0] as? String ?: return@after
    when (key) {
        "ads" -> param.result = "0"
        "catalogue" -> param.result = "premium"
        "product" -> param.result = "premium"
        "type" -> param.result = "premium"
    }
}

// Option B: Override at the write site — intercept the initial values
module.hook(productStatePutMethod).before { param ->
    if (!PreferencesManager.isEnabled(PrefKeys.KEY_BLOCK_ADS, true)) return@before
    // Modify the pairs map/proto before it's stored
    val pairs = param.args[0] // might be a Map or proto message
    injectPremiumOverrides(pairs)
}
```

**Risk**: Overriding product state too aggressively may trigger server-side validation. The `"type": "premium"` override should be limited to the local ad-decision codepath, not sent back to Spotify's servers.

**Mitigation**: Hook only the **reader** (Option A), not the writer. This ensures the override is purely local — Spotify's servers never see the spoofed values.

---

## Layer 2: Ad Manager Disabling

### What adblock.js Does

```javascript
const { audio, billboard, embeddedAd, inStreamApi, leaderboard, sponsoredPlaylist, vto } = AdManagers;

await disableManager(audio);
audio.isNewAdsNpvEnabled = false;
await disableManager(billboard);
await leaderboard?.disableLeaderboard();
await disableManager(sponsoredPlaylist);
await disableManager(inStreamApi);
if (vto) {
    await disableManager(vto.manager);
    vto.isNewAdsNpvEnabled = false;
}
await disableManager(embeddedAd?.embeddedAdManager);
```

Each manager has a `.disable()` method that stops it from requesting/rendering ads.

### Android Translation

On Android, ad managers are Java objects instantiated during Spotify's startup. They're accessible from the `AdManagers` (or equivalent) container class.

**DexKit Resolution:**

```kotlin
// Find the AdManagers container class — it instantiates all format-specific managers
// Likely identifiable by: it creates/holds references to audio ad, billboard, etc.
// Search by esperanto service names used for ad slot communication
dexKit.findClass {
    matcher {
        usingStrings(
            "spotify.ads.esperanto.settings.proto.Settings",
            "spotify.ads.esperanto.slots.proto.Slots"
        )
    }
}

// Find the AudioAdManager — the primary target for audio ad blocking
// It likely has methods named "disable" or "setEnabled" and references audio playback
dexKit.findClass {
    matcher {
        // AudioAdManager manages audio ad playback
        // May reference: "audio_ad", "ad_break", or cosmos paths like "sp://ads/"
        usingStrings("sp://ads/")
        methods {
            add { name("disable") }
        }
    }
}
```

**Hook Implementation:**

```kotlin
// Approach 1: Hook the constructor of each ad manager — prevent initialization
module.hookAllConstructors(audioAdManagerClass).after { param ->
    if (!PreferencesManager.isEnabled(PrefKeys.KEY_BLOCK_ADS, true)) return@after
    // Call disable() on the newly constructed manager
    runCatching {
        val disableMethod = param.thisObject::class.java.getMethod("disable")
        disableMethod.invoke(param.thisObject)
    }
}

// Approach 2: Hook the AdManagers container — disable all at once
module.hook(adManagersInitMethod).after { param ->
    if (!PreferencesManager.isEnabled(PrefKeys.KEY_BLOCK_ADS, true)) return@after
    val managers = param.thisObject
    // Iterate through all manager fields and call disable() on each
    for (field in managers::class.java.declaredFields) {
        field.isAccessible = true
        val manager = field.get(managers) ?: continue
        runCatching {
            manager::class.java.getMethod("disable").invoke(manager)
        }
    }
}

// Approach 3: Hook the "isEnabled" check that each manager calls before serving an ad
module.hook(adManagerIsEnabledMethod).after { param ->
    if (PreferencesManager.isEnabled(PrefKeys.KEY_BLOCK_ADS, true)) {
        param.result = false
    }
}
```

**Preferred approach**: Option 3 (hook `isEnabled`) — it's the least invasive and doesn't risk breaking Spotify's initialization sequence. If the manager thinks it's disabled, it simply won't serve ads, and all downstream code paths handle the "no ad" case gracefully.

---

## Layer 3: Ad Slot Manipulation via Cosmos

### What adblock.js Does

```javascript
// 1. Get all ad slots
const slots = (await slotsClient.getSlots()).adSlots;

// 2. For each slot:
//    a. Subscribe to slot events
//    b. Clear the slot
//    c. Update settings to prevent future ads
for (const slot of slots) {
    adsCoreConnector.subscribeToSlot(slot.slotId, handleAdSlot);
    adsCoreConnector.clearSlot(slot.slotId);
    slotsClient.clearAllAds({ slotId: slot.slotId });

    // Redirect ad server, disable slot, zero out intervals
    settingsClient.updateAdServerEndpoint({ slotIds: [slot.slotId], url: "http://localhost/no/thanks" });
    settingsClient.updateStreamTimeInterval({ slotId: slot.slotId, timeInterval: 0n });
    settingsClient.updateSlotEnabled({ slotId: slot.slotId, enabled: false });
    settingsClient.updateDisplayTimeInterval({ slotId: slot.slotId, timeInterval: 0n });
}

// 3. Post massive negative playtime to reset ad timer
testingClient.addPlaytime({ seconds: -1e11 });
// Equivalent to: POST sp://ads/v1/testing/playtime { value: -100000000000 }
```

### Android Translation

This requires the **PlayerBridge/CosmosRouter** infrastructure (see [05-home-pins.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/05-home-pins.md) and [09-architecture-patterns.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/09-architecture-patterns.md)).

**Cosmos Requests (once the bridge is established):**

```kotlin
// 1. Get all ad slots
cosmosRouter.get("sp://ads/v1/slots") { body ->
    val slots = parseAdSlots(body) // proto parsing
    for (slot in slots) {
        clearAdSlot(slot.slotId)
        disableAdSlot(slot.slotId)
    }
}

// 2. Post negative playtime
cosmosRouter.post("sp://ads/v1/testing/playtime", buildPlaytimeProto(-100_000_000_000L)) { /* done */ }

// 3. Clear individual slot
fun clearAdSlot(slotId: String) {
    // Via the esperanto Settings service
    cosmosRouter.call(
        "spotify.ads.esperanto.settings.proto.Settings",
        "UpdateSlotEnabled",
        buildSlotEnabledProto(slotId, enabled = false)
    ) { /* done */ }
}
```

**Alternative — Hook the Settings/Slots Esperanto services directly:**

Instead of making cosmos requests through the bridge, hook the esperanto service classes at the Java level:

```kotlin
// Find the Settings esperanto service implementation
dexKit.findClass {
    matcher {
        usingStrings("spotify.ads.esperanto.settings.proto.Settings")
        // Or: "spotify.ads.esperanto.proto.Settings" (older versions)
    }
}

// Find the Slots esperanto service
dexKit.findClass {
    matcher {
        usingStrings("spotify.ads.esperanto.slots.proto.Slots")
    }
}

// Hook: when the Slots service returns slots, intercept and clear them all
module.hook(getSlotsMethod).after { param ->
    if (!PreferencesManager.isEnabled(PrefKeys.KEY_BLOCK_ADS, true)) return@after
    // The result is a proto containing adSlots — clear them or return empty
    param.result = emptySlotResponse()
}
```

**This "hook the service directly" approach is unique to Xposed** — adblock.js must call through the cosmos transport because it's a JavaScript extension without Java-level access. SpotXero can intercept at a deeper level.

---

## Layer 4: Player State Ad Detection and Auto-Skip

### What Morphe's Esperanto Reveals

From [`Esperanto.parseState()`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/extensions/extension/src/main/java/app/spicetify/extension/spotify/extensions/Esperanto.java#L50-L66):

```java
// ContextPlayerState { 2 context_uri, 7 track: ProvidedTrack { 1 context_track } }
// ContextTrack { 1 uri, 2 uid, 3 metadata: map<string, string> }
// metadata key "is_advertisement" == "true" → it's an ad
state.advertisement = "true".equals(metadata.get("is_advertisement"));
```

The player state proto stream (`sp://esperanto/spotify.player.esperanto.proto.ContextPlayer/GetState`) reports whether the current track is an advertisement via the `is_advertisement` metadata field.

### Android Translation

**Approach A — Cosmos stream (requires PlayerBridge):**

```kotlin
// Subscribe to ContextPlayer/GetState stream
playerBridge.addStateListener { state ->
    if (!PreferencesManager.isEnabled(PrefKeys.KEY_BLOCK_ADS, true)) return@addStateListener
    if (state.advertisement) {
        // Skip the ad track
        playerBridge.call(
            Esperanto.CONTEXT_PLAYER,
            "SkipNext",
            Esperanto.skipNext()
        ) { /* skipped */ }
    }
}
```

**Approach B — Hook the player state consumer directly (no bridge needed):**

```kotlin
// Find the class that reads ContextTrack metadata and checks is_advertisement
// This is in the player's state machine — when it sees an ad, it triggers ad UI
dexKit.findMethod {
    matcher {
        usingStrings("is_advertisement")
    }
}

// Hook: override the is_advertisement check to always return false
module.hook(isAdCheckMethod).after { param ->
    if (PreferencesManager.isEnabled(PrefKeys.KEY_BLOCK_ADS, true)) {
        param.result = false // "this track is not an ad"
    }
}
```

**Approach C — Hook the ContextTrack metadata map:**

```kotlin
// The ContextTrack has a metadata Map<String, String>
// Hook the Map.get() call when key == "is_advertisement"
// This is too broad — would hook every Map.get() in the process

// Better: hook the method that specifically reads metadata from ContextTrack
// and interprets the "is_advertisement" value
dexKit.findMethod {
    matcher {
        usingStrings("is_advertisement", "true")
        returnType("boolean")
    }
}
```

**Preferred approach**: B or C — hooking the ad detection at the Java level doesn't require the full PlayerBridge infrastructure. Finding the `is_advertisement` string usage via DexKit is straightforward and version-resilient.

---

## Layer 5: Feature Flag Overrides

### What adblock.js Does

```javascript
const flags = {
    enableEsperantoMigration: true,    // Use new esperanto ad pipeline (more hookable)
    enableInAppMessaging: false,        // Disable promotional messages
    hideUpgradeCTA: true,               // Hide "Upgrade to Premium" buttons
    enablePremiumUserForMiniPlayer: true // Premium mini-player behavior
};
// Applied via RemoteConfigDebugAPI.setOverride()
```

### Android Translation

Spotify on Android also uses remote config / feature flags. These may be accessible via:

```kotlin
// Find the remote config / feature flag system
dexKit.findClass {
    matcher {
        usingStrings("enableInAppMessaging", "hideUpgradeCTA")
    }
}

// Or find the feature flag resolver
dexKit.findMethod {
    matcher {
        usingStrings("enableEsperantoMigration")
        returnType("boolean")
    }
}

// Hook: override specific flag values
module.hook(featureFlagGetMethod).after { param ->
    val flagName = param.args[0] as? String ?: return@after
    when (flagName) {
        "enableInAppMessaging" -> param.result = false
        "hideUpgradeCTA" -> param.result = true
    }
}
```

**Note**: Android feature flags may use different names or a different system than the web client. The exact flag names need to be discovered via DexKit string search in the target APK.

---

## Implementation Plan

### Phase 1: Product State Override (Highest Impact, Least Code)

```
hooks/ads/
└── ProductStateOverrideHook.kt
```

- [ ] DexKit: Find the product state reader method (by strings `"ads"`, `"catalogue"`, `"product"`, `"type"`)
- [ ] Hook: Override return values for ad-related keys
- [ ] Validation: Verify that the override is local-only (not sent to servers)
- [ ] Settings: Wire to `PrefKeys.KEY_BLOCK_ADS` (or reuse `KEY_ENABLE_PREMIUM`)

**This single hook may be sufficient to block all audio ads.** If product state says `ads: "0"` and `catalogue: "premium"`, Spotify's ad pipeline won't request or serve ads at all.

### Phase 2: Ad Manager Disabling (Redundancy)

```
hooks/ads/
└── AdManagerHook.kt
```

- [ ] DexKit: Find ad manager classes via esperanto service strings
- [ ] Hook: Intercept `isEnabled()` checks or call `disable()` after construction
- [ ] Focus: `AudioAdManager` first, then billboard/leaderboard/sponsored

### Phase 3: Player State Ad Skip (Safety Net)

```
hooks/ads/
└── AdSkipHook.kt
```

- [ ] DexKit: Find `"is_advertisement"` string usage in player state code
- [ ] Hook: Override the ad detection to return `false`, or
- [ ] Hook: Intercept at the player command level to auto-skip ad tracks
- [ ] This is the **last-resort layer** — catches any ad that slips through L1/L2

### Phase 4: Ad Slot Cosmos Manipulation (Optional, Requires Bridge)

```
hooks/ads/
└── AdSlotHook.kt
```

- [ ] Requires: PlayerBridge infrastructure from Phase 4 of the main roadmap
- [ ] Cosmos: `sp://ads/v1/slots` → clear all, `sp://ads/v1/testing/playtime` → negative
- [ ] Alternative: Hook the Slots/Settings esperanto services directly (no bridge needed)

---

## Proposed File Structure

```
app/src/main/kotlin/app/spotxero/hooks/ads/
├── ProductStateOverrideHook.kt   ← L1: Product state spoofing
├── AdManagerHook.kt              ← L2: Disable ad managers
├── AdSkipHook.kt                 ← L3+L4: Player ad detection override
├── BrandAdsHook.kt               ← L5: Home/Browse visual ad filtering (existing plan)
├── PlayerAdCardsHook.kt          ← L5: Now Playing ad cards (existing plan)
└── AdSectionFilter.kt            ← Shared filtering utility
```

---

## DexKit Resolver Keys

```kotlin
object AdResolverKeys {
    const val KEY_PRODUCT_STATE_GET = "product_state_get_value"
    const val KEY_AD_MANAGER_CONTAINER = "ad_managers_container"
    const val KEY_AUDIO_AD_MANAGER = "audio_ad_manager"
    const val KEY_AD_IS_ENABLED = "ad_manager_is_enabled"
    const val KEY_IS_ADVERTISEMENT_CHECK = "is_advertisement_check"
    const val KEY_AD_SLOTS_SERVICE = "ad_slots_esperanto_service"
    const val KEY_AD_SETTINGS_SERVICE = "ad_settings_esperanto_service"
    const val KEY_HOME_SECTION_LIST = "home_section_list_getter"
    const val KEY_BROWSE_SECTION_LIST = "browse_section_list_getter"
    const val KEY_IMAGE_AD_FLAG = "scrollsita_section_is_ad"
    const val KEY_EMBEDDED_AD_PREDICATE = "embedded_ad_predicate"
}
```

---

## Relationship to Existing SpotXero Code

| SpotXero Existing | Relevance |
|---|---|
| [`PremiumHookTemplate`](file:///home/s1ddhants1/Projects/spotify/spotxero/app/src/main/kotlin/app/spotxero/hooks/PremiumHookTemplate.kt) | L1 product state override may overlap — if SpotXero already hooks premium entitlements, the ad blocking may be partially covered |
| [`PrefKeys.KEY_ENABLE_PREMIUM`](file:///home/s1ddhants1/Projects/spotify/spotxero/app/src/main/kotlin/app/spotxero/core/PrefKeys.kt#L6) | Could be reused as the master ad-blocking toggle, or a separate `KEY_BLOCK_ADS` for finer control |
| [`DexKitSignatureCache`](file:///home/s1ddhants1/Projects/spotify/spotxero/app/src/main/kotlin/app/spotxero/core/DexKitSignatureCache.kt) | All new resolver keys will be cached here |

---

## Risk Assessment

These are the risks of the *plan* above, written before the device work. The limits that turned out to matter — the break being native, `GetAds` not being the creative source, ad metadata being read-only — are in the Verified Findings section at the end.

| Layer | Risk | Mitigation |
|---|---|---|
| L1 Product state | Server-side validation could detect mismatch | Hook only the local reader, not the writer; don't send premium state upstream |
| L2 Ad managers | `disable()` may not exist on all versions | Fail open — if method not found, skip this layer |
| L3 Ad slots | Requires PlayerBridge infrastructure | Defer until bridge is built; L1+L2 likely sufficient |
| L4 Ad skip | Skipping too fast may trigger rate limiting | Add a small delay before skip; only activate if `is_advertisement` is true |
| L5 Visual | Protobuf discriminator values may change | Fail open — show unfiltered list on error |

## Recommendation

**Ship `AdResponseHook` + `AdSkipHook`, and treat L1 as unverified rather than as the answer.** The plan above was written before the device measurements; the Verified Findings and Native Disassembly sections at the end of this document overturn its premise. L1's "one hook blocks everything" does not hold on this version: the break is scheduled and played by the native player, not by the Java product-state reader, and L1 was never exercised on device. Native does carry a product-state ads field, so L1 is untested, not disproven. L2–L4 remain worth having for the surfaces they do reach (ad fetching, ad UI, analytics), but none of them stops a break from starting or from being audible. The only audible option measured as feasible is silence-the-break, and the static pass over the player library found no single gate to flip.

---

## Verified Findings (2026-10-30, Spotify 9.1.x, free tier, device SM-S938B)

Audio ad breaks are **not removable from Java-process hooks on this version**. Measured on a real device while playing a playlist, each break was a two-ad sequence (`Advertisement • 1 of 2`, `• 2 of 2`) tracked in the media session metadata. Every observation below is from the same session:

| Observation | Evidence |
|---|---|
| The break is driven by the native player, not Java | The ad track (`spotify:ad:<id>`) becomes current from `ContextPlayer` state, and the app only observes it (`b72#d` reads `is_advertisement`/`ad_id`/`duration`). There is no separate player process: `ps -A` shows one `com.spotify.music` process, and the engine is native — `k2q0` calls `System.loadLibrary("orbit-jni-spotify")`, and `liborbit-jni-spotify.so` contains `ad_break_started`, `ad_break_in_progress`, `ad.skippable_ad_delay`, `cannot_fetch_ads_during_an_ad_break` and `ad_clips_per_free_tier_ad_break` |
| No Java ad-track factory runs | `ku0#a` (the only `ContextTrack` builder writing `is_advertisement`) never fired at a break; only observers did |
| `GetAds` returns before playback and can be emptied safely | The stub is called per break; returning a decoded empty `GetAdsResponse` is stable (no crash) but the break still plays, so the creative does not come from that response |
| Ad metadata is read-only for playback | Forcing `is_advertisement(ContextTrack)` to `false` (L4) changes nothing in playback behaviour; it only feeds UI/analytics |
| Cached creative is not the source | Purging the whole app cache and restarting still produced ad breaks |
| The ad is not skippable client-side | `KEYCODE_MEDIA_NEXT` during `Advertisement • 2 of 2` was ignored; `Restrictions.disallowSkippingNextReasons` and `ad.skippable_ad_delay` are supplied by the player state |
| `SubInStream` is not the audio path | `SubInStream` is subscribed from a Compose screen (start-up, once); silencing that stream never coincided with a break |
| The full ad track is visible in Java, but without any audio reference | Hooking `AutoValue_ContextTrack#metadata()` shows the break's track with `ad_id`, `ad.token`, `ad.is_skippable=false`, `duration=30000`, `has_ephemeral_uri=true` — and no audio URL or file id |
| Java's network layer only touches ad *tracking*/asset hosts | Instrumenting `java.net.InetAddress` shows music resolving `audio-ak.spotifycdn.com` / `image-cdn-fa.spotifycdn.com`, while a break adds `heads-fa-tls13.spotifycdn.com` ~1 s before the ad becomes current, then `aet.spotify.com` and `canvaz.scdn.co` |
| Cutting Java DNS for the ad host does not stop the break | Forcing `InetAddress.getAllByName("heads-fa-…")` to throw `UnknownHostException` still played both ads of the break (30 s each); music resumed normally afterwards, so the ad audio is fetched by native code with its own resolution |

**Independent corroboration**: [oyasumi-patches](12-oyasumi-cross-check.md), a separate Morphe patcher pinned to Spotify `9.1.90.2270`, patches the same `GetAds` coroutine wrapper and returns an empty `GetAdsResponse` via the proto parse (`GetAdsResponse;->r([B)` with a zero-length array, because `DEFAULT_INSTANCE` is private). Its own note admits the same limit — *"this seam is only one of Spotify's ad paths; ... playback slates may use other services. Runtime behavior is untested"* — while the patch description claims audio ads are disabled. Two projects, one seam, no evidence of an audible block: keep the UI wording at "blocks ad fetching".

Kept hooks: [`AdResponseHook`](file:///home/s1ddhants1/Projects/spotify/spotxero/app/src/main/kotlin/app/spotxero/hooks/ads/AdResponseHook.kt) (empty `GetAds` response, blocks ad fetching for other surfaces) and [`AdSkipHook`](file:///home/s1ddhants1/Projects/spotify/spotxero/app/src/main/kotlin/app/spotxero/hooks/ads/AdSkipHook.kt) (`is_advertisement` → `false`, keeps ad UI/analytics from treating ad tracks as ads).

## Native Disassembly (`liborbit-jni-spotify.so`, 9.1.94.1929)

Pulled `split_config.arm64_v8a.apk` from the test device and extracted `lib/arm64-v8a/liborbit-jni-spotify.so`: 25,065,936 bytes, aarch64, stripped (NDK r27c, BuildID `463ffdb9ed155010`), `.text` at `0xa54b10`, `.rodata` at `0x332500`, embedded protobuf descriptors in `protodesc_cold` at `0x581a0c`. String xrefs were resolved by scanning `.text` for adjacent `adrp`+`add` pairs that materialise a `.rodata` address, then reading each site with `llvm-objdump`. Addresses below are this build only.

### Where the ad-break vocabulary actually lands

| String | Address | Referencing code | Role |
|---|---|---|---|
| `ad_break_started` | `0x359e36` | `0x15bafac` | small function returning the literal |
| `ad_break_in_progress` | `0x383517` | `0x12c75fc` | case **43** of an enum→string switch at `0x12c75a8` (62 parsed jump-table entries) |
| `ad_break_ended` | `0x342c0e` | `0x11a7008` | transition handler; `csel` picks this or `user_skipped_out` on `w23 == 4` |
| `cannot_fetch_ads_during_an_ad_break` | `0x371ac8` | `0x12c789c` | case **1** of the same switch — an ad-slot skip reason, not a guard |
| `slot_fetching_turned_off` | `0x36aaab` | `0x12c7644` | case **47** of the same switch |
| `quiet_dummy` | `0x37c2cd` | `0x12c75e4` | case **50** — placeholder ad |
| `ad.skippable_ad_delay` | `0x444a38` | `0x12d8474` | ad-track metadata assembly, next to `ad.canvas.file_id`, `ad.video_orientation`, `ad.companion.type`, `podcast_ads.parent_uri` |
| `last_ad_break_stream_time` | `0x334a39` | `0xb1be14` | global initialised in static init (`__cxa_atexit`), among persisted player-state keys (`product state changed`, `played-state`, `active_ads`) |
| `ad_clips_per_free_tier_ad_break` | `0x4423e8` | `0x1288f54` | client config registration: section `core-list-player`, default **6** |

None of these is a scheduling branch. The switch is the ad-slot **reason** enum; the metadata insert is where the Java-visible ad track gets its `ad.*` keys; the config call registers a knob. The library also carries `FreeTierQueue`, `FreeTierSequence`, `MOBILE_FREE_TIER`, `TABLET_FREE_TIER`, so the free-tier path is real — it just has no printable decision point.

### The two knobs that are about scheduling

**Clips per break.** One function registers the `core-list-player` keys:

| Key | Default |
|---|---|
| `ad_clips_per_podcast_segment` | 12 |
| `ad_clips_per_free_tier_ad_break` | 6 |
| `ad_clip_lead_time_ms` | 200 |
| `narration_clip_lead_time_ms` | 3000 |
| `audio_url_clip_lead_time_ms` | 3000 |
| `audio_content_clip_lead_time_ms` | 3000 |
| `video_content_clip_lead_time_ms` | 2000 |
| `use_fake_podcast_ads` | (bool) |

The registration passes `16` (length of `core-list-player`) and `31` (length of `ad_clips_per_free_tier_ad_break`) alongside the default, i.e. it is a typed `registerInt(section, key, default)`.

**Slot fetching.** Case 47 (`slot_fetching_turned_off`) proves the model has an off-state for slot fetching, and the library speaks the ads services itself: `spotify.ads.esperanto.proto.SlotSettings`, `GetSlotSettings(Response)`, `GetAllSlotSettings(Request)`, `GetSlots(Request/Response)`, `ad_slots.proto`, plus fields `enabled_slots` / `active_slots` in its embedded descriptors. Runtime strings confirm the pipeline is native: `core-ad-config-requester`, `core-ad-fetcher`, `ad-fetcher-enqueue-ads`, `fetching_ads_throttled`, `early_ad_fetching_hook`, `late_ad_fetching_hook`, `ad_slot_disabled`, `slot_has_active_ad`, `slot_does_not_exist`, `always_refill_preroll_slot`, `always_refill_stream_slot`.

### Verdict

- **No single gate to flip.** The player has a state/reason vocabulary and takes its ad policy from the ad config it fetches from the ads service. Nothing next to these strings tests "free tier" and branches.
- **Slot fetching is the closest real switch.** Emptying `enabled_slots`, or forcing slot fetching off, is the change that maps to "no ad to play". It means intercepting an esperanto/protobuf exchange inside the library — no symbol, no JNI identity, no string anchor beyond the reason name.
- **Clips-per-break has two ends, and only one is worth patching.** The local default is 6, but the measured break played 2 clips, so the effective value came from the server. Rewriting the *default* (`mov w5, #6` in the registration function) changes nothing live, because the server value overwrites it. The other end is the store after the lookup: the coerced int is written straight into the break-config object, so zeroing it there covers the server value too. That is the patch that was applied below.
- **Still no Java surface.** None of `enabled_slots`, `ad_clips_per_free_tier_ad_break`, `slot_fetching_turned_off`, `core-ad-config-requester`, `FreeTierQueue`/`FreeTierSequence` exists in the dex. Java has read-only models (`AdBreakContext`, `AdBreakStatus`) and free-tier UI flags only.
- **Any patch is per-build binary work**, not a semantic resolver: a native hook (LSPosed-only) or an offline `.so` patch in a repacked APK. Both sit outside this module's install model. One such patch (free-tier clips per break → 0) was applied and loaded on device — see Applied Patch — but its audible effect was not observed, so it is not a claim of working ad removal.

### Corrections this forces

- "The library references no `com/spotify/ads/*` class at all" is true but incomplete: it does not call Java ad classes because it speaks the ads **protos** itself. The observability conclusion stands; the stated reason does not.
- "The Java product-state reader has nothing to gate" was too strong: `last_product_state_ads_enabled` exists as a native proto field, so product state does reach the player. It is not proven to gate scheduling, and L1 was never exercised on device — L1 is **untested**, not dead.

## Applied Patch (2026-10-30, build 9.1.94.1929, device SM-S938B)

One instruction in `liborbit-jni-spotify.so`, at `0x12895d4` inside `.text` (file offset == vaddr for the first `LOAD`):

```
str w0, [sp, #0x11c]   ; B9011FE0  →  str wzr, [sp, #0x11c]   ; B9011FFF
```

### Why this store and not the registration default

The config chain in the player is: `registerInt("core-list-player", "ad_clips_per_free_tier_ad_break", 6)` at `0x1288f54` (`0x15d42bc` materialises the key), then a typed get at `0x12895c0`–`0x12895cc` (`0x14706b4`, key `0x15d42bc`, length 31), whose result in `w0` is stored here. The two clip counts are then copied into the break-config object at `0x1289cc0`:

```
0x1289cb4  ldr w10, [sp, #0xe4]     ; ad_clips_per_podcast_segment  (length 28, default 12)
0x1289cb8  ldr w9,  [sp, #0x11c]    ; ad_clips_per_free_tier_ad_break (length 31, default 6)
0x1289cc0  stp w10, w9, [x20, #0x18]  ; fields 0x18 and 0x1c of the config object
```

Zeroing `[sp,#0x11c]` therefore zeroes field `0x1c` — the free-tier value — *after* the config lookup, so a server-supplied override is zeroed too, not just the default. The podcast-segment store (`0x12895bc`, field `0x18`) was left alone; it looks similar at a glance and is the easy mis-read here.

### Where the edit has to land

The library is **not extracted** on this install: there is no `EXTRACT_NATIVE_LIBS` flag and `lib/arm64/` under the code path is empty. `split_config.arm64_v8a.apk` holds the `.so` stored (uncompressed) with its data starting at apk offset `0x430000`, page aligned, and the loader mmaps it from there. So the patch is 4 bytes at **apk offset `0x16B95D4`** = `0x430000 + 0x12895d4`, and the count of differing bytes in the whole 33.4 MB APK is exactly 1.

The APK no longer verifies against its signature, so it cannot be installed as-is. What was tested is the root route: bind-mount the patched split over the installed one, force-stop, relaunch. Shipping it to non-root users means a repacked APK — the LSPatch arm of that question was explicitly deferred and has not been assessed.

### Device evidence

| Check | Result |
|---|---|
| Mounted split is what the app maps | `/proc/<pid>/maps` shows `split_config.arm64_v8a.apk` mapped `r-xp` at pgoff `0x430000` |
| Patched instruction is live in the process | `/proc/<pid>/mem` at `base+0x12895d4` reads `ff1f 01b9` (`str wzr, [sp, #0x11c]`) |
| App health on the patched library | Media session `PLAYING`, position advancing, track changes across a queue (Dream On → Cinderella Man → River → The King and I), no tombstone, no FATAL, no `linker` error |
| Survives a process restart | After a force-stop and cold start, a new pid maps the split at pgoff `0x430000` and `base+0x12895d4` again reads `ff1f 01b9` |
| Audible effect on an ad break | **Not observed.** No break in ~35 minutes of patched playback, including ~4 minutes with the module's ad hooks off |

What was tried to force a break, and why it did not work:

- `ad-state-storage.bnk` (protobuf, per user under `files/settings/Users/*-user/`) carries `elapsed_stream_time`, `ad_break_time`, `last_ad_break_stream_time`, `unix_epoch_of_last_impression`, `triggered_ad_duration`. Rewriting `last_ad_break_stream_time` to `0` does not make a break due: the player rewrites the file with `elapsed_stream_time = 0` on restart, so the elapsed-since-break delta resets with it.
- Setting `pref_block_ads=false` in the module (its `GetAds` stub no longer answers empty) and restarting on the patched build still produced no break in ~4 minutes, so the module's own stub is not the thing withholding one — this account simply did not schedule a break inside the test windows.

So the patch proves three things and not a fourth. It is applied (one differing byte in the 33.4 MB APK), it is loaded and live in the process, and it leaves normal playback untouched across a cold start. It is **not** proven to silence a break, because no break occurred; the zero-clip behaviour is read off the code path (`0x12895d4` → field `0x1c` → the clip count the break is built from), not watched.

### Re-applying it

Offsets are per build. On this build, from a copy of the installed split:

```
# split_config.arm64_v8a.apk, .so data at 0x430000
printf '\xff\x1f\x01\xb9' | dd of=split_patched.apk bs=1 seek=$((0x430000 + 0x12895d4)) conv=notrunc
su -c 'mount -o bind /data/local/tmp/split_patched.apk \
  /data/app/*/com.spotify.music-*/split_config.arm64_v8a.apk'
```

A rebuild of Spotify replaces the split and the mount must be redone, with the offsets re-derived. `umount` on that path (or a reboot) reverts to the stock library. The test device was left mounted on the patched split.

## Options If Zero Ad Time Is Required

| Option | Reachable from this module? | Evidence | Cost |
|---|---|---|---|
| Premium | n/a | account-level | — |
| Empty `enabled_slots` / slot fetching off inside the native ad manager | No | case 47, embedded ad protos | native hook or `.so` patch, per build |
| Force clips-per-break to 0 at the config store | Not from the module; applied as an offline APK patch | store at `0x12895d4` → field `0x1c` of the break config | one 4-byte edit, see Applied Patch |
| Interrupt the break with a player command | Untested | `KEYCODE_MEDIA_NEXT` ignored; no Java skip API (`notifySkipAd` is Comscore) | one device test |
| Block the ad host at OS level | Untested | Java DNS hook proved only that native resolves separately | one DNS/VPN rule |
| Silence the break | Yes | `is_advertisement` / `spotify:ad:` signal exists in Java | mute + restore |

Native hooking needs the libxposed C++ API (`META-INF/xposed/native_init.list` → `native_init` → `on_library_loaded` → `hook_func(addr, replace, backup)`), which only LSPosed implements — LSPatch never calls `native_init` ([LSPatch#17](https://github.com/LSPosed/LSPatch/issues/17)) — so it is root-only. The dynamic symbol table has 2577 entries, of which only `JNI_OnLoad`, `JNI_OnUnload` and one `SPTTagReader` function are usable, so a hook address has to come from binary matching; that is why none of this belongs in the module.

Coverage limits: one device (SM-S938B), one build (9.1.94.1929), free tier, one region. The disassembly was read-only. The single clips-per-break patch was loaded and played for ~35 minutes (see Applied Patch), but no ad break occurred while it was active, so the zero-clip behaviour is inferred from the code path, not observed.

---

## Update: Playback Stall Discovery & Queue Ad Removal (2026-11-03)

See full documentation in [docs/audio-ad-queue-removal-and-containment.md](../audio-ad-queue-removal-and-containment.md).

### 1. Root Cause of Playback Stalls with Host Blockers
When system-level ad blockers (e.g. `bindhosts`) blackhole Spotify ad CDNs (`adtima-media.zascdn.me`, `ads.zascdn.me`), the native playback pipeline hangs waiting for audio bytes (`BUFFERING(6)`, 0 HAL frames). Because the ad stream never errors out or completes, the player deadlocks.

### 2. Flaw in Previous `AdSkipHook` (`is_advertisement -> false`)
The initial hypothesis of forcing `KEY_IS_ADVERTISEMENT_CHECK` (`esm1.G(ContextTrack)`) to `false` was counterproductive:
- It blinded Spotify into treating ad tracks as ordinary songs.
- The player retained the ad tracks in `nextTracks()` and queued their audio streams.
- The audio engine still attempted to stream the ad from the blocked CDN, triggering the stall.

### 3. Solution: Queue-Level Ad Removal
`AdSkipHook` was completely re-architected to strip ad streams across all queue builder and accessor surfaces:
- **Builders**: `PlayerQueue$Builder#nextTracks(List)`, `AutoValue_PlayerState$Builder#nextTracks(List)`, `SetQueueCommand$Builder#nextTracks(List)`.
- **Accessors**: `AutoValue_PlayerQueue#nextTracks()`, `AutoValue_PlayerState#nextTracks()`.
- **Ad Breaks**: `AutoValue_PlayerState#adBreakContext()` returns `PlayerState.EMPTY.adBreakContext()`.
- **Active Ad Skip**: `AutoValue_PlayerState#track()` detects any active ad track and dispatches `ContextPlayer.SkipNext` over `NativeTransport`.
- **Zero Server Leakage**: All overrides are strictly client-side; no product state or entitlement spoofs leak to backend servers (Rule 8 compliant).
