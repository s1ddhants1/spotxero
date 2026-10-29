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

| Layer | Risk | Mitigation |
|---|---|---|
| L1 Product state | Server-side validation could detect mismatch | Hook only the local reader, not the writer; don't send premium state upstream |
| L2 Ad managers | `disable()` may not exist on all versions | Fail open — if method not found, skip this layer |
| L3 Ad slots | Requires PlayerBridge infrastructure | Defer until bridge is built; L1+L2 likely sufficient |
| L4 Ad skip | Skipping too fast may trigger rate limiting | Add a small delay before skip; only activate if `is_advertisement` is true |
| L5 Visual | Protobuf discriminator values may change | Fail open — show unfiltered list on error |

## Recommendation

**Start with L1 (product state override) alone.** If hooking the product state value reader to return `ads: "0"` and `catalogue: "premium"` works, it eliminates audio ads, visual ads, and upgrade CTAs in one hook. Everything else is defense-in-depth for when L1 alone isn't sufficient.
