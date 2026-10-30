# Patch 3: Hide Home and Browse Brand Ads

**Morphe source**: [`BrandAdsPatch.kt`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/ads/BrandAdsPatch.kt)
**Runtime code**: [`BrandAds.java`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/extensions/extension/src/main/java/app/spicetify/extension/spotify/ads/BrandAds.java)

**Porting difficulty**: ✅ Feasible (medium complexity)

---

## What Morphe Does

### The Hook: One String Getter

`BrandAdsPatch` resolves the method that produces the brand-ads panel string (`Lp/c8j0;->a()` in Spotify 9.1.80.2221 — a no-arg static getter returning `String`) and answers `""` when the feature is on. There is no section list, no protobuf discriminator and no per-URL logic; the panel is empty because the string it renders from is empty.

Two mechanisms exist for the same effect, selected by a build flag:

- **`hookBrandAds`** — rewrites the call sites so they receive `""` instead of calling the getter. Affects only those callers, which is why the patch must find each of them.
- **`redirectBrandAds`** — replaces the getter's body with a proxy that consults `Spotify.settings.adBlock.brandAds` and otherwise calls the original. Affects every caller and requires keep rules on the class (`shrink`/`markAsResourceImplementation`), since a replaced method cannot be renamed or stripped.

### Runtime Logic

`BrandAds.java` is 44 lines and holds no rendering logic: it is the settings adapter (ABI-guarded `set`/`get`, toggle per `BrandAdsVariant`). All of the patch lives in the Kotlin/dex side.

### Verified details (Spotify 9.1.80.2221)

- **`findRenderPanelMethod` validates before injecting**: the target must be a no-arg static getter whose body loads `const-string ""`. A match on the string alone is not enough.
- **The hook point is a constant, not a panel.** Everything the brand-ads panel draws flows from this one string; returning `""` makes the panel render empty. There is no per-URI or per-campaign logic to reproduce, which is why this ports so cheaply with String/const-string resolution.
- **It only hides that panel.** The patch states "Does not suppress audio ads, player ads, or upgrade prompts. Experimental." Audio ads need the break model, upgrade prompts are separate UI — see [11-audio-ad-blocking.md](11-audio-ad-blocking.md).

---

## Xposed Porting Strategy

### DexKit Resolution

Resolve the getter by its body, then confirm it from its caller. There is no list and no discriminator field to read — a `HomeStructure`/`BrowseStructure` filter would hook the wrong thing.

```kotlin
val brandAdPanelString = dexKit.findMethod {
    matcher {
        paramCount(0)
        returnType("java.lang.String")
        static()
        // body: const-string ""
        instruction("const-string", "")
    }
}.singleOrNull() ?: throw IllegalStateException("Brand-ads panel string not resolved (0 or >1 match)")
```

If the shape is ambiguous, narrow it from the consumer: the method that builds the brand-ads panel invokes the getter and places the string in its model. Confirm the caller before attaching, and refuse rather than picking the first empty-string getter in the APK.

### Hook Implementation

```kotlin
module.hook(brandAdPanelString).before { param ->
    if (PreferencesManager.isEnabled(PrefKeys.KEY_HIDE_BRAND_ADS, false)) {
        param.result = ""
        return@before
    }
}
```

Morphe expresses the same thing with `hookBrandAds` (call-site rewrite) or `redirectBrandAds` (method-body replacement). A LibXposed `.before {}` is the simpler third form, and unlike `hookBrandAds` it affects every caller of the getter — acceptable here because the getter exists only to feed the panel, and worth re-checking if that ever stops being true.

### Settings Integration
Add `PrefKeys.KEY_HIDE_BRAND_ADS` and a toggle in SpotXero settings.

---

## Key Differences from Morphe

| Aspect | Morphe | SpotXero Port |
|---|---|---|
| Target | Static `Lp/c8j0;->a()` returning the panel's `String` | Resolve by `const-string ""` return + caller shape, then hook |
| Injection point | Method-replacement proxy for that getter | `.before {}` setting `param.result = ""` — same effect, no bytecode surgery |
| Runtime logic | `BrandAds.java` settings adapter (44 lines, ABI-guarded) | A single preference read; no adapter needed |
| Shrinking guard | Morphe's `shrink`/`markAsResourceImplementation` keep rules | Not applicable — nothing is renamed at build time |
| Fail mode | Throws `PatchException` when the shape changed | Throws at resolution, so no hook is attached and the panel stays intact |

## Risk Assessment
- **Low risk on behavior**: the panel is driven by one string; empty string means empty panel.
- **Medium risk on resolution**: the getter is obfuscated, so it is found by shape, and many getters return `""`. Resolve from the consumer and fail closed on ambiguity.
- **Mitigation**: require the single `const-string ""` body *and* the panel-building caller; if either is missing, do not attach a no-op hook.
