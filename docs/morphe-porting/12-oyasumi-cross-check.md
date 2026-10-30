# Cross-check: `oyasumi-patches` (dev)

**Source**: [dunecache/oyasumi-patches](https://github.com/dunecache/oyasumi-patches/tree/dev/patches/src/main/kotlin/app/spotify/patches), `dev` branch, analysed 2026-10-30 at `fe89c5b` (`v0.6.0-dev.40`, 234 commits).
**Why it matters**: it is a second, independent Morphe patcher that reached the same Spotify ad seam SpotXero uses at runtime — and stopped where SpotXero's device measurements stopped.

This file records what it confirms, what it contradicts, and which of its engineering practices are worth copying into a runtime hook module. It is not a porting guide for its patches.

---

## 1. Shape of the project

Different from `morphe-patches` in the ways that matter:

| Aspect | `morphe-patches` | `oyasumi-patches` |
|---|---|---|
| Scope | Spotify only | Multi-app: ADM, 1DM, Djezzy, Truecaller, Spotify (Pinterest removed in `1875509`) |
| Spotify target | `9.1.80.2221`, `versionCode` `145767611`, **APKM**, experimental | `9.1.90.2270`, `versionCode` `147078674`, **regular APK** (`Constants.kt`) |
| Spotify patches | 9 documented features | 2: `Disable ads`, `Disable Smart Shuffle default` |
| Extensions | Java runtime merged into the APK | None shipped (`extensions/` is absent; `1875509` deleted the last four files and the directory) |
| Fingerprints | Inline in patch files, plus `.properties` ABI digests | One `Fingerprint` object per anchor in a sibling file, no ABI snapshots |
| Verification | Gradle + device | `tools/checks/` static checks + `resolve_fp.py` against a cached APK + device |

The smaller surface is the point: two patches, both narrow, both described with an explicit limitation. That is a healthier ratio than nine patches that each need a per-version fingerprint pass.

---

## 2. Its Spotify patches

### 2.1 `Disable ads` — the same seam as SpotXero's `AdResponseHook`

```kotlin
// AdsFingerprint.kt — coroutine wrapper for spotify.ads.esperanto.proto.Ads.GetAds
definingClass = "Lp/x22;", name = "a", returnType = "Ljava/lang/Object;",
parameters = ["Lcom/spotify/ads/esperanto/proto/GetAdsRequest;", "Lp/xel;"],
filters = [ string("spotify.ads.esperanto.proto.Ads"), string("GetAds"),
            methodCall("Lcom/spotify/ads/esperanto/proto/GetAdsResponse;", "r", ["[B"],
                       "Lcom/spotify/ads/esperanto/proto/GetAdsResponse;") ]
```

```text
; DisableAdsPatch.kt — early return at index 0
const/4 v0, 0
new-array v0, v0, [B
invoke-static {v0}, Lcom/spotify/ads/esperanto/proto/GetAdsResponse;->r([B)Lcom/spotify/ads/esperanto/proto/GetAdsResponse;
move-result-object v0
return-object v0
```

Three details are worth carrying into this repository's own notes:

- **`DEFAULT_INSTANCE` is private**, so the empty response has to be produced through the proto parse (`r([B)`) with a zero-length array. Commit `f0f4cc3` exists only because the first version referenced the field and would not assemble. SpotXero's `AdResponseHook` does the decode side of the same thing; if it ever constructs the message reflectively, this is the reason to prefer the parser.
- **The seam is a suspend wrapper, not the proto service.** `Lp/x22;->a(...)` returns `Object` because it is a coroutine body; an unconditional early return there is safe only because the method's only job is "call, parse, return".
- **The patch's own description overclaims.** The README says *"Return Spotify's default empty ad response from GetAds, so no server-provided audio ad is used"*, and `reference/NOTES.md` says *"never fetch or render Spotify's served ads"*. Both statements are true about the response and false about the break: on 9.1.x the audio break still plays from the native player with an empty `GetAdsResponse`. The same note then admits the uncertainty — *"this seam is only one of Spotify's ad paths; UI ads, cached/session ads, premium-upsell prompts, and playback slates may use other services. Runtime behavior is untested."* That is the honest half, and it matches the measurements in [11-audio-ad-blocking.md](11-audio-ad-blocking.md) exactly.

So: two independent projects converged on the same one-line seam, and neither has evidence it stops an audible ad. Treat `GetAds` interception as an ad-*fetching* patch, not an ad-*blocking* patch, in all user-facing wording.

### 2.2 `Disable Smart Shuffle default` — a feature Morphe does not ship

```kotlin
// SmartShuffleFingerprint.kt — remote-config model booleans
Lp/ez4;->r()Z  → smart_shuffle_as_default_for_liked_songs, reads Lp/ez4;->q:Z
Lp/ez4;->s()Z  → smart_shuffle_as_default_for_playlist,    reads Lp/ez4;->r:Z
// patch: const/4 v0, 0 / return v0 at index 0 of both getters
```

This is a genuinely portable candidate for SpotXero (see the roadmap): both getters delegate to a parent via `h()` and otherwise return a backing boolean field, so a resolver can anchor on *"no-arg boolean getter on a remote-config model class whose body reads a boolean field, called once from the model's `models()` registration"* rather than on names. The `9.1.90.2270` class names (`Lp/ez4;`) do not exist in `9.1.80.2221`; the shape is what has to be re-derived.

---

## 3. What its history says about patching Spotify

Read as commit archaeology (`git log --format='%s%n%b' -- patches/.../spotify`), the whole Spotify effort is five commits on one day, and three of the five are fixes to the patch that preceded them:

| Commit | Content | Lesson |
|---|---|---|
| `e5cb0fb` | feature + 156 lines of `NOTES.md` before any code | The investigation note is written first and reviewed; that is where the target is justified |
| `2e5c225` | `sget-object ... DEFAULT_INSTANCE Lcom/spotify/...` → `...:Lcom/spotify/...;` | A missing colon in a hand-written descriptor is not caught by Kotlin; it is caught by a static smali check |
| `f0f4cc3` | private `DEFAULT_INSTANCE` → parse empty bytes | Probe the actual field/method visibility before committing to an instruction |
| `6d7ba57` | Smart Shuffle feature | Feature is two identical early returns; all the risk is in the anchor |
| `1ded84b` | `filters = emptyList()` never matches → replace with `fieldAccess` filters | See the fingerprint pitfalls below |

There is no Spotify-specific refactor or retarget in the history — the version is pinned once and not chased. That is only affordable because the patch set is two features; the multi-app maintenance cost shows up in the issue tracker instead (§5).

---

## 4. Fingerprint pitfalls — where they map to DexKit

These are the repo's recorded failures, each costing at least one release or device round. None of them is a Morphe-only problem; each has a runtime-resolution analogue.

| Pitfall (recorded) | Root cause | Analogue for SpotXero's DexKit resolvers |
|---|---|---|
| `filters = emptyList()` never matches (`1ded84b`) | Patcher treats a non-null empty filter list as a failed match; only a null/omitted list skips matching. A device run failed with `Failed to match the fingerprint` | An empty constraint set is not "match anything". A resolver with no constraints must be rejected at construction, not resolved to the first class in the APK |
| Filters are consumed in instruction order (`a2cbe40`, `CallHistorySyncWorkerFingerprint`) | The filter cursor is forward-only, so anchors declared out of order never match | If a resolver applies successive `usingStrings` calls in one pass, they inherit the same ordering trap; if it matches strings independently, the difference has to be documented, not assumed |
| `instanceOf` filter on a method containing `check-cast` (`v0.6.0-dev.31`) | The opcode is not the semantic test | Constraints must be phrased on the operation you mean (`check-cast` vs `instance-of` are different bytecodes), never on a related one |
| SDK signature used as the DEX signature (ADM notes) | `MenuItem.setShowAsAction(I)` is `(I)V` in the build, `(I)Landroid/view/MenuItem;` in the SDK | Resolve against the APK, not against the Android SDK. A DexKit query copied from SDK javadoc can match nothing |
| `replaceInstructions` deleted the trailing instructions (`v0.2.1`–`v0.3.3`) | Off-by-one in the patch DSL | Not applicable at runtime — but the same reasoning applies to `.before {}` vs `.after {}`: pick by where the value exists, not by habit |
| `invoke-*/range` register count ≠ parameter count (`3rc`), `VerifyError` on device | Hand-written count in a shared helper | Not applicable, but it is the canonical example of "compiles cleanly, fails at load" |
| `newLabelForIndex` bound to the post-insertion index (`VerifyError`) | Label attached to a shifting instruction | Not applicable; the lesson generalizes to any index-based API: bind to identities, not offsets |

The repo's own conclusion about static checks is worth quoting into any verification plan: *"a check that is wrong in the strict direction is not a safety net"*, and *"these two checks cover the defects that were mechanically checkable. The rest still needs a device."*

---

## 5. Issues and what they signal

Four open issues at analysis time, none closed, none with a maintainer reply:

| # | Title | Signal |
|---|---|---|
| 7 | ADM app crash & update problem | A pinned target that then updates in the Play Store crashes for users; every version is a new fingerprint pass |
| 8 | Truecaller selectable Premium plans | The usual "make server-gated entitlement selectable" request; same class of request morphe-patches closed as unachievable (#1/#4/#5) |
| 9 | Port patches from another repo | Users treat a patch collection as a feature backlog |
| 10 | 1DM update to v20.0 | Version-chasing is the dominant maintenance load; the only comment is a link to a third-party fork that did it |

For SpotXero this is the clearest argument for semantic resolution: the maintenance model behind these issues is "re-fingerprint the app after every update", and the issue queue shows what happens when that work is not done. A runtime resolver that fails closed at startup at least fails once, visibly, instead of silently matching the wrong method.

---

## 6. What to take, and what not to

**Take:**

- **The investigation-note gate.** Write the target, the register/field touched, the fingerprint constraints and the open questions into a note, then get review before writing code. This repository's docs are the equivalent; keep them per-version and keep the "uncertainty" sections honest.
- **Anchor on field access and structural shape, not just strings.** `fieldAccess(Lp/ez4;->q:Z)` is stronger than `name = "r"`, and it is the exact discipline SpotXero's AGENTS.md asks for ("match semantic invariants, not obfuscated identity").
- **Validate resolution against a real APK before shipping.** `resolve_fp.py` exists because two releases shipped fingerprints that compiled and never matched. SpotXero's analogue is resolving every feature's target against the installed APK and failing closed with a useful message.
- **Three-state matching.** Their checker first reported "all fingerprints resolve" because it collapsed "not the kind of instruction I inspect" into "satisfied". A DexKit resolver needs the same distinction: not-found, ambiguous, matched.
- **Record the private-field and signature traps** in the feature note (e.g. `DEFAULT_INSTANCE` private → use the `[B` parse). They cost one release each.

**Don't take:**

- **Version pinning.** Single-`AppTarget` compatibility is a deliberate choice there; it makes the Spotify surface small but converts every Spotify release into re-derivation work, as issue #10 shows.
- **The README wording.** "Disable ads" / "no server-provided audio ad is used" describes the fetch, not the audible break. SpotXero's own docs and UI must say "blocks ad fetching" for this hook.
- **The static-check machinery.** `patch_smali_checks.py` and friends solve problems that only exist when you emit smali at build time. A runtime module has no such artifact; its equivalent is resolver validation, which AGENTS.md already requires.

---

## 7. Coverage of this note

Read: `patches/src/main/kotlin/app/spotify/**`, `reference/NOTES.md` (Spotify and ADM sections, patch pitfalls, build environment), `roadmap.md`, `AGENTS.md`, `README.md`, `CHANGELOG.md`, `tools/checks/README.md`, `git log` with bodies for the Spotify path and selected repository-wide commits (`b5530f9`, `1875509`), and issues #7–#10 with comments.

Not done: no APK for `9.1.90.2270` was downloaded, so none of its class names or instruction indices were independently verified; no patch was built or applied; the Smart Shuffle candidate's shape is taken from the source comments, not from a DEX.
