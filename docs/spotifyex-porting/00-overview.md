# SpotifyEx Stack → SpotXero Porting Overview

## What Is This Stack?

`app.apk` (user-supplied, analysed 2026-11-02) is a **shipped LSPatch-packed Spotify**: the original
Spotify client repackaged so that two Xposed modules are embedded inside the APK and executed by an
LSPatch loader at process start. Nothing is patched into Spotify's smali — every behaviour change
ships as a runtime hook loaded under `assets/lspatch/`.

Artifact identity (all values verified with build-tools 37 `aapt` / `apksigner`):

| Field | Value |
|---|---|
| Package | `com.spotify.music` |
| versionName | `9.1.94.1912` |
| versionCode | `2056466434` |
| minSdk / targetSdk | 28 / 37 |
| Outer signer | `CN=xiawanli, OU=aa, O=aa, L=aa, ST=aa, C=CN` (SHA-256 `c081890cf2a1adf13e56d7b50a4f3d8edb35b7c46d6ccc732dd997b7e433be1d`) |
| Inner original-signer | AOSP test key (`CN=Android`, `android@android.com`) per `assets/lspatch/config.json` `originalSignature` |
| Loader | LSPatch API_CODE `102`, core `canary-3106` (7306, `e00c5c50…989`), embedded loader `1.2` (487), `sigBypassLevel: 3`, `useManager: false`, `injectDex: false` |
| SHA-256 (full APK) | `1a30c97b11e9c351578bc7ab4a4e0268477ccac45e801c0c9f9f6333eb328056` |

The two embedded modules (both bundle `libdexkit.so`, i.e. both resolve obfuscated targets at runtime):

| | Module 1 — SpotifyEx | Module 2 — companion (self-logs as "L3") |
|---|---|---|
| xposed_init entry | `io.github.cloudburst.spotifyex.Module` | `e.va` (R8-obfuscated) |
| Package / label | `alzjcncmzlaoshxxksksoaoajdkfkfkdosisisskdowplwmxcjcuvkvmlsapuwie.xd` / scrambled label | `xnzkapwudkldlskajjskxkckfkeksososw.issoowdkddd.xd` / label contains "… spotify" |
| Version | `1.0.50` (versionCode 10000) | versionCode 33, versionName `202619188283` |
| Code | 45 KB dex, 50 smali classes, no resources | 1.5 MB dex, 1287 smali classes, own resources + in-Spotify settings rows ("Revanced Xposed FE Settings", keys `enable_premium`, `enable_adblock`) |
| Build stamp | log date long `0x197d0e78bda` = 2025-07-03 20:58:45 UTC | none observed |

No public repository for either module was located (GitHub search for `spotifyex + cloudburst` returns
nothing); package names and labels are deliberately randomised, so every statement below cites the
decoded smali of the artifact, not upstream source.

## Why In-Order Playback Depends On These Two Inputs

The stack exists to make Spotify play a queue **in order** on an account whose plan would otherwise
force shuffle behaviour. Upstream Spotify (free tier) enforces shuffle-only playback through two
server-controlled inputs that the client implements faithfully:

1. **Product-state account attributes** — a protobuf map of `AccountAttribute` entries
   (`com.spotify.remoteconfig.internal.ProductStateProto` class family; descriptor strings
   `internal.AccountAttribute`, `value_`, `ProductStateProto` all appear in the shipped client and in
   both modules). Keys such as `shuffle`, `pick-and-shuffle`, `on-demand`, `streaming`,
   `player-license` decide whether the client will play linearly, force shuffle-only, or upsell.
2. **`spotify:station:` contexts** — free-tier entry points hand back randomized station contexts
   instead of the playlist URI the user tapped.

Both modules attack (1); module 2 additionally attacks (2). None of them touch the playback engine:
no `PlayerOptions`, session or queue internals are resolved by module 1 at all, and module 2's player
surface is exactly one stable AutoValue class name (`PlayerOptionOverrides`).

## Mechanisms Inventory

| Doc | Mechanism | Observed in | Complexity | SpotXero coverage today |
|---|---|---|---|---|
| [01](01-product-state-attribute-spoof.md) | Product-state `AccountAttribute` spoof — 12 keyed rewrites of the typed `value_` field | Both modules, independently | Low | Partial — `ProductStateHook` rewrites a string map, not the typed attribute objects, and misses 7 of the 12 keys |
| [02](02-player-option-overrides-force.md) | `PlayerOptionOverrides$Builder.build()` forced to `shufflingContext(false)` | Module 2 (`ix.smali`, `f1.smali`) | Low | Partial — `PlayerShuffleHook` hooks the builder setter/getter and the sibling `PreparePlayOptions$Builder.build()`; no catch-all on the `PlayerOptionOverrides$Builder` itself |
| [03](03-station-destationing.md) | `spotify:station:spotify:…` → `spotify:…` URI rewrite on hooked results | Module 2 (`p10.smali` + `oo.smali`) | Low | **Absent** — no station handling exists in the module |
| [04](04-supporting-mechanisms.md) | Session preservation, upsell/messaging removal, section-type filtering, query-parameter & device-capability interception | Both | Mixed | Varied; several items marked underanalysed |

## SpotXero Deltas At A Glance

Verified against `app/src/main/kotlin/app/spotxero/hooks/` (2026-11-02):

- **ProductStateHook** injects `type`, `catalogue`, `ads`, `pick-and-shuffle`, `horizon`,
  `streaming-rules`, `shuffle-restricted`, `unlimited-skips`, `on-demand`, `offline` — all as strings —
  over the product-state **map** seam (`DexKitResolver.KEY_PRODUCT_STATE_MAP`). The stack instead
  rewrites the **proto attribute objects** (`AccountAttribute.value_`) with typed values
  (booleans, strings) and additionally covers `player-license`, `player-license-v2`, `shuffle`,
  `nft-disabled`, `can_use_superbird`, `tablet-free`. Of those six, `shuffle` and the two
  `player-license` keys are the direct shuffle/order gates in the client.
- **PlayerShuffleHook** already resolves the shuffle builder/getter surface far beyond the stack
  (`KEY_PLAYER_OPTION_OVERRIDES_BUILDER_SHUFFLE`, `…_SHUFFLE_GETTER`, prepare-play overrides,
  esperanto `SetShufflingContext`/`SetOptions` dispatch), and it already hooks the sibling
  `PreparePlayOptions$Builder.build()` chokepoint. The only unimplemented idea from module 2 is
  hooking `PlayerOptionOverrides$Builder.build()` itself as a last-resort catch-all for any
  construction path no setter hook covers.
- **No hook anywhere** converts `spotify:station:` contexts back to their base context (doc 03) —
  the one mechanism in the stack with no SpotXero equivalent.
- SpotXero keeps shuffle **user-controllable** (`PrefKeys.KEY_SHUFFLE_STATE`); the stack unconditionally
  forces shuffle off whenever its "Enable Premium" path is active. Any port must preserve SpotXero's
  user-toggle semantics rather than copy the forced-off behaviour.

## Recommended Porting Order

1. **Extend the product-state spoof** (doc 01) — add the missing keys and the second (typed-object)
   seam to `ProductStateHook`. Lowest risk, biggest effect on "queue plays in order".
2. **`build()` catch-all** (doc 02) — small, additive to the existing override hooks.
3. **Station de-stationing** (doc 03) — net-new feature, needs its own `PrefKeys` entry and careful
   limiting to station-originated play contexts so genuine radio intent still works.
4. **Supporting mechanisms** (doc 04) — session preservation and device-capability interception are
   marked **not worth porting** until dynamic verification proves the gaps; messaging/upsell removal
   is a UX-only win.

Everything here inherits the constraints in `AGENTS.md`: no hardcoded obfuscated symbols, fail closed
on unresolved or ambiguous fingerprints, validate resolved elements before hooking, and keep changes
minimal. Cross-reference the general rules in `docs/morphe-porting/00-overview.md` (in particular its
"Premium unlock is unachievable client-side" conclusion — none of these mechanisms claim premium
entitlement server-side; they only change what the local client believes and builds).

## Verification Status

**Static analysis only — counterfactual claims about playback were not exercised on a device.** Every
mechanism described in docs 01–04 is supported by quoted smali from the decoded APK; how each behaves
under real traffic (and whether module 2's query-parameter and device-capability interception admits
or blocks requests) remains to be observed. Before trusting a port, attach the LSPosed/LSPatch process
on a device and watch the hook logs, per the runtime-verification bar in `AGENTS.md`.

## Reproduce The Analysis

```bash
aapt dump badging app.apk | grep -E "^package:|sdkVersion"
apksigner verify --print-certs app.apk
unzip -o app.apk "assets/lspatch/*" -d payload/
aapt dump badging payload/assets/lspatch/modules/*.xd.apk | grep -E "^package:"
java -jar apktool.jar d -f --no-res -o mod1_out payload/assets/lspatch/modules/<module1>.xd.apk
java -jar apktool.jar d -f --no-res -o mod2_out payload/assets/lspatch/modules/<module2>.xd.apk
```

Anchors to re-check after a Spotify repack: `"internal.AccountAttribute"`, `"value_"`,
`"ProductStateProto"`, `"com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides$Builder"`,
`"shufflingContext"`, `"spotify:station:"`, `"featureTypeCase_"`, `"sectionTypeCase_"`,
`"checkDeviceCapability="`, `"Revanced Xposed FE Settings"`, `"Failed to patch account"`.
