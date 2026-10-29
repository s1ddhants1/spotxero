# Morphe → SpotXero Porting Overview

## What Is Morphe?

[morphe-patches](file:///home/s1ddhants1/Projects/spotify/morphe-patches) is a **static bytecode patcher** for Spotify (Spicetify for Android). It works like ReVanced:

1. **Patch-time** (offline, on a PC): Kotlin `bytecodePatch` / `resourcePatch` blocks rewrite the APK's DEX and resources before the user installs it.
2. **Runtime** (on-device): Java "extension" classes, merged into the APK as a separate DEX (`settings.dex` / `spotify.mpe`), contain the logic those injected call-sites dispatch to.

The patcher matches obfuscated classes and methods by **exact shape**: hardcoded obfuscated names (`Lp/jb20;`, `Lp/vot;`), specific instruction indices, opcode patterns, field references, and `.properties` ABI snapshot digests — all locked to **Spotify v9.1.80.2221**.

## What Is SpotXero?

[SpotXero](file:///home/s1ddhants1/Projects/spotify/spotxero) is a **modern LibXposed module** (API 101/102+) that hooks Spotify at runtime via DexKit-based dynamic resolution. It:

- Loads into Spotify's process via LSPosed/LSPatch.
- Uses **DexKit** to semantically find target methods at runtime — no hardcoded obfuscated names.
- Intercepts methods via `module.hook(method).intercept { chain -> ... }`.
- Manages settings via **RemotePreferences** (IPC between the module app and the hooked Spotify process).
- Ships a full Jetpack Compose settings UI.

## Fundamental Architectural Differences

| Aspect | Morphe (Static) | SpotXero (Xposed) |
|---|---|---|
| **When** | Build-time, rewrites DEX offline | Runtime, hooks in-memory |
| **How targets are found** | Hardcoded obfuscated names + instruction-index checks + ABI digest snapshots | DexKit runtime search by strings, signatures, proto contracts |
| **How code is injected** | Inserts smali instructions (`addInstructions`) at specific offsets | `hook().before {}` / `.after {}` / `.intercept {}` on resolved `Method` objects |
| **Extension code delivery** | Merged into the APK as a separate DEX | Ships inside the module APK; the module's classloader is available in hooks |
| **Resource patching** | Directly modifies `AndroidManifest.xml`, `colors.xml`, `overlayable.xml` | Cannot modify resources; must use reflection, RRO overlays, or work purely at the API level |
| **Settings** | In-process `SharedPreferences` + injected settings Activity/Dialogs | LibXposed `RemotePreferences` (cross-process IPC) |
| **Version coupling** | Locked to one Spotify version; a new version requires re-fingerprinting | DexKit resolvers survive obfuscation churn if the semantic invariants are stable |

## Patches Inventory

| # | Morphe Patch | Complexity | Porting Feasibility | Notes |
|---|---|---|---|---|
| 1 | **Clean sharing links** | Low | ✅ Easy | String-based fingerprint; pure URL manipulation logic |
| 2 | **Hide Home/Browse brand ads** | Medium | ✅ Feasible | Hook list getters for home/browse sections, filter by discriminator field |
| 3 | **Hide player ad cards** | Medium | ✅ Feasible | Hook image-ad mapper and embedded-ad predicate |
| 4 | **Hide Premium tab** | Low | ✅ Easy | Hook single boolean flag result |
| 5 | **Pin shortcuts on Home** | High | ⚠️ Complex | Requires PlayerBridge/Cosmos integration, Your Library proto parsing |
| 6 | **Spicetify extensions** (Trash Bin, Random Song, Shuffle+, Hide Podcasts) | Very High | ⚠️ Very Complex | 12+ hook sites, menu injection, cosmos router bridge, proto-heavy |
| 7 | **Local files from server** | Very High | ❌ Impractical | Resource patches (Manifest), separate process, ContentProvider, RxJava |
| 8 | **Theme colors** | Very High | ⚠️ Complex | Resource patches (overlayable.xml), Compose color interception, raw color tables |

## Recommended Porting Order

### Phase 1 — Quick Wins (1–2 days each)
1. **Clean sharing links** — Morphe already has a fingerprint-based approach; easy to replicate with DexKit string search.
2. **Hide Premium tab** — Single boolean flag interception.

### Phase 2 — Ad Blocking (2–3 days each)
3. **Hide Home/Browse brand ads** — List filtering hooks.
4. **Hide player ad cards** — Boolean/predicate interception.

### Phase 3 — Complex Features (1–2 weeks each)
5. **Pin shortcuts on Home** — Needs Cosmos router bridge ported.
6. **Theme colors** — Needs alternative to resource patching (RRO overlay or reflection).

### Phase 4 — Deep Integration (research required)
7. **Spicetify extensions** — Massive scope; consider selective sub-features.
8. **Local files from server** — Likely impractical without resource modification capabilities.

---

**Detailed docs for each patch follow in the numbered files in this directory.**
