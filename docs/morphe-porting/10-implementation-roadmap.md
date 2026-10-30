# Implementation Roadmap

A prioritized, phased plan for porting Morphe patches to SpotXero Xposed hooks.

**Baseline before any phase**: Morphe targets exactly one build (Spotify `9.1.80.2221`, `versionCode` `145767611`, `arm64-v8a`, APKM, experimental) and every patch `dependsOn(settingsPatch)`. SpotXero resolves semantically, so it does not inherit the version lock — but it does inherit the obligation to fail closed when a verified anchor is gone, and it must implement its own settings surface (`PrefKeys` + Compose), because there is no injected settings screen to lean on.

**Shipped as of 2026-10-30** (verified against `HookRegistry`): eight hooks — clean sharing, hide Premium tab, block ad fetching, ad-track detection, hide Home/Browse brand ads, hide player ad cards, hide podcasts, AMOLED theme. Phases 1–3 are landed apart from the deviations recorded inline below; Phase 4 is open.

---

## Phase 1: Quick Wins (Est. 1–2 days each)

### 1.1 Clean Sharing Links — ✅ shipped
- **Doc**: [01-clean-sharing-links.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/01-clean-sharing-links.md)
- **Tasks**:
  - [x] Resolver: share URL builder by `String, String, L, Map` + strings `si`/`utm_source`/`Invalid uri `, with the same `isShareUrlBuilder` check applied to a cached hit and a fresh search
  - [x] Resolver: the four zero-arg `String` getters on `GenerateUrlResponse` (`shareableUrl_`, `shareId_`, `spotifyUri_`, `fullUrl_`)
  - [x] Port `sanitizeUrl` to Kotlin (`SharingLinksUtil.sanitizeResult`, accepts a bare `String` or the builder's two-`Object` pair)
  - [x] Hook the builder `.after {}` — args are immutable here, so clean the result
  - [x] Cover server links by sanitising the four response getter results, not the constructor `.before {}`
  - [x] Wire to `PrefKeys.KEY_CLEAN_SHARING`
- **Files**: `hooks/sharing/CleanSharingHook.kt`, `hooks/sharing/SharingLinksUtil.kt`

### 1.2 Hide Premium Tab — ✅ shipped, one deviation
- **Doc**: [02-hide-premium-tab.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/02-hide-premium-tab.md)
- **Tasks**:
  - [x] Resolver: the second of three boolean getters on the record identified by `premium_tab_enabled`/`premium_tab_shows_on_tablets`/`premium_tab_title` plus its `List` getter; the record shape is validated, no instruction index is used
  - [x] Intercept the flag getter and return `false` (`deoptimize` first)
  - [ ] Hook the consumer instead — the getter has other callers, so this stays the one deviation from the doc
  - [x] `PrefKeys.KEY_HIDE_PREMIUM_TAB` + dashboard toggle (restart required — the tab set is read at startup)
- **Files**: `hooks/navigation/HidePremiumTabHook.kt`

---

## Phase 2: Ad Blocking (Est. 2–5 days each)

### 2.0 Audio Ad Blocking (fetch only — audio is not removable from Java)
- **Doc**: [11-audio-ad-blocking.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/11-audio-ad-blocking.md)
- **Priority**: 🟠 Ship the measured half (ad fetching, ad UI). On 9.1.x the break is scheduled and played by `liborbit-jni-spotify.so`; the disassembly pass found no gate to flip, only an ad-config chain (`enabled_slots`, `ad_clips_per_free_tier_ad_break`) that lives natively.
- **Tasks**:
  - [x] `AdResponseHook`: answer `GetAds` with an empty `GetAdsResponse` — measured stable, called per break, does not stop one
  - [x] `AdSkipHook`: force `is_advertisement(ContextTrack)` to `false` — measured cosmetic (UI/analytics only)
  - [x] Settings and strings: say "blocks ad fetching", never "no ads"
  - [ ] Optional, do not ship as an ad blocker: L1 product-state override at the read site. Native carries a product-state ads proto field, so L1 is untested rather than closed.
  - [x] Out of module: offline `.so` patch forcing free-tier clips per break to 0 at the config store (`0x12895d4` → field `0x1c`). Applied to the split APK, loaded and live in the process, ~35 min of normal playback across a cold start; no break occurred while active, so the effect is unobserved.
- **Files**: `hooks/ads/AdResponseHook.kt`, `hooks/ads/AdSkipHook.kt` (both exist)
- **Stop condition remains external**: zero ad time needs a native `.so` patch or a root-only native hook — off this roadmap. The applied clips-per-break patch reaches it only by installing a modified APK (root bind mount was the test method); repacked delivery, and whether LSPatch can carry it, are unevaluated. Evidence is in the doc's Applied Patch and Options sections.

### 2.1 Hide Home/Browse Brand Ads — ✅ shipped by a different mechanism
- **Doc**: [03-hide-brand-ads.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/03-hide-brand-ads.md)
- **Tasks**:
  - [x] Resolver: the single zero-arg `List` getter on `casita.v1.resolved.HomeStructure` and on `browsita.v1.resolved.BrowseStructure`
  - [x] Filter the returned lists: drop home sections whose `featureTypeCase_` is `IMAGE_BRAND_AD_FIELD_NUMBER`/`VIDEO_BRAND_AD_FIELD_NUMBER`, browse sections whose `sectionTypeCase_` is `BRAND_ADS_FIELD_NUMBER`
  - [x] Add `PrefKeys.KEY_HIDE_BRAND_ADS` + dashboard toggle
- **Deviation**: the shipped surface is a section-type filter (`AdSectionFilter`), not the single `const-string ""` getter doc 03 describes. If a field-number constant does not resolve, the list is passed through unchanged rather than failing the hook.
- **Files**: `hooks/ads/BrandAdsHook.kt`, `hooks/ads/AdSectionFilter.kt`

### 2.2 Hide Player Ad Cards — ✅ shipped by a different mechanism
- **Doc**: [04-hide-player-ad-cards.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/04-hide-player-ad-cards.md)
- **Tasks**:
  - [x] Resolver: the no-arg boolean getter on `scrollsita.v1.Section` whose body uses `IMAGE_BRAND_AD_FIELD_NUMBER`
  - [x] Intercept that predicate to `false` so the image-ad card is never composed as an ad (`deoptimize` first)
  - [ ] Cards-group builder and non-empty-cards gate — not hooked; the shipped surface is the section predicate, not the `slot_id`/`ad_break`/`partner_name` metadata method
  - [x] Add `PrefKeys.KEY_HIDE_PLAYER_ADS` + dashboard toggle
- **Files**: `hooks/ads/PlayerAdCardsHook.kt`
- **Verification**: play a track through a real ad break. Cards must be absent and playback must not stall, skip or stutter.

---

## Phase 3: Content Filtering (Est. 3–5 days)

### 3.1 Hide Podcasts (from Spicetify Extensions) — 🟡 2 of 6 sites shipped
- **Doc**: [07-spicetify-extensions.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/07-spicetify-extensions.md) — "Hide Podcasts" section
- **Tasks**:
  - [x] DexKit resolver: `casita.v1.resolved.Provided` items list getter
  - [x] DexKit resolver: `casita.v1.resolved.HomeStructure` section list getter
  - [x] Port podcast detection logic (`PodcastDetector`: item and section type checks)
  - [x] Implement the home-sections and provided-items hooks
  - [ ] Implement the remaining 4 sites (home chips provider, search entity mapper, search/library chips constructors)
  - [x] Add `PrefKeys.KEY_HIDE_PODCASTS` + dashboard toggle
- **Files**: `hooks/content/HidePodcastsHook.kt`, `hooks/content/PodcastDetector.kt`

---

## Phase 4: Complex Features (Est. 1–2 weeks each)

### 4.1 PlayerBridge Infrastructure
- **Prerequisite** for: Home Pins picker, Trash Bin, Shuffle+, Random Song
- **Tasks**:
  - [ ] Hook `SharedCosmosRouterService.<init>` to capture the router
  - [ ] Port `CosmosRouter.reflective()` — reflection wrapper for cosmos requests
  - [ ] Port `Esperanto.java` — protobuf read/write by field number
  - [ ] Port `PlayerBridge.java` — request/response, state stream, threading
  - [ ] Validate proto field numbers at hook time
- **Files to create**:
  - `hooks/bridge/CosmosRouterWrapper.kt`
  - `hooks/bridge/PlayerBridge.kt`
  - `hooks/bridge/EsperantoCodec.kt`

### 4.2 Home Pins
- **Doc**: [05-home-pins.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/05-home-pins.md)
- **Depends on**: PlayerBridge (for the picker; display-only works without it)
- **Why the model, not the grid**: `d966493` moved the port to the section model because the list is built for the Compose grid as well, so hooking the model gives the pinned order to both renderers. Hooking a renderer would need redoing per grid implementation.
- **Tasks**:
  - [ ] Resolver: section model constructor `(String, ArrayList, L)` whose argument store is `iput-object` of that list into a field (the patch's actual guard)
  - [ ] Port `HomePins.java` to Kotlin, keeping `String[]{uri, title, image}` as the placeholder element shape
  - [ ] Hook the constructor `.before {}`, replacing args[1] with the planned order
  - [ ] Cache the pin list instead of reading RemotePreferences on every render
  - [ ] Build pin picker UI in SpotXero Compose settings
  - [ ] Add `PrefKeys.KEY_HOME_PINS` + settings

### 4.3 Theme Colors — 🟡 AMOLED only
- **Doc**: [06-theme-colors.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/06-theme-colors.md)
- **Tasks**:
  - [x] Build `Resources.getColor()` interception framework (both overloads; Spotify's eight dark greys map to black)
  - [x] Implement OLED-black preset (`HookRegistry` → `SpotifyAmoledHook`, default off)
  - [ ] Port Compose theme hooks (palette, raw colors, surface, scrim, icon tint)
  - [ ] Build theme color configuration UI
- **Files**: `hooks/theme/SpotifyAmoledHook.kt`

---

## Phase 5: Bridge-Dependent Features (2+ weeks)

### 5.1 Trash Bin
- **Depends on**: PlayerBridge, menu hooks
- **Tasks**: Port TrashBin logic, menu item injection, skip-list management

### 5.2 Shuffle+
- **Depends on**: PlayerBridge, menu hooks, playlist fetch
- **Tasks**: Port ShufflePlus logic, long-press handler, playlist menu item

### 5.3 Random Song
- **Depends on**: PlayerBridge, WebApi, Home chip injection
- **Tasks**: Port RandomSong logic, chip injection, WebApi integration

---

## Candidate Feature: Disable Smart Shuffle Default

- **Source**: [`DisableSmartShuffleDefaultPatch.kt`](https://github.com/dunecache/oyasumi-patches/blob/dev/patches/src/main/kotlin/app/spotify/patches/shuffle/DisableSmartShuffleDefaultPatch.kt) in oyasumi-patches, Spotify `9.1.90.2270`; see [12-oyasumi-cross-check.md](12-oyasumi-cross-check.md)
- **Behavior**: starting a playlist or Liked Songs does not select Smart Shuffle, so playback follows the normal queue order
- **Surface**: two no-arg boolean getters on the `android-system-smart-shuffle` remote-config model — liked-songs default and playlist default. Each delegates to a parent via a one-arg getter and otherwise returns a backing `Z` field
- **Why it ports**: no bridge, no proto, no UI — two boolean reads. Morphe's version is an early `return false` at index 0 of each getter
- **Resolver shape**: the class registered once from the smart-shuffle config model, with a no-arg `Z` getter that reads a boolean field whose name is referenced exactly once inside the model's `models()` method. Refuse on ambiguous matches rather than hooking every boolean getter in the class
- **Unknowns**: the `9.1.90.2270` class names do not exist in `9.1.80.2221`, so the shape must be re-derived; oyasumi records that the patch only changes parsed remote-config defaults and that server-side or hard defaults may still enable Smart Shuffle
- **Priority**: after Phase 4 — it is a playback-behavior change, so it needs sustained-playback verification, not just a UI check

---

## Not Ported

### ❌ Local Files from Server
- **Doc**: [08-local-files-server.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/08-local-files-server.md)
- **Reason**: Requires AndroidManifest modification, ContentProvider in separate process — fundamentally incompatible with runtime Xposed hooking.
- **Additional constraint from `dd35dae`**: even patchers that can rewrite the manifest only get it on install modes that register components; a root mount install keeps Spotify's stock manifest. Applies to any port that needs a component, an authority or a process name.

---

## Verified Cross-Patch Constraints

- **Hook the consumer, not the getter.** Every display patch in the repo does this (brand-ads panel, cards group, section model, Premium-tab consumer). A getter hook is only safe when the getter has one meaningful caller.
- **Strings and proto classes are the anchors that survive.** `"slot_id"`/`"ad_break"`/`"partner_name"`, `"si"`/`"utm_source"`/`"Invalid uri "`, `const-string ""` for the brand-ads getter, `GenerateUrlResponse` field names for server links.
- **Guard instruction shape, never an index.** `b035aba` exists because one instruction moved between the flag result and its branch; `d966493` exists because the grid renderer changed. Resolve by opcode sequence and by the field store.
- **Fail closed on resolution, fail neutral on runtime data.** Zero or ambiguous matches must throw; an unexpected argument shape inside an attached hook must leave Spotify's value alone.
- **Premium unlock is not on this roadmap.** Issues #1, #4 and #5 close it: the catalogue gate is Play Integrity attestation bound to the signing certificate, and every client-side spoof was rejected.

---

## Shared Infrastructure Needed

| Component | Used By | Priority |
|---|---|---|
| DexKit resolver framework + signature cache | All hooks | 🔴 Exists already |
| New PrefKeys + settings toggles | All new features | 🔴 Phase 1 |
| `Resources.getColor()` interception | Theme | 🟡 Phase 4 |
| PlayerBridge + CosmosRouter | Pins, Extensions | 🟡 Phase 4 |
| Esperanto protobuf codec | Extensions | 🟡 Phase 4 |
| Menu item injection framework | Trash Bin, Shuffle+ | 🟢 Phase 5 |

---

## File Structure (Proposed)

```
app/src/main/kotlin/app/spotxero/
├── hooks/
│   ├── sharing/
│   │   ├── CleanSharingHook.kt
│   │   └── SharingLinksUtil.kt
│   ├── navigation/
│   │   └── HidePremiumTabHook.kt
│   ├── ads/
│   │   ├── BrandAdsHook.kt
│   │   ├── PlayerAdCardsHook.kt
│   │   └── AdSectionFilter.kt
│   ├── content/
│   │   ├── HidePodcastsHook.kt
│   │   └── PodcastDetector.kt
│   ├── home/
│   │   ├── HomePinsHook.kt
│   │   └── HomePins.kt
│   ├── theme/
│   │   ├── ThemeHook.kt
│   │   ├── ComposeThemeHook.kt
│   │   └── ColorInterceptor.kt
│   ├── bridge/
│   │   ├── CosmosRouterWrapper.kt
│   │   ├── PlayerBridge.kt
│   │   └── EsperantoCodec.kt
│   └── extensions/
│       ├── TrashBinHook.kt
│       ├── ShufflePlusHook.kt
│       └── RandomSongHook.kt
└── core/
    ├── HookRegistry.kt          # Central hook registration
    └── DexKitResolver.kt        # Unified resolver for all hooks
```
