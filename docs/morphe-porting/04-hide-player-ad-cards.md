# Patch 4: Hide Player Ad Cards

**Morphe source**: [`PlayerAdCardsPatch.kt`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/ads/PlayerAdCardsPatch.kt)
**Runtime code**: [`PlayerAdCards.java`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/extensions/extension/src/main/java/app/spicetify/extension/spotify/ads/PlayerAdCards.java)

**Porting difficulty**: ✅ Feasible (medium complexity)

---

## What Morphe Does

### Hook Site 1: Image Brand Ad Mapper
- **Class**: `Lp/ja31;`, method `invoke(Object)`.
- **Pattern**: Finds the call to `Section->o0()Z` (a boolean indicating "is image ad"), injects after the result:
```smali
invoke-static {vN}, PlayerAdCards->showImageBrandAd(Z)Z
move-result vN
```
- **Effect**: When ads are hidden, the boolean is forced to `false`, so the image ad section is skipped in the mapper.

### Hook Site 2: Embedded Ad Predicate
- **Class**: `Lp/onq;`, method `z(ContextTrack)Z`.
- **Pattern**: Validates the first instruction is `iget-object v0, ..., Lp/onq;->b:Object`, then inserts an early-return at index 0:
```smali
invoke-static {}, PlayerAdCards->showEmbeddedAd()Z
move-result v0
if-nez v0, :show    ; jump to original code if enabled
const/4 v0, 0x0
return v0            ; return false → embedded ad is not shown
```
- **Effect**: When ads are hidden, the predicate returns `false` before checking anything, suppressing embedded ad pages in Now Playing.

### Runtime Logic

```java
public static boolean showImageBrandAd(boolean present) {
    return present && !PatchSettings.hidePlayerAdCardsEnabled();
}

public static boolean showEmbeddedAd() {
    return !PatchSettings.hidePlayerAdCardsEnabled();
}
```

---

## Xposed Porting Strategy

### DexKit Resolution

**Target 1 — Image ad mapper:**
```kotlin
// Section.o0() is on com.spotify.scrollsita.v1.Section — a stable named class
val sectionClass = loadClass("com.spotify.scrollsita.v1.Section")

// Find the boolean method o0() on Section
dexKit.findMethod {
    matcher {
        declaredClass(sectionClass.name)
        paramCount(0)
        returnType("boolean")
        name("o0") // may be obfuscated, but the class is named
    }
}

// OR: find the mapper that calls this method — the method on an obfuscated class
// that receives an Object, calls Section.o0(), and maps sections conditionally
```

**Target 2 — Embedded ad predicate:**
```kotlin
// The predicate takes a ContextTrack and returns boolean.
// ContextTrack is com.spotify.player.model.ContextTrack — a stable named class.
val contextTrackClass = loadClass("com.spotify.player.model.ContextTrack")

dexKit.findMethod {
    matcher {
        paramTypes(contextTrackClass.name)
        returnType("boolean")
        // The method checks whether a track is an embedded ad
        // Can narrow down by: the class has an Object field, method reads it first
    }
}
```

### Hook Implementation

```kotlin
// Hook 1: Image brand ad boolean
// Option A: Hook Section.o0() directly
module.hook(sectionIsAdMethod).after { param ->
    if (PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_PLAYER_ADS, false)) {
        if (param.result == true) {
            param.result = false
        }
    }
}

// Option B: Hook the mapper method that calls o0()
module.hook(adMapperInvoke).before { param ->
    // Intercept the entire mapping to suppress ad sections
}

// Hook 2: Embedded ad predicate
module.hook(embeddedAdPredicate).before { param ->
    if (PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_PLAYER_ADS, false)) {
        param.result = false
        return@before // short-circuit
    }
}
```

### Settings Integration
Add `PrefKeys.KEY_HIDE_PLAYER_ADS` and a toggle in SpotXero settings.

---

## Key Differences from Morphe

| Aspect | Morphe | SpotXero Port |
|---|---|---|
| Image ad hook | Injects after `move-result` in the obfuscated mapper | Hook `Section.o0()` directly (stable class) or hook mapper |
| Embedded ad hook | Inserts early-return at index 0 of obfuscated predicate | `.before {}` with `param.result = false` — idiomatic Xposed |
| Version coupling | Hardcoded `Lp/ja31;`, `Lp/onq;`, field references | Hook the stable named `Section` class and `ContextTrack`-taking methods |

## Risk Assessment
- **Low risk for image ads**: `scrollsita.v1.Section` is a named protobuf class.
- **Medium risk for embedded ads**: The predicate method is on an obfuscated class; need a robust DexKit query to find it (e.g., by parameter type `ContextTrack` + return type `boolean` + class shape).
- **Mitigation**: The `ContextTrack` parameter type is a stable anchor. Combine with field-count or method-count filters on the declaring class.
