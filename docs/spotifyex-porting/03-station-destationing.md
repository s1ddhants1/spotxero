# Mechanism 3: Station De-Stationing (`spotify:station:` → Base Context)

**Observed source**: companion module `p10.smali` (rewrite helper) + `oo.smali` (hook case that applies it).
**Target in the client**: play/context objects whose `uri`/`url` fields carry a station URI wrapped around a real context — `spotify:station:spotify:playlist:…` handed back to the client for free-tier shuffle-only playback.
**Porting difficulty**: Low code / Medium semantics — the rewrite itself is trivial; the hard part is scoping it so genuine "start radio from this track/playlist" intent is not destroyed.

---

## What The Companion Module Does

### The rewrite helper (condensed from `p10.smali` lines 122–192; signature verified)

```smali
.method public static final a(Ljava/lang/reflect/Field;Ljava/lang/Object;)V
    if-eqz p0, :cond_2
    invoke-virtual {p0, p1}, Ljava/lang/reflect/Field;->get(Ljava/lang/Object;)Ljava/lang/Object;
    move-result-object v0
    instance-of v1, v0, Ljava/lang/String;
    ...
    sget-object v1, Lf10;->a:Ljava/util/List;
    ...
    const-string v1, "spotify:station:"
    const-string v2, "spotify:"
    invoke-virtual {v0, v1, v2}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;
    move-result-object v0
    invoke-virtual {p0, p1, v0}, Ljava/lang/reflect/Field;->set(Ljava/lang/Object;Ljava/lang/Object;)V
```

Line order, registers and the two literals are exact; `…` elides control-flow labels and the
try/catch regions. The behaviour facts: the field itself is null-checked first; the read value is
kept only if it is a `String` (anything else becomes "no value" and the method returns without
writing); the replace is wrapped in a `catch Exception` whose fall-through still reaches the
`Field.set`, so a thrown replace writes the original value back unchanged and the caller keeps its
object intact.

Only two string facts are used: the value must already be a `String`, and the substitution is the
literal `spotify:station:` → `spotify:`. There is no URI parsing and no allowlist — anything passing
through the hooked result mutates deterministically.

### Where it is applied

The shared hook body (`oo.smali`, the case that follows the upstream fingerprint resolution) takes the
hooked method's result, then rewrites two fields (exact registers, `oo.smali` lines 492–521):

```smali
invoke-virtual {p0}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
move-result-object p1
const-string v0, "uri"
invoke-static {p1, v0}, Lde/robv/android/xposed/XposedHelpers;->findField(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;
move-result-object v0
invoke-static {v0, p0}, Lp10;->a(Ljava/lang/reflect/Field;Ljava/lang/Object;)V
const-string v0, "url"
invoke-static {p1, v0}, Lde/robv/android/xposed/XposedHelpers;->findField(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;
move-result-object p1
invoke-static {p1, p0}, Lp10;->a(Ljava/lang/reflect/Field;Ljava/lang/Object;)V
```

The result object is class-scanned for **both** a `uri` and a `url` field by name and each is passed
through the helper (same string-level `Field` lookup, so the shape requirement is simply "has a
public `uri`/`url` String field"). Also in this module's evidence pool: `formatAndroidShareSheetUrlFingerprint`,
`canBindAppWidgetPermissionFingerprint`, `shareCopyUrlFingerprint`, `browseStructureGetSectionsFingerprint`
and the `pendragon*` fingerprints — evidence that the module hooks result-object shapes by name
across different systems, of which the station rewrite is one application pattern.

### Why it matters for in-order playback

Spotify's free tier answers some play requests with a **station context**
(`spotify:station:<orig>`) rather than the playlist the user tapped. Station contexts are the
randomized roll-the-dice surfaces — even with the product-state attributes spoofed (doc 01), a queue
initialised from a station URI still comes from the recommendation station machinery. Rewriting the
URI before the session is created makes the queue linear from the original playlist.

---

## Xposed Porting Strategy (SpotXero)

**No SpotXero hook touches stations today** — this is the stack's cleanest net-new contribution.

### Where to hook

Do not hook generic uri/url fields like module 2 does. The safer seam is the esperanto play surface
`PlayerShuffleHook` already supervises (`ContextPlayer.Play` / `PreparePlay` /
`PlaylistPlayService.Play`), rewriting the context payload before dispatch:

```kotlin
private const val STATION_PREFIX = "spotify:station:"
private const val BASE_PREFIX = "spotify:"

fun deStation(uri: String): String? =
    if (uri.startsWith(STATION_PREFIX)) uri.substring(STATION_PREFIX.length) else null
```

applied to the string fields of the payload the existing `handleEsperantoPayload` path already walks
(the context/uri field of the request). Guard with a distinction the module ignores:

- **Apply** when the request is the *continuation of a user-initiated play* (user pressed play on a
  playlist and the payload shows a station variant) — that is exactly the invalid behaviour to fix.
- **Do not apply** when the user deliberately invoked radio (the start-radio buttons / swipe-up).
  Without that, every station anywhere collapses to its base context and "I wanted radio" breaks.
  The shipped module has no such distinction; SpotXero should be better than the source.

### Settings integration

Coupled directly into `PrefKeys.KEY_ENABLE_PREMIUM`, active whenever premium features are enabled.

### Edge cases to verify on device

1. Queue continuation: after de-stationing, the appended recommended tracks (station tail) should not
   silently vanish or double-insert — observe via `callSingle` logs.
2. Nested station chains (`spotify:station:spotify:station:…`) collapse to the innermost
   `spotify:…` — with `startsWith` + substring this is naturally single-shot; confirm no double
   application across repeated hook hits.
3. `SpotXero` playback-quality bar (`AGENTS.md`): track starts must not stall — the rewrite happens
   pre-dispatch, so no mid-session mutation.

---

## Key Differences From the Companion Module

| Aspect | Companion module | SpotXero port |
|---|---|---|
| Hook point | any hooked result carrying `uri`/`url` fields | esperanto play payloads already supervised by `PlayerShuffleHook` |
| Scope | indiscriminate | user-initiated plays only; genuine radio preserved |
| Toggle | none (part of "Enable AdBlock"/premium flow) | coupled to `KEY_ENABLE_PREMIUM` |
| Rewrite | literal `"spotify:station:"` → `"spotify:"` | identical literal, kept as a named constant — a stable protocol identifier, not an obfuscated symbol |

## Risk Assessment

- **Very low mechanical risk** — constant string substitution; the anchor string (`spotify:station:`)
  is as stable as protocol identifiers get.
- **Semantic risk is real**: the module rewrites *everything* that passes its hooks; a faithful port
  would flatten the radio feature globally. Port into the play payload path only, with the toggle
  defaulting off, and validate that deliberate radio still plays (the `AGENTS.md` bar:
  navigation, search, queue and radio flows must behave normally).
