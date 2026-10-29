# Architecture Patterns: Morphe → SpotXero Translation Guide

This document covers the recurring architectural patterns in Morphe's patches and how each translates to SpotXero's LibXposed + DexKit framework.

---

## 1. Target Resolution

### Morphe Pattern: Hardcoded Obfuscated Names + ABI Snapshots

Every Morphe patch hardcodes obfuscated class/method names and validates them against `.properties` digest files:

```kotlin
// Hardcoded target
val method = mutableClassDefBy("Lp/jb20;").methods.single { it.name == "invoke" }

// ABI snapshot validation
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

### SpotXero Pattern: DexKit Semantic Resolution

Use DexKit to find targets by **semantic invariants** — strings, parameter types, return types, field patterns, class relationships — not obfuscated names:

```kotlin
// Find by strings used in the method
val target = dexKit.findMethod {
    matcher {
        usingStrings("si", "utm_source", "Invalid uri ")
        paramTypes("java.lang.String", "java.lang.String", null, "java.util.Map")
    }
}.singleOrNull() ?: throw RuntimeException("Share URL builder not found")

// Find by named class relationships
val homeStructure = loadClass("com.spotify.casita.v1.resolved.HomeStructure")
val sectionListGetter = dexKit.findMethod {
    matcher {
        declaredClass(homeStructure.name)
        paramCount(0)
        returnType("java.util.List")
    }
}
```

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
    invoke-static/range {v$register .. v$register}, BrandAds->home(List;)List;
    move-result-object v$register
""")
```

### SpotXero Pattern

```kotlin
// Option A: Hook the getter method that returns the list
module.hook(listGetterMethod).after { param ->
    val list = param.result as? List<*> ?: return@after
    param.result = filterAdSections(list)
}

// Option B: Hook the consumer method that processes the list
module.hook(consumerMethod).before { param ->
    val list = param.args[0] as? MutableList<*> ?: return@before
    param.args[0] = filterAdSections(list)
}
```

**Preferred approach**: Hook the **getter** (Option A) when it's on a named/stable class. Hook the **consumer** (Option B) when the getter is deeply obfuscated but the consumer is easier to find.

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
module.hook(flagMethod).after { param ->
    if (isFeatureEnabled()) {
        param.result = overriddenValue
    }
}
```

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
- `InstalledPatches` class with placeholder methods → patch replaces `return false` with `return true`.
- `PatchSettings` uses in-process `SharedPreferences`.
- Settings UI is injected into Spotify's own settings screen via `SettingsBridge`.

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
Throws `PatchException` if anything doesn't match — the patch refuses to apply, user gets an error.

### SpotXero Pattern: Fail Open (Recommended)
```kotlin
// DexKit resolution failure → log and skip the hook, don't crash Spotify
val target = dexKit.findMethod { ... }.singleOrNull()
if (target == null) {
    module.log(Log.WARN, TAG, "Could not resolve target for feature X — skipping")
    return
}

// Runtime hook failure → catch and log, don't propagate to Spotify
module.hook(target).after { param ->
    runCatching {
        // hook logic
    }.onFailure {
        module.log(Log.ERROR, TAG, "Hook failed: ${it.message}")
    }
}
```

---

## 9. Named vs. Obfuscated Classes (Stable Anchors)

Classes that survive R8 obfuscation and can be used as DexKit anchors:

| Category | Examples | Stability |
|---|---|---|
| Protobuf messages | `com.spotify.casita.v1.resolved.HomeStructure`, `com.spotify.scrollsita.v1.Section`, `com.spotify.player.model.ContextTrack`, `com.spotify.share.linkgeneration.api.proto.GenerateUrlResponse` | ✅ Very stable |
| Spotify public APIs | `com.spotify.cosmos.sharedcosmosrouterservice.SharedCosmosRouterService`, `com.spotify.localfiles.mediastore.MediaStoreReader`, `com.spotify.imageloader.localfileimage.LocalFileImageLoader` | ✅ Stable |
| Android framework | `android.net.Uri`, `android.content.SharedPreferences`, `io.reactivex.rxjava3.core.Observable` | ✅ Immutable |
| Proto field names | `shareableUrl_`, `fullUrl_`, `featureTypeCase_`, `items_` | ✅ Stable (proto-generated) |
| Obfuscated (`p.*`) | `p.jb20`, `p.vot`, `p.x7v0`, `p.ja31`, `p.onq`, `p.tkd0`, etc. | ❌ Change every version |
