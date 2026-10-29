# Patch 7: Spicetify Extensions

**Morphe source**: [`ExtensionsPatch.kt`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/extensions/ExtensionsPatch.kt)
**Runtime code**: [`extensions/`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/extensions/extension/src/main/java/app/spicetify/extension/spotify/extensions/) — 15 Java files

**Porting difficulty**: ⚠️ Very Complex — 12+ hook sites, heavy proto/cosmos infrastructure

---

## What Morphe Does

The extensions patch is the largest single patch. It provides four user-facing features, each built on shared infrastructure:

### Infrastructure: PlayerBridge + CosmosRouter
See [Patch 5](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/05-home-pins.md) — the bridge connects to Spotify's `SharedCosmosRouterService` and provides esperanto/cosmos request/response primitives. All four features depend on this.

### Infrastructure: Esperanto Protobuf
[`Esperanto.java`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/extensions/extension/src/main/java/app/spicetify/extension/spotify/extensions/Esperanto.java) reads/writes protobuf messages by field number for:
- `ContextPlayer/GetState` — player state stream
- `ContextPlayer/Play` — play requests
- `Playlist/Get` — playlist contents
- `Metadata/Get` — track/album metadata
- `YourLibrary/Get` — library listing

The patch validates **~60 protobuf field numbers** at patch time (see `esperantoFieldNumbers` map in the source) to ensure the proto schema hasn't changed.

### Feature 1: Trash Bin
- **Hook sites**: T1 (`Lp/b9p0;->apply` at index 1678) and T2 (`Lp/lr5;->apply` at index 633)
- **What it does**: Intercepts context menu item lists for tracks and artists, inserts a "Throw away" menu item via `MenuBridge`.
- **Runtime**: `TrashBin.java` maintains a skip list in preferences. When a trashed track/artist plays, the player skips it.

### Feature 2: Play a Random Song + Random Pill on Home
- **Hook sites**: A (Home chips at index 315 in `Lp/qrl;->invokeSuspend`) and B (chip tap at index 1205 in `Lp/a4v;->invoke`)
- **What it does**: Adds a "Random" pill to Home's filter row via `HomeChipBridge`. Tapping it triggers `RandomSong` which picks a random track from all of Spotify or the user's library.
- **Runtime**: `RandomSong.java` uses the WebApi to fetch random tracks and plays them via the PlayerBridge.

### Feature 3: Shuffle+
- **Hook sites**: N1 (shuffle button constructor at index 62 in `Lp/xkp;-><init>`) and M1 (list menu constructor at index 4 in `Lp/sv70;-><init>`)
- **What it does**: Adds a long-press handler to Now Playing's shuffle button and a "Shuffle+ this playlist" menu item. Uses the PlayerBridge to fetch playlist contents and play them in truly random order.
- **Runtime**: `ShufflePlus.java` fetches all tracks, Fisher-Yates shuffles them, builds a play request.

### Feature 4: Hide Podcasts
- **Hook sites**: P1–P6 across 6 methods
  - P1: Home section filter (`Lp/mz1;->g0`)
  - P2: Home items getter (`Provided->getItemsList`)
  - P3: Home chips filter (`Lp/xqw;->a`)
  - P4: Search entity filter (`Lp/bzw0;->b`)
  - P5: Search chips filter (`Lp/ipy;-><init>`)
  - P6: Your Library chips filter (`Lp/b90;->invoke` at indices 267–268)
- **What it does**: Hides podcast/episode content from Home, Search, and Your Library. Filters section lists, item lists, and chip/filter lists.
- **Runtime**: `HidePodcasts.java` checks section types, entity types, and chip identifiers.

---

## Xposed Porting Strategy

### Porting Priority (Sub-features)

Given the massive scope, port features individually:

| Sub-feature | Hooks | Difficulty | Standalone? |
|---|---|---|---|
| Hide Podcasts (P1–P6) | 6 | Medium | ✅ Yes — only needs DexKit, no bridge |
| Trash Bin (T1–T2) | 2 + bridge | High | ❌ Needs PlayerBridge for skip logic |
| Random Song (A, B) | 2 + bridge | High | ❌ Needs PlayerBridge + WebApi |
| Shuffle+ (N1, M1) | 2 + bridge | High | ❌ Needs PlayerBridge for playlist fetch |

### Hide Podcasts — Most Feasible Sub-feature

**P1 (Home sections)**: Find the method that maps `Section` objects. The `Section` class is `com.spotify.casita.v1.resolved.Section` — named and stable.

```kotlin
// Hook the section mapper to filter podcasts
dexKit.findMethod {
    matcher {
        paramTypes("com.spotify.casita.v1.resolved.Section")
        returnType(null) // object return
    }
}
```

**P2 (Home items)**: Hook `Provided.getItemsList()` — `Provided` is `com.spotify.casita.v1.resolved.Provided`, a named class.

```kotlin
val providedClass = loadClass("com.spotify.casita.v1.resolved.Provided")
val getItemsList = providedClass.getDeclaredMethod("getItemsList")
module.hook(getItemsList).after { param ->
    val items = param.result as? List<*> ?: return@after
    param.result = HidePodcasts.filterHomeItems(items)
}
```

**P3–P6**: Filter chip/filter lists by checking chip identifiers for podcast-related values.

### PlayerBridge — Required for T1, A/B, N1/M1

See [Patch 5](file:///home/s1ddhants1/Projects/spotify/spotxero/docs/morphe-porting/05-home-pins.md) for the bridge porting strategy. Once the bridge is established, the extension features can be ported incrementally.

---

## Key Differences from Morphe

| Aspect | Morphe | SpotXero Port |
|---|---|---|
| Hook resolution | All 12+ sites use hardcoded obfuscated names + instruction indices | DexKit searches by named classes, string markers, method signatures |
| Menu injection | Inserts smali to call `MenuBridge` at exact instruction offsets | Hook the menu builder method and modify the item list in `.before {}` |
| Chip injection | Inserts smali at exact index in `invokeSuspend` | Hook the chip list provider method |
| Proto validation | Validates ~60 field numbers at patch time | Validate at hook time or fail gracefully |
| Extension lifecycle | Started by `PlayerBridge.onCosmos()` after router connects | Started by the Xposed module in `onPackageReady` |

## Risk Assessment
- **High complexity**: 12+ hook sites across deeply obfuscated classes.
- **Hide Podcasts** is the most feasible sub-feature — it only needs list filtering on named classes.
- **Trash Bin, Random Song, Shuffle+** all need the full PlayerBridge + Esperanto infrastructure.
- **Proto field numbers** are the most fragile part — any proto schema change breaks the esperanto parsing.

## Recommendation
1. Port **Hide Podcasts** first as a standalone feature — it has the most user value for the least infrastructure investment.
2. Build the **PlayerBridge** infrastructure as a separate reusable module component.
3. Port **Trash Bin** next (simplest bridge consumer).
4. Port **Shuffle+** and **Random Song** last.
