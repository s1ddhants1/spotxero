# Morphe → SpotXero Porting Overview

## What Is Morphe?

[morphe-patches](file:///home/s1ddhants1/Projects/spotify/morphe-patches) is a **static bytecode patcher** for Spotify (Spicetify for Android). It works like ReVanced:

1. **Patch-time** (offline, on a PC): Kotlin `bytecodePatch` / `resourcePatch` blocks rewrite the APK's DEX and resources before the user installs it.
2. **Runtime** (on-device): Java "extension" classes, merged into the APK as a separate DEX (`settings.dex` / `spotify.mpe`), contain the logic those injected call-sites dispatch to.

The patcher matches obfuscated classes and methods by **exact shape**: hardcoded obfuscated names (`Lp/tkd0;`, `Lp/joz0;`, `Lp/c8j0;`), specific instruction indices, opcode patterns, field references, and `.properties` ABI snapshot digests — all locked to **Spotify v9.1.80.2221**.

## Repo Reality (verified 2026-10-30)

Facts from the repository, its git history and its issues that bound every port below:

- **One target build.** `spotifyCompatibility` declares a single `AppTarget`: Spotify `9.1.80.2221`, `versionCode` `145767611` for `arm64-v8a` only, `apkFileType = APKM`, `isExperimental = true`. Anything else is refused by design, so a port cannot be "version-resilient" in the way SpotXero's DexKit resolution is.
- **Every patch depends on the settings patch.** `settingsPatch` is the unnamed base: it merges `extensions/spotify.mpe` and `extensions/settings.dex`, validates the settings ABI snapshot, appends the Spicetify settings entry to Spotify's settings list, and calls `PatchSettings.initialize` at the start of `SpotifyApplication.onCreate`. Feature patches only `dependsOn(settingsPatch)` and flip their toggle with `enableSetting("...")` (a `const/4 v0, 0x1` in the extension's `InstalledPatches`).
- **The ABI snapshot is semantic, not byte-based** since commit `6741682`, which fixed issues #15, #17 and #18. `NativeSettingsAbi.digest()` is a SHA-256 of a text description of each class — flags, supertypes, annotations, and each field and method with registers, instructions and try blocks, sorted so member order doesn't matter; debug info and parameter names are excluded. Before that, the pins hashed the bytes dexlib2 laid out, so patcher 2.x's newer dexlib2 stopped 26 of 32 settings pins from matching and every patch refused the correct APK with `Spotify settings ABI changed: …`. The same 134 classes digest identically on patcher 1.14.1 and 2.0.0-dev.11/13; the 138 pins in the seven snapshot files were regenerated. **Release v1.2.0 predates this fix**, which is why the open issues still describe it.
- **Premium unlock is closed as unachievable client-side** (issues #1, #4, #5). Spotify gates the catalogue on Play Integrity attestation tied to the app's signing certificate; the maintainers report spoofed client identity, skipped attestation, faked version and telemetry were all rejected. Do not budget work for it in SpotXero.
- **Each patch states its own exclusions** in its description, and the wording is worth carrying across: brand ads — "Does not suppress audio ads, player ads, or upgrade prompts. Experimental."; player ad cards — "Does not suppress audio ads or other player overlays. Experimental."; Premium tab — "Does not change your subscription or remove other ads." Port the caveat, not just the mechanism.

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

Doc numbers follow the files in this directory, not the upstream patch order.

| Doc | Morphe Patch | Complexity | Porting Feasibility | Hook Surface |
|---|---|---|---|---|
| [01](01-clean-sharing-links.md) | **Clean sharing links** | Low | ✅ Easy | Builder by string fingerprint + `GenerateUrlResponse` constructor; pure URL logic |
| [02](02-hide-premium-tab.md) | **Hide Premium tab** | Low | ✅ Easy | Consumer of the tab flag, resolved by opcode sequence |
| [03](03-hide-brand-ads.md) | **Hide Home/Browse brand ads** | Medium | ✅ Feasible | One static getter returning the panel string |
| [04](04-hide-player-ad-cards.md) | **Hide player ad cards** | Medium | ✅ Feasible | Cards gate + cards-group builder, found by ad-metadata strings |
| [05](05-home-pins.md) | **Pin shortcuts on Home** | High | ⚠️ Complex | Home section-model constructor; PlayerBridge for the picker only |
| [06](06-theme-colors.md) | **Theme colors** | Very High | ⚠️ Complex | Resource patches (`overlayable.xml`), Compose colors, raw color tables |
| [07](07-spicetify-extensions.md) | **Spicetify extensions** (Trash Bin, Random Song, Shuffle+, Hide Podcasts) | Very High | ⚠️ Very Complex | 12+ hook sites, menu injection, Cosmos router bridge, proto-heavy |
| [08](08-local-files-server.md) | **Local files from server** | Very High | ❌ Impractical | Manifest-registered ContentProvider in a second process |
| [11](11-audio-ad-blocking.md) | *(no Morphe equivalent)* | Very High | 🔬 Native-only | Break is played by `liborbit-jni-spotify.so`; Java hooks block ad fetching only. Offline patch sets free-tier clips per break to 0 — loads and plays on device, break effect unobserved |

Every row above also `dependsOn(settingsPatch)`; the settings patch itself is not user-selectable and has no SpotXero equivalent, because SpotXero ships its own settings app and RemotePreferences instead of injecting a settings screen into Spotify.

## Second Reference Implementation

[oyasumi-patches](https://github.com/dunecache/oyasumi-patches/tree/dev/patches/src/main/kotlin/app/spotify/patches) is an independent multi-app Morphe patcher with only two Spotify patches — `Disable ads` (empty `GetAdsResponse`) and `Disable Smart Shuffle default` — pinned to `9.1.90.2270` as a regular APK. It is analysed in [12-oyasumi-cross-check.md](12-oyasumi-cross-check.md).

Read it for two things: its `Disable ads` patch is the build-time twin of SpotXero's `AdResponseHook` and confirms that the same seam does not stop an audible break, and its history records fingerprint and verifier failures that translate into rules for DexKit resolvers (empty constraint sets, three-state matching, validating against the real APK).

## Recommended Porting Order

Phases 1–3 have since landed in the module; [10-implementation-roadmap.md](10-implementation-roadmap.md) records what shipped and where the implementation deviates from these plans.

### Phase 1 — Quick Wins (1–2 days each)
1. **Clean sharing links** — Morphe already has a fingerprint-based approach; easy to replicate with DexKit string search. Both sites are needed: the local builder and the server-response constructor.
2. **Hide Premium tab** — One consumer hook, but restart-required and shape-sensitive.

### Phase 2 — Ad Blocking (2–3 days each)
3. **Hide Home/Browse brand ads** — One string getter, not a list filter.
4. **Hide player ad cards** — Boolean gate plus a recomposed card group.

### Phase 3 — Complex Features (1–2 weeks each)
5. **Pin shortcuts on Home** — Needs Cosmos router bridge ported.
6. **Theme colors** — Needs alternative to resource patching (RRO overlay or reflection).

### Phase 4 — Deep Integration (research required)
7. **Spicetify extensions** — Massive scope; consider selective sub-features.
8. **Local files from server** — Likely impractical without resource modification capabilities.

---

**Detailed docs for each patch follow in the numbered files in this directory.**
