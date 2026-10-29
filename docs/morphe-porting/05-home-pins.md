# Patch 5: Pin Shortcuts on Home

**Morphe source**: [`HomePinsPatch.kt`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/home/HomePinsPatch.kt)
**Runtime code**: [`HomePins.java`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/extensions/extension/src/main/java/app/spicetify/extension/spotify/home/HomePins.java)
**Depends on**: [`playerBridgePatch`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/extensions/ExtensionsPatch.kt#L108-L130) (Cosmos router bridge for Your Library queries)

**Porting difficulty**: ⚠️ Complex — requires the PlayerBridge/Cosmos infrastructure

---

## What Morphe Does

### Patch-Time Hook
Hooks the constructor of the Home "section model" class (`Lp/joz0;`):

```
<init>(String sectionId, ArrayList<rows>, Lp/qt10; ...)
```

At index 1 (right before `iput-object p2, this, Lp/joz0;->a:ArrayList`), inserts:

```smali
invoke-static {p1, p2}, HomeTileBridge->apply(String, ArrayList)ArrayList
move-result-object p2
```

This intercepts the Home shortcuts grid rows **after** Spotify has already cut them to 10 tiles. The bridge receives the section ID and the row list, then:

1. Calls `HomePins.plan(sectionId, rows)` to reorder/insert pinned items.
2. For pins Spotify omitted, injects synthetic `String[] {uri, title, image}` tile placeholders that the native grid renderer picks up.

### Runtime Logic (`HomePins.java`)

A substantial piece of application logic (~284 lines):

- **`initialize(Context)`**: Loads pinned items from `SharedPreferences` (`spicetify_home_pins`).
- **`plan(sectionId, rows)`**: For the shortcuts section, reorders rows to put pinned items first, injects synthetic tiles for missing pins, respects "Show only my pins" mode.
- **`choices(library)`**: Builds the picker list — pins in order, then Home tiles, then library playlists/albums, all alphabetically.
- **`captureAndOrder(ids, titles)`**: Records what Home is showing (for the picker) and produces a pin-first ordering.
- **`arrange(...)`**: The core algorithm — puts pinned items first, adds synthetic tiles for missing pins, enforces Spotify's 10-tile cap.

### PlayerBridge Dependency

The Home pins **picker** needs to read Your Library through the Cosmos router (via `PlayerBridge`) to list playlists and albums for pinning. This is the same bridge the Spicetify extensions use. See the [playerBridgePatch](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/extensions/ExtensionsPatch.kt#L108-L130):

- Hooks `SharedCosmosRouterService.<init>` at the point where `NativeRouter.initializeScheduling()` has been called.
- Hands the service to `PlayerBridge.onCosmos(Object)` which extracts a `CosmosRouter` via reflection.
- The bridge then sends esperanto/cosmos requests through Spotify's own router.

---

## Xposed Porting Strategy

### Phase 1: Hook the Section Model Constructor

```kotlin
// The section model class (joz0) has a constructor taking:
//   (String, ArrayList, <someType>)
// The String is the section ID, the ArrayList contains the grid rows.
// DexKit can find it by:
//   - Constructor with (String, ArrayList, L) signature
//   - The class has a field of type ArrayList
//   - The class is used in Home's grid rendering pipeline

// Better approach: find it by tracing from HomeStructure/Section which are named
dexKit.findMethod {
    matcher {
        name("<init>")
        paramTypes("java.lang.String", "java.util.ArrayList", null)
        // Additional filters: the class should be instantiated from Home's
        // section rendering code
    }
}
```

### Hook Implementation

```kotlin
module.hook(sectionModelConstructor).before { param ->
    val sectionId = param.args[0] as? String ?: return@before
    val rows = param.args[1] as? ArrayList<*> ?: return@before
    // Reorder rows based on pinned items
    param.args[1] = HomePins.plan(sectionId, rows) ?: rows
}
```

### Phase 2: Port the PlayerBridge (for the Picker)

This is the hardest part. The PlayerBridge needs to:

1. **Find `SharedCosmosRouterService`** — this is a named class, so finding it is straightforward.
2. **Hook its constructor** to capture the service instance.
3. **Build a `CosmosRouter` reflective wrapper** — the `CosmosRouter.reflective(service)` method uses reflection to call `resolve(action, uri, body, callback)` on Spotify's router.
4. **Port the esperanto protobuf parsing** — `Esperanto.java` reads/writes protobuf messages to query the player state and Your Library.

**Xposed approach for the CosmosRouter:**

```kotlin
// SharedCosmosRouterService is a named class
val cosmosServiceClass = loadClass(
    "com.spotify.cosmos.sharedcosmosrouterservice.SharedCosmosRouterService"
)

// Hook its constructor
module.hookAllConstructors(cosmosServiceClass).after { param ->
    // param.thisObject is the service instance
    // Extract the router and store it for later use
    cosmosRouterRef.set(CosmosRouterWrapper(param.thisObject))
}
```

### Phase 3: Port HomePins Logic

The `HomePins.java` logic is self-contained Java code with minimal Spotify dependencies — it can be ported to Kotlin almost verbatim. The only Spotify dependency is reading tile fields via reflection (`p.goz0.a`, `p.nnz0.a/b/d`), which needs to be adapted to DexKit-resolved field references.

### Settings UI for Pin Picker

Morphe's picker is a custom sheet/dialog built with Spotify's own UI components (via `SpotifySheet`, `SpotifyStyle`). For SpotXero, this would need to be:
- A Compose dialog in the SpotXero settings app, or
- A floating overlay triggered from within Spotify.

---

## Key Differences from Morphe

| Aspect | Morphe | SpotXero Port |
|---|---|---|
| Section model hook | Hardcoded `Lp/joz0;` constructor | DexKit resolution by constructor signature |
| Tile field access | Hardcoded `p.goz0.a`, `p.nnz0.a/b/d` | Need DexKit or dynamic field resolution |
| Cosmos bridge | Injected at build time into the DEX | Hook `SharedCosmosRouterService` constructor at runtime |
| Picker UI | Built with Spotify's own sheet/style system | SpotXero Compose UI or overlay |
| Preferences | In-process `SharedPreferences` | RemotePreferences (cross-process IPC) |

## Risk Assessment
- **Medium risk** for the section model hook — the constructor signature is fairly distinctive.
- **High risk** for tile field access — `p.goz0` and `p.nnz0` are deeply obfuscated model classes whose field names change every version. Need a resilient resolution strategy (e.g., by field type and position, or by tracing from named protobuf classes).
- **High complexity** for the PlayerBridge — significant infrastructure to port, but the Cosmos service class is named and stable.

## Recommendation
Port the **display hook** (reordering existing tiles) first without the picker. The picker can come later once the PlayerBridge infrastructure is established. A simpler initial approach: let users configure pins via URIs in the SpotXero settings UI, without needing to query Your Library.
