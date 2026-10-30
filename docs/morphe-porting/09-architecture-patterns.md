# Architecture Patterns: Morphe → SpotXero Translation Guide

This document covers the recurring architectural patterns in Morphe's patches and how each translates to SpotXero's LibXposed + DexKit framework.

---

## 1. Target Resolution

### Morphe Pattern: Hardcoded Obfuscated Names + ABI Snapshots

One target build, one set of names. `spotifyCompatibility` declares a single `AppTarget` (Spotify `9.1.80.2221`, `versionCode` `145767611`, `arm64-v8a`, APKM, experimental), and every patch hardcodes that build's obfuscated class/method names and validates them against `.properties` digest files. The digest is a semantic snapshot (`NativeSettingsAbi.digest()` — flags, supertypes, annotations, fields/methods with registers, instructions and try blocks, sorted; debug info excluded), not a byte hash, which is what commit `6741682` changed after 2.x's dexlib2 broke 26 of 32 pins:

```kotlin
// Hardcoded target
val method = mutableClassDefBy("Lp/tkd0;").methods.single { it.name == "invoke" }

// ABI snapshot validation (semantic digest, not bytes)
val snapshot = Properties().apply {
    NativeSettingsAbi::class.java.getResourceAsStream("/ads/9.1.80.2221.properties")!!.use(::load)
}
for (type in snapshot.stringPropertyNames()) {
    val definition = classDefByOrNull(type) ?: throw PatchException("ABI changed: missing $type")
    if (NativeSettingsAbi.digest(definition) != snapshot.getProperty(type)) {
        throw PatchException("ABI changed: $type")
    }
}
```

Two things a port should copy from this and one it should not. Copy: the fail-closed refusal, and the fact that the validation describes *structure*, so it catches a changed method body without tripping on unrelated member reordering. Don't copy the hardcoded names themselves — SpotXero resolves semantically (below).

Also note the base dependency: every patch `dependsOn(settingsPatch)`, which merges the extension dex and validates the settings ABI before the feature patch runs. When a patcher reports "settings ABI changed", no feature patch is at fault. SpotXero has no equivalent stage because it has no injected settings screen.

### SpotXero Pattern: DexKit Semantic Resolution

Use DexKit to find targets by **semantic invariants** — strings, parameter types, return types, field patterns, class relationships — not obfuscated names:

```kotlin
// Find by strings used in the method (pseudo-code: SpotXero's matcher DSL differs)
val target = dexKit.findMethod {
    matcher {
        usingStrings("si", "utm_source", "Invalid uri ")
        paramCount(4)
        returnType("android.net.Uri") // SpotXero's builder returns Uri, not Object
    }
}.singleOrNull() ?: throw RuntimeException("Share URL builder not found")

// Find by named class relationships
val cosmosService = loadClass("com.spotify.cosmos.sharedcosmosrouterservice.SharedCosmosRouterService")
module.hookAllConstructors(cosmosService).after { param -> PlayerBridge.onCosmos(param.thisObject) }
```

Morphe's own strings are the best starting point for a SpotXero resolver, because they are the values that must survive: `"slot_id"`, `"ad_break"`, `"partner_name"` (player ad cards), `"si"`, `"utm_source"`, `"Invalid uri "` (share builder), and the `const-string ""` return of the brand-ads getter. Prefer them over guesses derived from obfuscated names.

A second patcher's recorded resolver failures — empty constraint sets matching nothing, filters consumed in instruction order, a method that contains `check-cast` not containing `instance-of`, and SDK signatures that differ from the build's DEX signature — are catalogued with their DexKit analogues in [12-oyasumi-cross-check.md](12-oyasumi-cross-check.md#4-fingerprint-pitfalls--where-they-map-to-dexkit).

**Key invariants to search by** (ordered by stability):
1. Named class references (protobuf classes, Android framework classes)
2. String literals (tracking params, error messages, proto service names)
3. Method signatures (parameter types, return types)
4. Number literals (protobuf field numbers, color constants)
5. Call patterns (which methods call which)

---

## 2. Code Injection

### Morphe Pattern: Smali Instruction Insertion

Morphe inserts raw smali at specific instruction indices:

```kotlin
// Insert after instruction at index `result + 1`
method.addInstructions(result + 1, """
    invoke-static/range {v$register .. v$register}, BrandAds->home(List;)List;
    move-result-object v$register
""".trimIndent())
```

**Common injection patterns**:
- `addInstructions(index, smali)` — insert at a precise index
- `addInstructionsWithLabels(index, smali, ExternalLabel(...))` — insert with a conditional jump to existing code
- `replaceInstruction(index, smali)` — replace one instruction

### Morphe's Second Mechanism: Method Replacement

Not everything is instruction insertion. The brand-ads patch has two mutually exclusive paths: `hookBrandAds` (call `Lp/c8j0;->a()` in place) and `redirectBrandAds`, which **replaces the method body** with a proxy that consults the setting and otherwise invokes the original implementation. That is why the patch carries keep rules for the class (`shrink`/`markAsResourceImplementation`) — a replaced method cannot be removed or renamed without breaking the proxy.

SpotXero's `.before {}`/`.after {}` covers both cases with less machinery: insertion becomes a callback on the producing method, replacement becomes a callback that sets `param.result`. The one thing a callback cannot express identically is replacing a method *only for one caller*, which is exactly what `hookBrandAds` does — a SpotXero port must accept that a getter hook affects every caller, or hook the caller instead.

### SpotXero Pattern: Hook Callbacks

LibXposed hooks intercept entire method calls:

```kotlin
// .before {} — runs before the original method, can modify args or short-circuit
module.hook(method).before { param ->
    param.args[0] = sanitize(param.args[0] as String)
}

// .after {} — runs after the original method, can modify the return value
module.hook(method).after { param ->
    val result = param.result as List<*>
    param.result = filterAds(result)
}

// .intercept {} — full control, must call chain.proceed() to run original
module.hook(method).intercept { chain ->
    if (shouldSkip()) return@intercept null
    val result = chain.proceed()
    transform(result)
}
```

### Translation Table

| Morphe Smali Pattern | SpotXero Hook Equivalent |
|---|---|
| `addInstructions` before a store/return to filter a value | `.after {}` on the method that produces the value |
| `addInstructionsWithLabels` + early return | `.before {}` with `param.result = X` to short-circuit |
| `addInstructions` at method start to intercept args | `.before {}` modifying `param.args` |
| `replaceInstruction` changing a return constant | `.after {}` overriding `param.result` |
| Hooking a constructor at a specific index | `.after {}` on the constructor + field reflection |

---

## 3. Early-Return / Conditional Skip

### Morphe Pattern

```kotlin
method.addInstructionsWithLabels(0, """
    invoke-static/range {p1 .. p1}, HidePodcasts->hideHomeSection(Object;)Z
    move-result v0
    if-eqz v0, :keep     ; jump to original code if we should keep
    const/4 v0, 0x0
    return-object v0      ; return null to skip the section
""".trimIndent(), ExternalLabel("keep", method.getInstruction(0)))
```

### SpotXero Pattern

```kotlin
module.hook(method).before { param ->
    val section = param.args[0]
    if (HidePodcasts.shouldHide(section)) {
        param.result = null  // short-circuit, original method never runs
    }
    // Otherwise: fall through, original method runs normally
}
```

---

## 4. List Filtering / Transformation

### Morphe Pattern

Intercepts a list getter, then filters the returned list:

```kotlin
method.addInstructions(result + 1, """
    invoke-static {p1, p2}, HomeTileBridge->apply(String, ArrayList)ArrayList
    move-result-object p2
""")
```

Morphe never edits a producer of a list in place. It wraps or recomposes: the Home model constructor receives a planned list, and the player is handed a card group composed without ads.

### SpotXero Pattern

```kotlin
// Preferred: recompose at the consumer that hands the list to a renderer
module.hook(cardsGroupBuilder).before { param ->
    param.result = buildGroupWithoutAds(param.args[0])
}

// Fallback: post-filter the producer, only when it has a single consumer
module.hook(listGetterMethod).after { param ->
    val list = param.result as? List<*> ?: return@after
    param.result = filterAdSections(list)
}
```

**What Morphe actually does**, measured against the three display patches: it hooks **the consumer**, not the producer — the brand-ads panel builder for the empty string, the cards gate and the cards-group builder for player ads, the section-model constructor for Home pins. Two reasons, both relevant to a port:

- The getter often has more than one caller, so overriding it changes unrelated UI. The Premium-tab patch deliberately hooks the consumer for this reason.
- The consumer is where a list is handed to a renderer, so composing one list there is cheaper than filtering on every accessor draw.

So resolve from the consumer for any display change, and hook a producer only when it has exactly one meaningful caller — the share-link resolver's distinct-register check is the same principle applied to constructor arguments.

---

## 5. Boolean Flag Override

### Morphe Pattern

```kotlin
method.addInstructions(index + 1, """
    invoke-static/range {v$register .. v$register}, PatchSettings->showPremiumTab(Z)Z
    move-result v$register
""")
```

### SpotXero Pattern

```kotlin
module.hook(flagConsumer).before { param ->
    if (isFeatureEnabled()) {
        param.result = overriddenValue
    }
}
```

Note the target: the insertion happens in the consumer (`Lp/tkd0;->invoke()`), not in the flag getter (`Lp/f4p0;->b()Z`), because the getter has other callers. If a port hooks the getter it silently changes every entitlement reader.

---

## 6. Constructor Interception

### Morphe Pattern

Inserts code at a specific index inside a constructor to transform arguments before they're stored:

```kotlin
constructor.addInstructions(1, """
    invoke-static {p1, p2}, HomeTileBridge->apply(String, ArrayList)ArrayList
    move-result-object p2
""")
```

### SpotXero Pattern

```kotlin
module.hook(constructor).before { param ->
    val sectionId = param.args[0] as? String
    val rows = param.args[1] as? ArrayList<*>
    if (sectionId != null && rows != null) {
        param.args[1] = HomePins.apply(sectionId, rows) ?: rows
    }
}
```

---

## 7. Settings & Preferences

### Morphe Pattern
- **`settingsPatch` is the unnamed base.** It merges `extensions/spotify.mpe` and `extensions/settings.dex`, validates the settings ABI snapshot, appends the Spicetify settings entry to Spotify's settings list, and calls `PatchSettings.initialize` at the start of `SpotifyApplication.onCreate`.
- **Toggles are compiled in, not read at runtime.** Each feature patch calls `enableSetting("brand_ads")`, which writes a `const/4 v0, 0x1` into the extension's `InstalledPatches`, so Spotify's settings screen knows the feature exists. The actual value still comes from preferences at runtime.
- **`PatchSettings` uses in-process `SharedPreferences`** and exposes `restartRequired()`, which compares the saved values with the values Spotify started with — that is what drives the "restart to apply" bar.
- **Settings UI is injected into Spotify's own settings screen** via `SettingsBridge` and the settings ABI snapshot (`NativeSettingsAbi`, 134 classes across seven `.properties` files), because the screen is built from Spotify internals that change between builds.

### SpotXero Pattern
- `PrefKeys` object with string constants.
- `PreferencesManager` wraps `RemotePreferences` (LibXposed cross-process IPC).
- Settings UI is a standalone Compose Activity in the module app.
- Hooks check preferences at runtime: `PreferencesManager.isEnabled(key, default)`.

### New PrefKeys Needed

```kotlin
object PrefKeys {
    // ... existing keys ...
    const val KEY_HIDE_PREMIUM_TAB = "pref_hide_premium_tab"
    const val KEY_HIDE_BRAND_ADS = "pref_hide_brand_ads"
    const val KEY_HIDE_PLAYER_ADS = "pref_hide_player_ads"
    const val KEY_HOME_PINS = "pref_home_pins"
    const val KEY_HIDE_PODCASTS = "pref_hide_podcasts"
    const val KEY_THEME_MODE = "pref_theme_mode" // none, oled, custom
    const val KEY_SHUFFLE_PLUS = "pref_shuffle_plus"
    const val KEY_TRASH_BIN = "pref_trash_bin"
    const val KEY_RANDOM_SONG = "pref_random_song"
}
```

---

## 8. Fail Modes

### Morphe Pattern: Fail Closed
Throws `PatchException` if anything doesn't match — the patch refuses to apply, the user gets an error, and no half-applied state exists. Every resolver checked for this doc behaves that way: the share-link resolver refuses on zero or multiple candidates and on a `move-result-object` that does not follow the conversion, the Premium-tab patch refuses unless the flag call is followed by `MOVE_RESULT`, and the Home-pins patch refuses unless the store is the expected `iput-object`.

### SpotXero Pattern: Also Fail Closed at Resolution

`AGENTS.md` is explicit: *"An unexpected signature, zero matches, or ambiguous resolution must raise a useful exception; never silently skip required behavior or guess."* The earlier "fail open, log and skip" advice in this document contradicts that rule and should not be implemented.

```kotlin
val target = dexKit.findMethod { ... }.singleOrNull()
    ?: throw IllegalStateException("Feature X: share builder not resolved (0 or >1 match)")
```

Where fail-open *is* correct is inside an attached hook, after resolution succeeded: an unexpected argument shape at runtime should leave Spotify's value untouched rather than corrupt it. Fail closed on "I could not find it", fail neutral on "I found it but the data is not what I expected".

---

## 9. Named vs. Obfuscated Classes (Stable Anchors)

Classes that survive R8 obfuscation and can be used as DexKit anchors:

| Category | Examples | Stability |
|---|---|---|
| Protobuf messages | `com.spotify.casita.v1.resolved.HomeStructure`, `com.spotify.scrollsita.v1.Section`, `com.spotify.player.model.ContextTrack`, `com.spotify.share.linkgeneration.api.proto.GenerateUrlResponse` | ✅ Very stable |
| Spotify public APIs | `com.spotify.cosmos.sharedcosmosrouterservice.SharedCosmosRouterService`, `com.spotify.localfiles.mediastore.MediaStoreReader`, `com.spotify.imageloader.localfileimage.LocalFileImageLoader` | ✅ Stable |
| Android framework | `android.net.Uri`, `android.content.SharedPreferences`, `io.reactivex.rxjava3.core.Observable` | ✅ Immutable |
| Proto field names | `shareableUrl_`, `shareId_`, `spotifyUri_`, `fullUrl_`, `items_` | ✅ Stable (proto-generated) |
| Obfuscated (`p.*`) | `p.tkd0` (Premium-tab consumer), `p.f4p0` (its flag getter), `p.joz0` (Home section model), `p.c8j0` (brand-ads panel string), `p.ub21`/`p.aey` (Your Library rows/chips) | ❌ Change every version — never anchor here |
