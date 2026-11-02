# Mechanism 1: Product-State Account-Attribute Spoof

**Observed source**: SpotifyEx `c.smali` (`<clinit>` + `a()`) with hook payload `b.smali`; companion module `f10.smali` + `p10.smali` hook bodies in `oo.smali`, registered through the `k10`/`ix` fingerprint chain.
**Targets in the client**: `com.spotify.remoteconfig.internal.ProductStateProto` family — the map of `internal.AccountAttribute` protobuf objects the client fetches and caches for entitlement decisions.
**Porting difficulty**: Low (map seam partially shipped) / Medium (typed-object seam, new).

---

## What The Stack Does

### The spoof table

Both modules independently carry the identical 12-entry override table — strict `key → spoofed value`
(verified by tracing every register copy in both `<clinit>`/builder methods; values differ in nothing):

| Attribute key | Spoofed value | Type |
|---|---|---|
| `ads` | `false` | Boolean |
| `player-license` | `"premium"` | String |
| `player-license-v2` | `"premium"` | String |
| `shuffle` | `false` | Boolean |
| `on-demand` | `true` | Boolean |
| `streaming` | `true` | Boolean |
| `pick-and-shuffle` | `false` | Boolean |
| `streaming-rules` | `""` | String |
| `nft-disabled` | `"1"` | String |
| `type` | `"premium"` | String |
| `can_use_superbird` | `true` | Boolean |
| `tablet-free` | `false` | Boolean |

`shuffle=false` + `pick-and-shuffle=false` + `on-demand=true` + `player-license="premium"` is the
"in-order playback" load-bearing set: the client stops believing the account is restricted to
shuffle-only and stops entering the shuffle-forcing flows (`shuffle` and `pick-and-shuffle` are
Boolean-typed attributes — string values like `"0"` will not coerce to the same proto arm).

### SpotifyEx (module 1): DexKit resolution + hookAllMethods

Anchors (strings in the module code, resolved against the target app's dex via bundled `libdexkit`):

- `internal.AccountAttribute` — finds the attribute-class shape
- `value_` (prefix) — the value field of each attribute object
- `ProductStateProto` — finds the product-state proto container
- a `java.util.Map`-typed field — the attribute map inside the container

After resolution it reflects into the resolved container class and applies:
(from `c.smali`, exact smali preserved)

```smali
invoke-static {v0, v1, v2}, Lde/robv/android/xposed/XposedBridge;->hookAllMethods(
    Ljava/lang/Class;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)Ljava/util/Set;
```

The `afterHookedMethod` in `b.smali` filters map results:

```smali
invoke-virtual {p1}, ...MethodHookParam;->getResult()Ljava/lang/Object;
instance-of v0, p1, Ljava/util/Map;
...
sget-object v2, Lc;->a:Ljava/util/LinkedHashMap;
invoke-virtual {v2, v1}, Ljava/util/LinkedHashMap;->get(...)  # key → spoofed value, skip when absent
invoke-virtual {v3, v0, v2}, Ljava/lang/reflect/Field;->set(Ljava/lang/Object;Ljava/lang/Object;)V
```

i.e. **whenever any hooked method returns a `Map<String, AccountAttribute>`, every matching attribute
instance has its `value_` field overwritten** with the typed spoof. This is the same idea as
SpotXero's `ProductStateHook` (`module.hook(key_product_state_map).after { chain.proceed() … }`), but
at the proto-object level rather than by substituting strings into a map.

### Companion module (module 2): named fingerprint

The same rewrite, structured ReVanced-style: a registry entry
`"productStateProtoFingerprint"` (`k10.smali` → `Lcf;` fingerprint lambdas) is resolved to an
`Executable` by the hooker (`jx.h(bb)`), then wrapped in the shared
`wh(before, after)` XC_MethodHook pair whose bodies are the synthetic case dispatcher `oo`:

```smali
invoke-virtual {v2, v4}, Lza;->c(Ljava/lang/String;Lw3;)Lbb;          # "productStateProtoFingerprint"
invoke-virtual {v0, v1}, Ljx;->h(Lbb;)Ljava/lang/reflect/Executable;
invoke-static {v1, v8}, ...XposedBridge;->hookMethod(Ljava/lang/reflect/Member;XC_MethodHook;)...
```

The after-hook case (from `oo.smali`, abridged):

```smali
invoke-interface {p1}, Ljava/util/List;->iterator()          # Lf10->a list of Le10 key/value/enabled
invoke-interface {v1, v3}, Ljava/util/Map;->get(...)         # productState map lookup by attribute key
invoke-static {v3, p0}, XposedHelpers;->getObjectField(...)  # read current value_ …
invoke-static {v3, p0, v0}, XposedHelpers;->setObjectField(...) # … write spoofed value_
```

### Managed patch failure reporting

SpotifyEx wraps each patch in try/catch and logs a distinct failure tag — the port should mirror this
observability: `"Failed to patch integrity"`, `"Failed to block requests"`,
`"Failed to patch account"` (this spoof), `"Failed to remove popups"`, `"Failed to patch context menu"`,
`"Failed to clean tracking URLs"`, each under tag `"SpotifyEx"`.

---

## Xposed Porting Strategy (SpotXero)

### Second seam in ProductStateHook

Keep the existing map seam and add an attribute-object seam. Resolve with the same anchors the stack
proves sufficient in 9.1.94.1912:

```kotlin
val containers = dexKit.findClass {
    matcher { usingString("ProductStateProto") }
}
require(containers.size == 1) { "ProductState container unresolved (matches=${containers.size})" }
val attrClasses = dexKit.findClass {
    matcher { usingString("internal.AccountAttribute") }
}
require(attrClasses.size == 1) { "AccountAttribute unresolved (matches=${attrClasses.size})" }
```

Wire these as new `KEY_*` entries resolved inside `DexKitResolver.resolve(...)` following the existing
per-key resolver functions: `KEY_PRODUCT_STATE_ATTRIBUTE_OBJECTS` (the container's map-returning
members, validated to return `Map`), and `KEY_PRODUCT_STATE_ATTRIBUTE_VALUE_FIELD` (the writable
`value_*` field, validated against the resolved attribute class). A missing or ambiguous match must
raise (fail closed), never silently skip. The hook then rewrites the objects with **typed** values:

```kotlin
val method = DexKitResolver.get<Method>(DexKitResolver.KEY_PRODUCT_STATE_ATTRIBUTE_OBJECTS)
    ?: return
module.deoptimize(method)
module.hook(method)
    .setId("product_state_attribute_objects")
    .setExceptionMode(ExceptionMode.PROTECTIVE)
    .intercept { chain ->
        val result = chain.proceed()
        (result as? Map<*, *>)?.forEach { (key, attr) ->
            AppAccountOverrides[key as? String]?.let { spoof ->
                valueField.set(attr, spoof.value)
            }
        }
        result
    }
```

with `AppAccountOverrides` expressed as a sealed typed table (Boolean / String), matching the table
above. Note `type = "premium"` is a **String** in this client (it appears as the constant
`"premium"`, correct in this proto family).

### Delta against today's `ProductStateHook`

| Key | SpotXero `ProductStateHook` | Shipped stack | Decision |
|---|---|---|---|
| `type` | `"premium"` ✓ | `"premium"` | keep |
| `pick-and-shuffle`, `streaming-rules`, `ads`, `on-demand` | ✓ (string values) | typed equivalents | keep both seam's values consistent |
| `player-license`, `player-license-v2`, `shuffle`, `streaming`, `nft-disabled`, `can_use_superbird`, `tablet-free` | **missing** | present | add to both seams |
| `catalogue`, `horizon`, `shuffle-restricted`, `unlimited-skips` | present | absent | keep — SpotXero-only extras, harmless client-side UI beliefs (`offline` omitted so server-side entitlement is untouched) |

Also extend the esperanto payload rewrites in `PlayerShuffleHook`
(`overrideProductStateMap` / `overridePutValues`) with the same seven keys so the server-written legacy
storage view agrees with the in-client spoof. Mismatched keys across seams is exactly the "dual code
paths" rot `AGENTS.md` warns about — express the key set once.

### Settings integration

Reuses `PrefKeys.KEY_ENABLE_PREMIUM` (already gates both existing seams). Do **not** introduce a
separate toggle per key — the shipped stack does not, and per-key toggles widen the resolvable surface
for no documented benefit.

---

## Key Differences From the Shipped Stack

| Aspect | Shipped stack | SpotXero port |
|---|---|---|
| Record of truth | fixed 12-key table in both modules | one shared typed table, consumed by both seams |
| Toggle-ability | always-on (no pref read in the override path) | gated by `KEY_ENABLE_PREMIUM` like the existing map seam |
| Failure behaviour | each module catches and logs; SpotifyEx also swallows DexKit failures with `"Failed to patch account"` | fail closed — unresolved/ambiguous fingerprints must raise, never silently skip |
| Identity of callers | hooks every map-returning method resolved | same — but keep the method-set auditable by logging resolved member names on first hit |

## Risk Assessment

- **Low risk at runtime** — the hooks are additive overwrites of already-decoded protobuf data; they
  cannot deserialise invalid states if value arms match (`shuffle` as `Boolean`).
- **Medium risk of resolution rot** — the client is obfuscated; a future build may rename
  `internal.AccountAttribute`/`ProductStateProto` descriptors or drop them from the string table.
  Resolution must be R8-churn tolerant: anchor on the descriptor strings, validate the resolved class
  exposes a `Map` field and a writable `value_*` field, and fail closed otherwise.
- **Not a premium unlock** — consistent with `docs/morphe-porting/00-overview.md`: this changes what
  the client believes; server-side entitlement is untouched.
