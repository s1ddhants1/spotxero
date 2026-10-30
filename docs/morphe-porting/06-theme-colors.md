# Patch 6: Theme Colors

**Morphe source**: [`ThemePatch.kt`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/theme/ThemePatch.kt), [`ThemeComposePatch.kt`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/theme/ThemeComposePatch.kt), [`ThemeResources.kt`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/theme/ThemeResources.kt)
**Runtime code**: [`theme/`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/extensions/extension/src/main/java/app/spicetify/extension/spotify/theme/) — 11 Java files (SpicetifyTheme, ThemeRuntime, ThemeOverlay, ThemeRoleMap, ComposeTheme, ColorTable, ThemeTable, ThemeState, ThemePresets, ThemeResolver, ThemeBackground, ArgbColors)

**Porting difficulty**: ⚠️ Complex — requires alternative approaches to resource patching
**Status in SpotXero**: 🟡 AMOLED only — `hooks/theme/SpotifyAmoledHook.kt` intercepts both `Resources.getColor` overloads and maps Spotify's dark greys to black. Layers 2 and 3 (role table, Compose palette/raw colors/surface/scrim/icon tint) and the configuration UI are unported.

---

## What Morphe Does

The theme system has three layers:

### Layer 1: Resource Patching (`ThemeResources.kt` + `themeResourcesPatch`)
- Reads Spotify's `res/values/colors.xml` to map color resource names to their stock ARGB values.
- Reads a role map (`theme/9.1.80.2221.properties`) that maps Spicetify theme roles (`main`, `text`, `button`, etc.) to Spotify color resource names.
- **Modifies `res/values/overlayable.xml`** to declare every mapped color as overlayable, enabling the RRO (Runtime Resource Overlay) system.
- Builds two encoded tables:
  - `roleTable`: `role:name,name@AA|role:...` — maps roles to resource names with original alpha.
  - `composeTable`: `path=name@AARRGGBB,...;path=...` — maps Compose field paths to resources and stock colors.

### Layer 2: Bytecode Injection (`ThemePatch.kt`)
- Injects the pre-built role table and compose table into placeholder methods in the extension classes:
  ```kotlin
  injectTable(ROLE_MAP_CLASS, "encoded", roleTableForExtension)
  injectTable(COMPOSE_THEME_CLASS, "table", composeTableForExtension)
  ```
  Each replaces a `const-string v0, ""` with the actual table data.

### Layer 3: Compose Theme Hooks (`ThemeComposePatch.kt`)
Six fingerprint-based hook sites in Compose rendering code:

1. **ProvideEncoreTheme** — Replaces the default dark Encore palette with the themed one via `ComposeTheme.palette(Object)`.
2. **EncoreThemeDefaults** — Same palette replacement at the CompositionLocal default.
3. **EncoreRawColors** — After raw colors are initialized (`<clinit>`), calls `ComposeTheme.primitives(Object)` to override them.
4. **Header scrim** — Adjusts scrim alpha for background images via `ComposeTheme.scrimAlpha(F)F`.
5. **Encore icon tint** — Overrides icon tint colors via `ComposeTheme.iconTint(J)J`.
6. **Surface fields** — Two `<clinit>` methods that store `Color(0xFF282828)` are hooked to replace the surface color.

### Runtime Logic (11 classes)
- **`ThemeRuntime`**: Installs the overlay, listens for theme changes, rebuilds on settings update.
- **`ThemeOverlay`**: Creates and registers an RRO overlay APK at runtime (Android 11+).
- **`ThemeRoleMap`**: Decodes the role table, maps theme colors to resource names.
- **`ComposeTheme`**: Intercepts Compose palette, primitives, scrim, icon tint, surface.
- **`ThemePresets`**: Built-in themes (OLED black, dark gray, etc.).
- **`ThemeResolver`**: Resolves theme configuration from settings.
- **`ColorTable`/`ThemeTable`**: Parse and apply the encoded color tables.

---

## Xposed Porting Strategy

### The RRO Challenge

Morphe can modify `overlayable.xml` because it rewrites the APK. An Xposed module **cannot** modify resources in the target APK. Options:

**Option A: Runtime RRO Overlay (ThemeOverlay approach)**
The Morphe runtime already builds an RRO overlay APK dynamically. This approach can work from Xposed too:
1. The module generates a small APK containing only color resource overrides.
2. Registers it as a self-overlay via the hidden `OverlayManager` API.
3. Requires the colors to be declared `overlayable` in the target — which they won't be without the resource patch.

**⚠️ Problem**: Without modifying `overlayable.xml`, the RRO overlay can't override Spotify's colors. This is the fundamental blocker.

**Option B: Reflection-Based Color Replacement**
Instead of resource overlays, hook the color resource loading:
```kotlin
// Hook Resources.getColor(int, Theme) to intercept color lookups
module.hook(Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType, Resources.Theme::class.java))
    .after { param ->
        val resId = param.args[0] as Int
        val replacement = themeColorMap[resId]
        if (replacement != null) param.result = replacement
    }
```
This is less efficient but works without resource modification.

**Option C: Hook TypedArray/ColorStateList**
More targeted — hook where Spotify reads colors from XML attributes:
```kotlin
// Hook TypedArray.getColor() for XML-defined colors
// Hook ColorStateList loading for stateful colors
```

### Compose Theme Hooks (Directly Portable)

The Compose hooks are pure bytecode interception — these port cleanly to Xposed:

```kotlin
// 1. ProvideEncoreTheme — find via string "EncoreLayoutTheme: Invalid screen dimensions ("
val provideEncoreTheme = dexKit.findMethod {
    matcher {
        usingStrings("EncoreLayoutTheme: Invalid screen dimensions (")
    }
}

// 2. EncoreThemeDefaults — find via string prefix
val encoreDefaults = dexKit.findMethod {
    matcher {
        usingStrings("Encore theme was not provided. Please wrap your content with ProvideEncoreTheme.")
    }
}

// 3. EncoreRawColors — static constructor with literal constants
val rawColors = dexKit.findMethod {
    matcher {
        modifiers(Modifier.STATIC)
        name("<clinit>")
        usingNumbers(0xFF181818L, 0xFF333333L, 0xFF535353L)
    }
}

// 4-6: Icon, scrim, surface hooks via fingerprints on method signatures
```

### Hook Implementation for Compose

```kotlin
// Replace dark palette at both reads
module.hook(provideEncoreThemeMethod).after { param ->
    // Find the static field read of the dark palette, replace it
    // This requires hooking the palette getter, not the method itself
}

// For the raw colors clinit — hook the class initializer
module.hookClassInitializer(rawColorsClass).after {
    // Read the static field, apply theme overrides
    val field = rawColorsClass.getDeclaredField("a") // or whatever holds the colors
    field.isAccessible = true
    val colors = field.get(null)
    ComposeThemeOverride.applyPrimitives(colors)
}

// Surface color replacement
for (surfaceField in surfaceFields) {
    module.hookClassInitializer(surfaceField.declaringClass).after {
        surfaceField.isAccessible = true
        surfaceField.setLong(null, themedSurfaceColor)
    }
}
```

---

## Key Differences from Morphe

| Aspect | Morphe | SpotXero Port |
|---|---|---|
| XML resource colors | Modified via `overlayable.xml` + RRO overlay | Must use reflection-based color interception |
| Color table injection | Replaces placeholder `const-string` in extension DEX | Tables built and stored in module at compile time or in prefs |
| Compose palette | Fingerprint → smali injection after palette read | `.after {}` hook on fingerprinted methods |
| Raw color override | Hooks `<clinit>`, inserts after the store | `hookClassInitializer().after {}` |
| Surface color | Hooks `<clinit>` at specific `const-wide` + `sput-wide` | `hookClassInitializer().after {}` + field reflection |
| Android version | Requires Android 11+ (for RRO) | Reflection approach works on all versions |

## Risk Assessment
- **High risk**: The RRO/overlayable approach is fundamentally blocked without resource modification.
- **Medium risk**: The reflection-based `Resources.getColor()` approach has performance implications (called frequently) and may miss some color sources.
- **Low risk**: Compose hooks are fingerprint-based with stable string markers — very portable.
- **Note**: SpotXero already has a `KEY_SPOTIFY_AMOLED` preference and `SpotifyAmoledHookTemplate` — a simpler AMOLED-only theme could be implemented first as a stepping stone.

## Recommendation
1. Start with an **AMOLED-black theme** that only overrides the most visible background colors via `Resources.getColor()` hooks and Compose palette replacement.
2. Expand to full theming once the color interception framework is proven.
3. The Compose hooks (palette, raw colors, surface, scrim, icon tint) are the most valuable part to port first — they cover the majority of visible Compose screens.
