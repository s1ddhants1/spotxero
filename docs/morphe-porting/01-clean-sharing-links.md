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
- **Low risk**: The strings (`"si"`, `"utm_source"`, etc.) are standard tracking parameters unlikely to change.
- **`GenerateUrlResponse`** is a named protobuf class, not obfuscated — stable across versions.
- The `Uri.toString()` pattern is fundamental Android API usage — stable.
