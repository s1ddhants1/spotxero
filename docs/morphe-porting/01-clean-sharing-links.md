# Patch 1: Clean Sharing Links

**Morphe source**: [`SharingLinksPatch.kt`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/privacy/SharingLinksPatch.kt), [`ShareResult.kt`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/privacy/ShareResult.kt)
**Runtime code**: [`SharingLinks.java`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/extensions/extension/src/main/java/app/spicetify/extension/spotify/privacy/SharingLinks.java)

**Porting difficulty**: ✅ Easy — SpotXero already has a `CleanSharingHookTemplate`.

---

## What Morphe Does

### Step 1: Find the share URL builder
Uses a `Fingerprint` with string markers: `"si"`, `"context"`, `"utm_source"`, `"utm_medium"`, `"utm_campaign"`, `"Invalid uri "`.

### Step 2: Hook after final `Uri.toString()`
Finds the last `invoke-virtual Uri->toString()` in the matched method, then after the `move-result-object`, inserts:
```smali
invoke-static {vN}, SharingLinks->onShareUrl(String)String
move-result-object vN
```

### Step 3: Find and hook the ShareResult constructor
`ShareResult.kt` resolves the share result class via the `GenerateUrlResponse` protobuf — locates its `shareableUrl_`, `shareId_`, `spotifyUri_`, `fullUrl_` fields, traces which classes call all four getters and pass them to a 4-String constructor. Hooks index 1 of that constructor to clean both `p1` (shareableUrl) and `p4` (fullUrl).

### Runtime Logic (`SharingLinks.java`)
```java
public static String onShareUrl(String url) {
    return PatchSettings.cleanSharingEnabled() ? sanitizeUrl(url) : url;
}
```
Strips `si`, `pi`, `utm_source`, `utm_medium`, `utm_campaign`, `utm_content`, `utm_term`, `utm_id` from `open.spotify.com` URLs.

### Verified details (Spotify 9.1.80.2221)

- **Builder fingerprint shape**: `parameters = [String, String, L, Map]`, `returnType = L`, strings `["si", "context", "utm_source", "utm_medium", "utm_campaign", "Invalid uri "]`. A comment in the source records that the historical anddea `ShareUrl` constructor no longer exists in 9.1.80, so the builder is the anchor now.
- **Injection is after the last `android.net.Uri->toString()`** in the builder, i.e. after its `move-result-object`. The patch refuses unless there is exactly one fingerprint match and that `move-result-object` follows the conversion.
- **Why a second site exists**: server-generated links bypass the local builder. `findShareResultConstructor` resolves the 4-`String` constructor that consumes `GenerateUrlResponse`'s `shareableUrl_`, `shareId_`, `spotifyUri_` and `fullUrl_`, then cleans arguments `p1` (shareableUrl) and `p4` (fullUrl) at index 1 — after `Object.<init>`, before the fields are stored.
- **The resolver is strict**: each getter must be exactly `iget-object` on the named proto field followed by `return-object`, the four argument registers must be distinct, and zero or multiple candidates throw. This is the opposite of a permissive scan, and it is why a changed proto shape fails loudly instead of cleaning nothing.

---

## Xposed Porting Strategy

### DexKit Resolution

**Target 1 — Share URL builder method:**
```kotlin
dexKit.findMethod {
    matcher {
        usingStrings("si", "context", "utm_source", "utm_medium", "utm_campaign", "Invalid uri ")
        paramTypes("java.lang.String", "java.lang.String", null, "java.util.Map")
        returnType(null) // any object return
    }
}
```

**Target 2 — ShareResult constructor:**
```kotlin
// Find GenerateUrlResponse class (stable, non-obfuscated name)
val responseClass = loadClass("com.spotify.share.linkgeneration.api.proto.GenerateUrlResponse")

// Find methods that call all 4 field getters on GenerateUrlResponse and pass results to a 4-String constructor
// This requires DexKit's cross-reference / call-graph search, or a simpler approach:
// Find all <init>(String, String, String, String) constructors where the defining class 
// is only instantiated from code that also reads GenerateUrlResponse
dexKit.findMethod {
    matcher {
        name("<init>")
        paramTypes("java.lang.String", "java.lang.String", "java.lang.String", "java.lang.String")
        // Filter by callers that reference GenerateUrlResponse
    }
}
```

### Hook Implementation

```kotlin
// Hook 1: URL builder — after it produces the final URL string
module.hook(shareUrlBuilderMethod).after { param ->
    val result = param.result as? String ?: return@after
    param.result = sanitizeUrl(result)
}

// Hook 2: ShareResult constructor — clean the URL arguments
module.hook(shareResultConstructor).before { param ->
    param.args[0] = sanitizeUrl(param.args[0] as? String) // shareableUrl
    param.args[3] = sanitizeUrl(param.args[3] as? String) // fullUrl
}
```

### Runtime Logic
The `sanitizeUrl` logic from `SharingLinks.java` can be ported as-is into a Kotlin utility — it's pure string manipulation with no dependencies on Spotify internals.

### Settings Integration
SpotXero already has `PrefKeys.KEY_CLEAN_SHARING` and `CleanSharingHookTemplate`. The hook should check `PreferencesManager.isEnabled(PrefKeys.KEY_CLEAN_SHARING, true)` before modifying URLs.

---

## Key Differences from Morphe

| Aspect | Morphe | SpotXero Port |
|---|---|---|
| Target resolution | Fingerprint with string list; separate `ShareResult.kt` resolver | DexKit string search + proto class cross-reference |
| Injection point | Inserts smali after `move-result-object` | `.after {}` hook on the method, modify `param.result` |
| ShareResult hook | Inserts smali at constructor index 1 | `.before {}` hook on constructor, modify `param.args` |
| Version coupling | Expects exactly one fingerprint match | DexKit search is version-resilient if strings persist |

## Risk Assessment
- **Low risk is about the sanitizer, not the resolution.** `sanitizeUrl` is pure string work and ports as-is; the strings (`"si"`, `"utm_source"`, …) are standard tracking parameters.
- **Medium risk on resolution.** The builder fingerprint is a shape match, and the constructor resolver depends on four proto getters plus a 4-register constructor. Both fail closed here, and SpotXero's resolver should do the same rather than clean nothing.
- **Known open bug, issue #19.** A report against 9.1.80.2221 with Morphe 1.47/2.2.0 fails on `Clean sharing links` because the settings dependency refuses the ABI snapshot — the patcher-side problem fixed after v1.2.0, not a fault in the link logic. Group any SpotXero report the same way before touching this feature.
- **Server-side coverage is the part ports usually miss.** SpotXero's `CleanSharingHookTemplate` only cleans locally built URLs; without the `GenerateUrlResponse` constructor site, links produced by Spotify's own link service stay dirty.
