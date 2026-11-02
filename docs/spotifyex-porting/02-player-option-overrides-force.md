# Mechanism 2: Force `shufflingContext(false)` On Every `PlayerOptionOverrides` Build

**Observed source**: companion module `ix.smali` (hook registration) + `f1.smali` (hook body).
**Target in the client**: `com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides$Builder` — the no-arg `build()` method that materialises the overrides object handed to play commands.
**Porting difficulty**: Low — class and method are **unobfuscated** in 9.1.94.1912, but the port must still not hardcode an assumption R8 can break (see resolution below).

---

## What The Companion Module Does

### The hook (exact registers, `ix.smali` lines 630–657)

```smali
:try_start_3
const-string v1, "com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides$Builder"
iget-object v4, v0, Ljx;->c:Ljava/lang/ClassLoader;
const-string v7, "build"
new-instance v8, Lf1;
const/4 v9, 0x5
invoke-direct {v8, v9}, Lf1;-><init>(I)V
filled-new-array {v8}, [Ljava/lang/Object;
move-result-object v8
invoke-static {v1, v4, v7, v8}, Lde/robv/android/xposed/XposedHelpers;->findAndHookMethod(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/String;[Ljava/lang/Object;)Lde/robv/android/xposed/XC_MethodHook$Unhook;
:try_end_3
.catchall {:try_start_3 .. :try_end_3} :catchall_3
```

The registration sits in its own try/catch; a failed lookup falls through to the next registration
and surfaces under the block's `"L3 Hook Failure: "` tag.

### The body (`f1.smali`, before-hook branch selected by index 5)

`f1` is a shared callback class with a case switch on its construction index. For index 5 the
`beforeHookedMethod` branch does exactly this:

```smali
iget-object p0, p1, ...MethodHookParam;->thisObject:Ljava/lang/Object;
sget-object p1, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
filled-new-array {p1}, [Ljava/lang/Object;
...
const-string v0, "shufflingContext"
invoke-static {p0, v0, p1},
    Lde/robv/android/xposed/XposedHelpers;->callMethod(
        Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;
```

i.e. **before `build()` runs, the module invokes `builder.shufflingContext(false)`** on that same
builder, then lets the original `build()` proceed. Every `AutoValue_PlayerOptionOverrides$Builder`
instance that materialises — regardless of which caller built it — is neutralised: the resulting
`PlayerOptionOverrides` object carries `shufflingContext = false`, so play commands built from it ask
the player for **linear order playback** instead of a shuffled pass.

### How this sits next to the rest of the module

`f1`'s other branches handle unrelated features (a `LogOutPatch` branch blocks
`SharedPreferences.clear()` with the log line `★ L3: Blocked SharedPreferences.clear() to preserve
session`; another branch sets a false result when `args[2].identifier == "ads"`). This is a blanket
hook, deliberately last-resort: it does not try to find every builder-construction site — it
captures them at the one chokepoint that must run for all of them.

---

## Xposed Porting Strategy (SpotXero)

SpotXero already covers most of this surface through `PlayerShuffleHook`:

- `KEY_PLAYER_OPTION_OVERRIDES_BUILDER_SHUFFLE` — the builder's shuffle setter is hooked and rewrites
  the argument to the tracked user state (`chain.proceed(arrayOf(java.lang.Boolean.FALSE))` when
  shuffle is off).
- `KEY_PLAYER_OPTION_OVERRIDES_SHUFFLE_GETTER` — the built object's getter is rewired through
  `buildBooleanOptional(..., isShuffleEnabled())`.
- `KEY_PREPARE_PLAY_OPTIONS_BUILDER_BUILD` — the `PreparePlayOptions$Builder.build()` chokepoint is
  already hooked; when shuffle is off it additionally attaches the `Suppressions` provider sets
  (`mft`, `mft/inject_random_tracks`, `mft/inject_filler_tracks`,
  `mft/inject_track_on_start_play`, `mft/disallow_restart_play`).
- `KEY_PLAYER_OPTION_OVERRIDES_FILTER` — a static
  `(PreparePlayOptions, boolean, boolean) → PlayerOptionOverrides` member is resolved into the
  registry but currently consumed by no hook. Per the dead-path rule in `AGENTS.md`, either prime it
  for reuse in the catch-all below or drop the resolver entry.

### The one net-new piece: the `build()` catch-all

The resolver already declares the unobfuscated constant
(`AUTO_VALUE_PLAYER_OPTION_OVERRIDES_BUILDER`, same family as `GET_VALUES_RESPONSE`), so the entry
parallels `resolvePlayerOptionOverrides`'s neighbors — a new key resolved with a validating predicate
before anything is stored:

```kotlin
const val KEY_PLAYER_OPTION_OVERRIDES_BUILDER_BUILD = "player_option_overrides_builder_build"

private fun resolvePlayerOptionOverridesBuilderBuild(
    classLoader: ClassLoader,
    cached: Map<String, String>?,
    sigs: MutableMap<String, String>,
    members: MutableMap<String, Any>
) {
    val method = cachedMethod(cached, KEY_PLAYER_OPTION_OVERRIDES_BUILDER_BUILD, classLoader, ::isPlayerOptionOverridesBuilderBuild)
        ?: runCatching {
            Class.forName(AUTO_VALUE_PLAYER_OPTION_OVERRIDES_BUILDER, false, classLoader)
                .declaredMethods
                .firstOrNull { isPlayerOptionOverridesBuilderBuild(it) }
        }.getOrNull()
    store(KEY_PLAYER_OPTION_OVERRIDES_BUILDER_BUILD, method, sigs, members)
}

private fun isPlayerOptionOverridesBuilderBuild(method: Method): Boolean =
    method.declaringClass.name == AUTO_VALUE_PLAYER_OPTION_OVERRIDES_BUILDER &&
        method.name == "build" &&
        method.parameterCount == 0 &&
        !java.lang.reflect.Modifier.isStatic(method.modifiers)
```

Then hook exactly like the neighbouring `hookOptionOverrides` members (`module.deoptimize` +
`intercept` + explicit `KEY_ENABLE_PREMIUM` read), reusing the already-resolved
`KEY_PLAYER_OPTION_OVERRIDES_BUILDER_SHUFFLE` member as the value setter so the semantic stays in one
place:

```kotlin
val build = DexKitResolver.get<Method>(DexKitResolver.KEY_PLAYER_OPTION_OVERRIDES_BUILDER_BUILD)
if (build != null) {
    val shufflingContext =
        DexKitResolver.get<Method>(DexKitResolver.KEY_PLAYER_OPTION_OVERRIDES_BUILDER_SHUFFLE)
    module.deoptimize(build)
    module.hook(build)
        .setId("player_option_overrides_builder_build")
        .setExceptionMode(ExceptionMode.PROTECTIVE)
        .intercept { chain ->
            if (!PreferencesManager.isEnabled(PrefKeys.KEY_ENABLE_PREMIUM, true)) {
                return@intercept chain.proceed()
            }
            val builder = chain.getThisObject()
            if (!isShuffleEnabled()) {
                shufflingContext?.invoke(builder, false)
            }
            chain.proceed()
        }
}
```

Notes:

- **Never copy the module's unconditional forcing.** The module applies the forcing whenever its
  Enable Premium row is on, regardless of user state; SpotXero defers to the user shuffle preference
  (`PrefKeys.KEY_SHUFFLE_STATE`, tracked by the set-shuffle command hooks). Gating by
  `isShuffleEnabled()` is what makes this a catch-all rather than a forced state.
- This hook is additive to the existing setter/getter hooks. Keep `AGENTS.md`'s
  "no dual execution paths" in mind when reviewing: the setter hook and the catch-all must agree on
  which one wins (the setter hook stays authoritative; the catch-all only fires when a builder
  reaches `build()` without having passed through a hooked setter).
- `Chain.getThisObject()` is available on the libxposed API 102 surface SpotXero builds against.

### Resolution note (constant name, validated not trusted)

`AutoValue_PlayerOptionOverrides$Builder` and `build` kept their generated names in 9.1.94.1912 and
the constant already sits in the resolver next to its sibling `AUTO_VALUE_PLAYER_OPTION_OVERRIDES`
constants — acceptable to name because it is a generated (not obfuscated) identifier, but never
trusted without the predicate. If the constant lookup resolves to nothing — R8 renamed it — fall
back to DexKit the way `resolvePlayerOptionOverridesFilter` already does: matcher on the stable
`com.spotify.player.model.command.options.PlayerOptionOverrides` schema plus a
no-arg method declared by the Builder class. Anything unresolvable stores no member and the hook
never installs: skipped with a warning, not guessed.

---

## Key Differences From the Companion Module

| Aspect | Companion module | SpotXero port |
|---|---|---|
| Semantics | `shufflingContext` **always** false on build | honours `isShuffleEnabled()` from tracked user state |
| Coverage of builders | every `AutoValue_…Builder.build()` via one hook | same chokepoint, additive to existing setter/getter hooks |
| Failure mode | per-registration try/catch (`:try_start_3` catchall on the AutoValue lookup) with dispatch on boxed hook-payload index | `ExceptionMode.PROTECTIVE` + warning log + skip when the resolver stores nothing |
| Toggle | "Enable Premium" in-app row (`enable_premium`), always applied when on | `PrefKeys.KEY_ENABLE_PREMIUM` + user shuffle preference |

## Risk Assessment

- **Low**: the hook mutates builder state before a pure materialisation; no deserialisation concerns.
- **Medium blast radius**: hooking `build()` touches *every* overrides construction in the process,
  including flows SpotXero's existing hooks intentionally limit. If a future Spotify build funnels
  more command types through this builder, the catch-all could clash with the esperanto payload
  rewrites in `PlayerShuffleHook`. Resolution of "who wins" must be solvent when both hooks see the
  same object (the setter hook forces the setter argument when shuffle is off; the catch-all invokes
  that same already-hooked setter only when shuffle is off, so the two agree).
- Verify on device: queue order after toggling shuffle off/on mid-session, and radio/station flows are
  unchanged (mechanism 03 handles the station case separately).
