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

### Verified details (Spotify 9.1.80.2221)

- **Only two gates, both resolved by string constants.** `PlayerAdCardsPatch` finds the target by searching for the literal `"slot_id"`, `"ad_break"` and `"partner_name"` (the ad-card metadata fields) and for the cards-group builder used by the player; it then hooks (a) the boolean gate that decides the ad-card list is non-empty and (b) the grouping call that produces the card group. The `Cr` interface is consulted, not hooked.
- **Runtime logic is 16 lines.** `PlayerAdCards.java` is a settings adapter only (`PlayerAdCardsVariant` toggle). All the work is in which list the player is handed, which keeps the port small: return an empty/composed-without-ads collection and the card group renders nothing.
- **Why grouping, not filtering**: filtering individual item accessors is per-draw and per-accessor; composing the group once, as the patch does, is one interception point regardless of how many accessors the renderer calls. This is the "latest safe point" rule in SpotXero's AGENTS.md.
- **It does not stop audio.** Ad breaks still arrive and still play; the doc's premise (no cards shown) depends entirely on the group being empty, so a partial hook degrades to "cards visible" rather than to broken playback — and the patch description says so: "Does not suppress audio ads or other player overlays. Experimental."

---

## Xposed Porting Strategy

### DexKit Resolution

The strings are the anchor; the `Section`/`ContextTrack` types are not. Resolve the ad-card metadata method first, then take the two call sites it feeds — the `Cr` object it builds and the boolean gate that decides whether the group is shown.

```kotlin
val adCardMetadata = dexKit.findMethod {
    matcher {
        usingStrings("slot_id", "ad_break", "partner_name")
    }
}.singleOrNull() ?: throw IllegalStateException("Ad card metadata method not resolved")

val adCardGate = dexKit.findMethod {
    matcher {
        returnType("boolean")
        // calls adCardMetadata, or reads the list it fills, then branches into the card group
        invokes(adCardMetadata)
    }
}.single()
```

### Hook Implementation

```kotlin
// Hook 1: the gate — cheap, and it is the shape Morphe hooks first
module.hook(adCardGate).before { param ->
    if (PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_PLAYER_ADS, false)) {
        param.result = false
        return@before
    }
}

// Hook 2: the group builder — prefer this one; the player is handed a group
// that contains no ad cards, so nothing downstream has to filter per draw
module.hook(cardsGroupBuilder).before { param ->
    if (PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_PLAYER_ADS, false)) {
        param.result = buildGroupWithoutAds(param.args[0])
        return@before
    }
}
```

Keep both sites independent: if one resolves and the other does not, attach the one that resolved and report the miss, because they cover different paths (image card vs. embedded ad) and neither is implied by the other.

### Settings Integration
Add `PrefKeys.KEY_HIDE_PLAYER_ADS` and a toggle in SpotXero settings. Read the preference once per attach where possible — the gate is on the player's render path.

---

## Key Differences from Morphe

| Aspect | Morphe | SpotXero Port |
|---|---|---|
| Target | Calls found by the `"slot_id"` / `"ad_break"` / `"partner_name"` strings | Same string anchors through DexKit; no `Cr`-walking |
| Gate hook | Method replacement on the non-empty cards gate | `.before {}` with `param.result = false` |
| Group hook | Method replacement on the card-group builder | Reproduced as a `.before {}` on the same builder, returning the non-ad group |
| Runtime logic | `PlayerAdCards.java` settings adapter (16 lines) | Single preference read |
| Fail mode | Throws `PatchException` on a changed shape | Cards stay visible; playback untouched |

## Risk Assessment
- **Low risk on the strings**: ad-card metadata keys are server-driven and have to stay stable for Spotify's own tooling.
- **Medium risk on the gate**: a boolean gate around a list is a common shape; confirm the strings are in the same method before hooking, and refuse on more than one candidate.
- **Mitigation**: prefer the group hook over the gate (one interception point, no per-item draw cost), and verify by playing a track through a real ad break rather than by inspecting the card group.
