# Patch 2: Hide Premium Tab

**Morphe source**: [`PremiumTabPatch.kt`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/navigation/PremiumTabPatch.kt)
**Runtime code**: Handled inline by [`PatchSettings.showPremiumTab()`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/extensions/extension/src/main/java/app/spicetify/extension/spotify/settings/PatchSettings.java#L76-L78)

**Porting difficulty**: ✅ Easy

---

## What Morphe Does

### Target
Class `Lp/tkd0;`, method `invoke()` (no params, returns `Object`).

### Resolution
Scans the method's instructions for a call to `Lp/f4p0;->b()Z` — a boolean getter that returns whether Spotify's Premium navigation tab is enabled. This is the only such call in the method. After the `move-result`, injects:

```smali
invoke-static {vN}, PatchSettings->showPremiumTab(Z)Z
move-result vN
```

### Runtime Logic
```java
public static boolean showPremiumTab(boolean spotifyEnabled) {
    return spotifyEnabled && !hidePremiumTabEnabled();
}
```
When the setting is on, this overrides the boolean to `false`, hiding the tab.

---

## Xposed Porting Strategy

### DexKit Resolution

The Premium tab flag is a boolean method that controls navigation tab visibility. The key semantic invariant is that it's a parameterless boolean method on a class involved in navigation bar construction.

**Approach 1 — Find the flag method directly:**
```kotlin
// The flag getter (f4p0.b()) is on a class that determines Premium tab visibility.
// In the caller (tkd0.invoke()), the result of this boolean determines whether
// the Premium tab model is included in the navigation items list.
// We can find the caller by looking for the navigation builder pattern.
dexKit.findMethod {
    matcher {
        // The invoke() method that builds navigation tabs
        name("invoke")
        paramCount(0)
        returnType("java.lang.Object")
        // Look for it calling a boolean method and then conditionally
        // building a navigation item — this needs refinement with actual
        // string/reference markers from the navigation tab construction
    }
}
```

**Approach 2 — Hook the boolean flag itself:**
```kotlin
// Find the class that provides the Premium tab enabled flag.
// This is simpler — just hook the boolean getter and override its return.
// The challenge is identifying it without obfuscated names.
// Possible markers: the class may reference "premium" strings, or be used
// in navigation bar construction alongside other tab type identifiers.
```

**Approach 3 — Find via navigation model strings:**
If the navigation construction uses identifiable string constants (like tab IDs, content descriptions, or resource references), those can be used as anchors to find the surrounding code and the flag getter.

### Hook Implementation

```kotlin
// Option A: Hook the flag getter directly
module.hook(premiumTabFlagMethod).after { param ->
    if (PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_PREMIUM_TAB, false)) {
        param.result = false
    }
}

// Option B: Hook the invoke() method that builds navigation items
module.hook(navigationBuilderInvoke).after { param ->
    if (PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_PREMIUM_TAB, false)) {
        // Filter the Premium tab item from the returned list
        val items = param.result as? MutableList<*> ?: return@after
        items.removeAll { /* identify premium tab item */ }
    }
}
```

### Settings Integration
Needs a new `PrefKeys.KEY_HIDE_PREMIUM_TAB` and a toggle in the SpotXero settings UI. The setting should warn that a Spotify restart is required (Morphe's `PatchSettings.restartRequired()` checks this).

---

## Key Differences from Morphe

| Aspect | Morphe | SpotXero Port |
|---|---|---|
| Resolution | ABI snapshot + hardcoded `Lp/tkd0;` and `Lp/f4p0;->b()Z` | DexKit search by semantic markers |
| Injection | Inserts smali after `move-result` | `.after {}` hook overrides `param.result` |
| Toggle granularity | Intercepts the boolean mid-computation | Can intercept at the same level or at the list level |

## Risk Assessment
- **Medium risk**: Navigation tab construction may change structurally across versions.
- **Mitigation**: If DexKit can't find one clean fingerprint, the alternative is hooking at the list/adapter level where navigation items are registered — a broader but more stable surface.
