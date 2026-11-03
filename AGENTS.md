# SpotXero Agent Guidelines

## 1. Operational Rules

1. **Zero comments**: Never write comments in production code.
2. **No hardcoded obfuscated symbols**: Never hardcode obfuscated class names, method names, or field names. Resolve them dynamically via DexKitResolver or rely on stable public/proto contracts.
3. **No tests in production**: Do not write tests for the production codebase.
4. **Clean workspace**: Clean up all temporary scripts, scratch files, and dumps immediately after use.
5. **Clean architecture & idioms**: Follow modern Kotlin and Android best practices. Keep changes minimal and concise: install and use the [ponytail](https://github.com/DietrichGebert/ponytail/raw/refs/heads/main/skills/ponytail/SKILL.md) skill to keep work minimal and clean.
6. **Thorough refactoring**: When replacing or refactoring logic, remove all superseded code and accompanying artifacts across all layers. Leave no dead paths or legacy baggage.
7. **Validation**: Validate changes with `./gradlew assembleDebug`. Ensure the build passes before finishing.
8. **Client-side containment & zero server leakage**: Never touch, modify, or spoof server-side entitlement endpoints or outbound network requests. Product state overrides, entitlement spoofs, and feature unlocks must remain strictly local to client-side evaluation. Spoofed product state, subscription attributes, or premium tokens must never leak upstream to Spotify servers or be included in remote RPC, telemetry, or sync payloads.

## 2. Framework & Stack Context

- **Hooking**: Modern LibXposed (API 101/102+), never legacy XposedBridge APIs.
- **Deobfuscation**: Runtime bytecode indexing using DexKit.
- **UI**: Jetpack Compose with Material 3.
- **IPC / Config**: RemotePreferences via PreferencesManager.

## 3. Hooking Philosophy & Methodology

Spotify is an obfuscated target under active R8 churn. Hooks must be resilient to updates:

- **Match semantic invariants, not obfuscated identity**: Short names and numeric instruction offsets are reconnaissance artifacts only. Match stable strings, interface contracts, or proto definitions.
- **Resolve capabilities, not versions**: Detect behavior and structure dynamically from the APK rather than hardcoding version codes.
- **Prefer one deep semantic resolver**: Consolidate logic into one robust resolver rather than accumulating release-specific fingerprints.
- **Fail closed**: An unexpected signature, zero matches, or ambiguous resolution must raise a useful exception; never silently skip required behavior or guess.
- **Validate resolved elements**: Assert returned types and signatures before attaching hooks. "The first iget-object after the key" is not proof.
- **Leave no dangling stubs**: Optional resolvers must never leave live extension placeholders or no-op interceptors behind.
- **Hook the minimal surface at the latest safe point**: Intercept the smallest value at the point of control rather than modifying broad caller pipelines.
- **Confine overrides to local read sites**: Hook only local readers and feature consumers. Never mutate data structures or serialization pipelines destined for outbound RPCs, remote sync, or server telemetry; keep all spoofed entitlements strictly within client memory.
- **Avoid mutating shared utilities**: Do not modify common helpers if they serve unrelated features.

## 4. Refactoring & Complete Logic Replacement

When refactoring or superseding existing logic, cover all bases and completely eliminate the previous implementation across the entire codebase:

- **No dual/dead execution paths**: Eradicate replaced hooks, interception pipelines, and transitional fallback branches completely. Never allow obsolete hooks to run alongside new ones.
- **Purge orphaned constants and helpers**: Delete companion object constants (RPC paths, service names, intent actions), private helper functions, reflection caches, and parameters that only served the retired code.
- **Clean resolver signatures and cache**: Remove unused signature keys (`KEY_*`) and search routines from `DexKitResolver`. Ensure no obsolete cache entries or invalid fallback signatures linger.
- **Prune UI, prefs, and registrations**: If a toggle or feature is retired, remove its entry from `HookRegistry`, `PrefKeys`, settings screens, and `strings.xml`.
- **Audit all reference sites**: Grep for all usages of touched classes, methods, and constants before completing the task to ensure no orphaned references or dead imports remain.

## 5. Standard Spotify Behavior and Runtime Verification

- **Preserve normal Spotify behavior**: All pages and screens must load and respond correctly; navigation, search, library, queue, playback controls, and track changes must behave normally. Content failing to load or populate, "Something went wrong" errors, and unexpectedly empty playlists or screens are unacceptable and must be treated as highest-priority regressions.
- **Protect playback quality**: Tracks must start and continue playing without stalls, unintended skips, or stutter introduced by SpotXero hooks.
- **Verify on a real runtime**: For hook or playback-related changes, exercise the affected flows on an ADB-connected device with SpotXero active. Check representative screens and sustained playback, and inspect runtime logs for crashes, hook failures, or repeated errors.
- **Be evidence-based**: Do not claim full compatibility or successful playback verification unless those behaviors were actually exercised. State device, app version, tested flows, and any coverage limitations; report failures rather than weakening checks or hiding them.
