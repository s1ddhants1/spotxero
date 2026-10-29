# Patch 3: Hide Home and Browse Brand Ads

**Morphe source**: [`BrandAdsPatch.kt`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/ads/BrandAdsPatch.kt)
**Runtime code**: [`BrandAds.java`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/extensions/extension/src/main/java/app/spicetify/extension/spotify/ads/BrandAds.java)

**Porting difficulty**: ✅ Feasible (medium complexity)

---

## What Morphe Does

### Three Hook Sites

Each hooks a method in an obfuscated class at the point where it reads a section list:

1. **Home (via `Lp/jb20;->invoke()`)**: Calls `HomeStructure->p()` to get the section list. Inserts `BrandAds.home(list)` after the result.
2. **Home (via `Lp/vot;->g()`)**: Same getter `HomeStructure->p()`. Inserts `BrandAds.home(list)`.
3. **Browse (via `Lp/x7v0;->a()`)**: Calls `BrowseStructure->o()` to get browse sections. Inserts `BrandAds.browse(list)`.

Pattern: finds the single call to the getter, verifies the next instruction is `move-result-object`, then injects a static filter call right after.

### Runtime Logic

`BrandAds.java` filters sections by their protobuf discriminator field:

```java
private static List<?> filter(List<?> sections, String type, String discriminator, int first, int second) {
    // Checks PatchSettings.hideBrandAdsEnabled()
    // Gets the featureTypeCase_ or sectionTypeCase_ field via reflection
    // Removes sections where the discriminator == first or second (ad section types)
    // Returns filtered copy — does NOT mutate the original
}
```

- **Home**: `com.spotify.casita.v1.resolved.Section`, field `featureTypeCase_`, ad types `20` and `21`
- **Browse**: `com.spotify.browsita.v1.resolved.Section`, field `sectionTypeCase_`, ad type `6`

---

## Xposed Porting Strategy

### DexKit Resolution

The key stable identifiers are the **non-obfuscated protobuf class names** and their **getter methods**:

```kotlin
// Target: Methods that call HomeStructure.p() to get the section list
// HomeStructure is com.spotify.casita.v1.resolved.HomeStructure — stable name
val homeStructureClass = loadClass("com.spotify.casita.v1.resolved.HomeStructure")

// Find the getter that returns the section list (List type, no params)
// There may be multiple getters — we want the one returning List<Section>
dexKit.findMethod {
    matcher {
        declaredClass(homeStructureClass.name)
        paramCount(0)
        returnType("java.util.List") // or the internal list type
    }
}

// Similarly for BrowseStructure
val browseStructureClass = loadClass("com.spotify.browsita.v1.resolved.BrowseStructure")
```

**Alternative approach — hook the section classes directly:**
```kotlin
// The Section classes are named and stable. We can hook wherever they appear in lists.
// Find methods that consume List<Section> and filter them.
val homeSectionClass = loadClass("com.spotify.casita.v1.resolved.Section")
val browseSectionClass = loadClass("com.spotify.browsita.v1.resolved.Section")
```

### Hook Implementation

```kotlin
// Approach: Hook the getters on HomeStructure/BrowseStructure that return the section list
module.hook(homeSectionListGetter).after { param ->
    if (!PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_BRAND_ADS, false)) return@after
    val sections = param.result as? MutableList<*> ?: return@after
    param.result = filterAdSections(sections, "featureTypeCase_", 20, 21)
}

module.hook(browseSectionListGetter).after { param ->
    if (!PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_BRAND_ADS, false)) return@after
    val sections = param.result as? MutableList<*> ?: return@after
    param.result = filterAdSections(sections, "sectionTypeCase_", 6)
}
```

### Filter Logic (Port from `BrandAds.java`)

```kotlin
private fun filterAdSections(sections: List<*>, discriminatorField: String, vararg adTypes: Int): List<*> {
    if (sections.isEmpty()) return sections
    return try {
        val sectionClass = sections[0]!!::class.java
        val field = sectionClass.getDeclaredField(discriminatorField).apply { isAccessible = true }
        sections.filter { section ->
            section != null && field.getInt(section) !in adTypes
        }
    } catch (e: ReflectiveOperationException) {
        sections // fail open
    }
}
```

### Settings Integration
Add `PrefKeys.KEY_HIDE_BRAND_ADS` and a toggle in SpotXero settings.

---

## Key Differences from Morphe

| Aspect | Morphe | SpotXero Port |
|---|---|---|
| Target resolution | Hardcoded obfuscated consumer classes (`jb20`, `vot`, `x7v0`) | Hook the **getter** methods on the stable named protobuf classes instead |
| Injection point | After `move-result-object` of the getter call | `.after {}` on the getter itself — cleaner and version-resilient |
| Filter logic | Identical — runtime reflection on discriminator field | Identical approach, ported to Kotlin |
| Fail mode | Throws `PatchException` if shape changed | Fails open (returns unfiltered list) — more resilient |

## Risk Assessment
- **Low risk**: `HomeStructure` and `BrowseStructure` are named protobuf classes — very stable.
- **Medium risk**: The discriminator field numbers (`20`, `21`, `6`) are protobuf `oneof` case values. These are defined in the `.proto` schema and rarely change, but a proto schema update could renumber them.
- **Mitigation**: Rather than hardcoding case numbers, inspect the ad section's contents (e.g., check for known ad-related field names or types) for a more resilient filter.
