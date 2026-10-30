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

### Verified details (Spotify 9.1.80.2221)

- **The guard is shape-checked, not assumed**: the patch requires exactly one call to `Lp/f4p0;->b()Z` in `Lp/tkd0;->invoke()`, requires the following instruction to be `MOVE_RESULT`, and refuses otherwise. Porting commit `b035aba` (Spotify 9.1.88) records why this is the fragile part: *"The flag consumer no longer loads a constant between the flag result and its branch, so the verifier checks the test one instruction earlier."* A port that hardcodes "MOVE_RESULT then IF_EQZ" breaks on such a change; resolve by opcode sequence, not fixed index.
- **Morphe hooks the consumer, not the flag.** The flag method itself (`Lp/f4p0;->b()Z`) has other callers; overriding it there changes every consumer of the Premium-tab entitlement, not just the navigation list. This matches SpotXero's AGENTS rule to hook the minimal surface at the latest safe point.
- **Restart is required.** The tab set is part of the startup state Spotify reads, and `PatchSettings.restartRequired()` compares the current value with the one Spotify started with, so the restart bar stays visible while they differ.
- **Scope**: the patch description states it "does not change your subscription or remove other ads", so it is a UI hide, not an entitlement change.

---

## Xposed Porting Strategy

### DexKit Resolution

**Verified anchor to implement**: a no-arg `invoke()` (returning `Object`) that calls a no-arg boolean getter exactly once, on a class whose items become navigation tabs — i.e. resolve the *consumer* by "single boolean-getter call result feeds a branch that decides whether to build the tab", then hook its result. The three sketches below are earlier guesses kept for context; the verified anchor is the one to build.

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

**Approach 2 — Hook the boolean flag itself (not preferred):**
```kotlin
// The flag getter is shared: other code reads the same entitlement, so overriding
// it changes more than the tab list. Morphe deliberately hooks the consumer instead.
// Use this only if the consumer cannot be resolved and the extra scope is acceptable.
```

**Approach 3 — Find via navigation model strings:**
If the navigation construction uses identifiable string constants (like tab IDs, content descriptions, or resource references), those can be used as anchors to find the surrounding code and the flag getter.

### Hook Implementation

```kotlin
// Option A: the consumer's own result — one call site, no collateral effect
module.hook(premiumTabItemBuilder).before { param ->
    if (PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_PREMIUM_TAB, false)) {
        param.result = null // the tab model is never built
        return@before
    }
}

// Option B: post-filter the navigation item list
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
- **Medium risk**: Navigation tab construction may change structurally across versions, and this one already did once (9.1.88 removed the constant between the flag result and its branch).
- **Mitigation**: resolve by opcode sequence — single boolean-getter call, result consumed by a branch — rather than by instruction index. Never fall back to the flag getter silently; that widens the blast radius to every entitlement reader.
