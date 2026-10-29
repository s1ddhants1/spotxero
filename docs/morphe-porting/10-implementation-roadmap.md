# Implementation Roadmap

A prioritized, phased plan for porting Morphe patches to SpotXero Xposed hooks.

---

## Phase 1: Quick Wins (Est. 1–2 days each)

### 1.1 Clean Sharing Links
- **Doc**: [01-clean-sharing-links.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/01-clean-sharing-links.md)
- **Tasks**:
  - [ ] DexKit resolver: find share URL builder via string fingerprint (`"si"`, `"utm_source"`, `"Invalid uri "`)
  - [ ] DexKit resolver: find ShareResult constructor via `GenerateUrlResponse` proto class
  - [ ] Port `SharingLinks.sanitizeUrl()` to Kotlin utility
  - [ ] Implement `.after {}` hook on URL builder
  - [ ] Implement `.before {}` hook on ShareResult constructor
  - [ ] Wire to existing `PrefKeys.KEY_CLEAN_SHARING`
- **Files to create**:
  - `hooks/sharing/CleanSharingHook.kt`
  - `hooks/sharing/SharingLinksUtil.kt` (sanitize logic)
- **Existing SpotXero code to integrate**: `CleanSharingHookTemplate` already exists — decide whether to use the template or build a cleaner dedicated hook.

### 1.2 Hide Premium Tab
- **Doc**: [02-hide-premium-tab.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/02-hide-premium-tab.md)
- **Tasks**:
  - [ ] DexKit resolver: find Premium tab boolean flag getter
  - [ ] Implement `.after {}` hook to override the flag
  - [ ] Add `PrefKeys.KEY_HIDE_PREMIUM_TAB`
  - [ ] Add toggle in settings UI (with restart-required warning)
- **Files to create**:
  - `hooks/navigation/HidePremiumTabHook.kt`

---

## Phase 2: Ad Blocking (Est. 2–5 days each)

### 2.0 Audio Ad Blocking (Product State + Ad Managers + Ad Skip)
- **Doc**: [11-audio-ad-blocking.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/11-audio-ad-blocking.md)
- **Priority**: 🔴 Highest impact — a single product state hook may eliminate all audio ads
- **Tasks** (layered, implement in order until ads stop):
  - [ ] **L1**: DexKit resolver: find product state value reader (by strings `"ads"`, `"catalogue"`, `"product"`)
  - [ ] **L1**: Hook: override `ads→"0"`, `catalogue→"premium"`, `product→"premium"`, `type→"premium"` at read site
  - [ ] **L1**: Validate override is local-only (not sent upstream)
  - [ ] **L2**: DexKit resolver: find ad manager classes via esperanto service strings (`spotify.ads.esperanto.*`)
  - [ ] **L2**: Hook: intercept `isEnabled()` or call `disable()` on AudioAdManager, BillboardAdManager, etc.
  - [ ] **L3**: DexKit resolver: find `"is_advertisement"` string usage in player state consumer
  - [ ] **L3**: Hook: override ad detection to `false`, or auto-skip ad tracks
  - [ ] **L4** (optional, needs bridge): Cosmos slot manipulation — clear/disable all ad slots
  - [ ] Wire to `PrefKeys.KEY_BLOCK_ADS` (or reuse `KEY_ENABLE_PREMIUM`)
  - [ ] Settings toggle in SpotXero UI
- **Files to create**:
  - `hooks/ads/ProductStateOverrideHook.kt`
  - `hooks/ads/AdManagerHook.kt`
  - `hooks/ads/AdSkipHook.kt`
- **Stop condition**: If L1 alone eliminates ads, L2–L4 become optional defense-in-depth.

### 2.1 Hide Home/Browse Brand Ads
- **Doc**: [03-hide-brand-ads.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/03-hide-brand-ads.md)
- **Tasks**:
  - [ ] DexKit resolver: find `HomeStructure` section list getter
  - [ ] DexKit resolver: find `BrowseStructure` section list getter
  - [ ] Port `BrandAds.filter()` reflection logic to Kotlin
  - [ ] Implement `.after {}` hooks on both getters
  - [ ] Add `PrefKeys.KEY_HIDE_BRAND_ADS` + settings toggle
- **Files to create**:
  - `hooks/ads/BrandAdsHook.kt`
  - `hooks/ads/AdSectionFilter.kt`

### 2.2 Hide Player Ad Cards
- **Doc**: [04-hide-player-ad-cards.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/04-hide-player-ad-cards.md)
- **Tasks**:
  - [ ] DexKit resolver: find `Section.o0()Z` image-ad boolean on `scrollsita.v1.Section`
  - [ ] DexKit resolver: find embedded-ad predicate (takes `ContextTrack`, returns boolean)
  - [ ] Implement `.after {}` hook on image-ad boolean
  - [ ] Implement `.before {}` hook on embedded-ad predicate
  - [ ] Add `PrefKeys.KEY_HIDE_PLAYER_ADS` + settings toggle
- **Files to create**:
  - `hooks/ads/PlayerAdCardsHook.kt`

---

## Phase 3: Content Filtering (Est. 3–5 days)

### 3.1 Hide Podcasts (from Spicetify Extensions)
- **Doc**: [07-spicetify-extensions.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/07-spicetify-extensions.md) — "Hide Podcasts" section
- **Tasks**:
  - [ ] DexKit resolver: find Home section mapper (uses `casita.v1.resolved.Section`)
  - [ ] DexKit resolver: find `Provided.getItemsList()`
  - [ ] DexKit resolver: find Home chips provider
  - [ ] DexKit resolver: find Search entity mapper
  - [ ] DexKit resolver: find Search/Library chips constructors
  - [ ] Port podcast detection logic (section type checking)
  - [ ] Implement 6 hooks (P1–P6)
  - [ ] Add `PrefKeys.KEY_HIDE_PODCASTS` + settings toggle
- **Files to create**:
  - `hooks/content/HidePodcastsHook.kt`
  - `hooks/content/PodcastDetector.kt`

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
- **Tasks**:
  - [ ] DexKit resolver: find section model constructor
  - [ ] Port `HomePins.java` to Kotlin
  - [ ] Implement constructor hook for tile reordering
  - [ ] Build pin picker UI in SpotXero Compose settings
  - [ ] Add `PrefKeys.KEY_HOME_PINS` + settings

### 4.3 Theme Colors
- **Doc**: [06-theme-colors.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/06-theme-colors.md)
- **Tasks**:
  - [ ] Build `Resources.getColor()` interception framework
  - [ ] Port Compose theme hooks (palette, raw colors, surface, scrim, icon tint)
  - [ ] Build theme color configuration UI
  - [ ] Implement OLED-black preset
- **Phases within**: Start with AMOLED black, expand to full theming

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

## Not Ported

### ❌ Local Files from Server
- **Doc**: [08-local-files-server.md](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/08-local-files-server.md)
- **Reason**: Requires AndroidManifest modification, ContentProvider in separate process — fundamentally incompatible with runtime Xposed hooking.

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
