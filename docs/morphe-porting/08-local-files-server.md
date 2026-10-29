# Patch 8: Local Files from a Server

**Morphe source**: [`LocalServerPatch.kt`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/patches/src/main/kotlin/app/spicetify/patches/spotify/localfiles/LocalServerPatch.kt)
**Runtime code**: [`localserver/`](file:///home/s1ddhants1/Projects/spotify/morphe-patches/extensions/extension/src/main/java/app/spicetify/extension/spotify/localserver/) — 19 Java files

**Porting difficulty**: ❌ Impractical — requires manifest modification, separate process, ContentProvider registration

---

## What Morphe Does

This is the most infrastructure-heavy patch. It streams audio from HTTPS WebDAV or Jellyfin servers into Spotify's "Local Files" feature.

### Resource Patches (Blocker for Xposed)
The `serverResourcesPatch` **modifies AndroidManifest.xml** to register:

1. A `ContentProvider` (`ServerFileProvider`) running in a separate process (`:spicetify_server`):
   ```xml
   <provider android:name="...ServerFileProvider"
             android:authorities="com.spotify.music.spicetify.localserver"
             android:process=":spicetify_server" />
   ```

2. An `Activity` (`ServerMusicActivity`) for browsing server music:
   ```xml
   <activity android:name="...ServerMusicActivity" android:exported="false" />
   ```

### Bytecode Hooks (10+ sites)

1. **MediaStoreReader.runQuery()** — Appends server files to the local file query results.
2. **MediaStoreReader.startListening(J)** — Captures the reader instance for server file change notifications.
3. **MediaStoreReader.stopListening()** — Cleans up when listening stops.
4. **SpotifyApplication.onCreate()** — Early-returns if in the `:spicetify_server` process (skips Spotify startup).
5. **SpotifyApplication.onTrimMemory()** — Same early-return for the server process.
6. **Your Library request (Lp/ub21;->z)** — Appends server library rows, or answers our own chip's request entirely.
7. **Your Library chip row (Lp/aey;-><init>)** — Adds a "Server files" chip to the library filter row.
8. **Chip labels (Lp/igy;->a, Lp/igy;->b)** — Provides label/description text for the server chip.
9. **URI navigation (Lp/s4h0;->b, ->g)** — Intercepts `spicetify:server:` URIs to open the server browser.
10. **Local file artwork (LocalFileImageLoader.loadImage)** — Returns Jellyfin album art for server tracks.
11. **Remembered chips (Lp/ldy;->b)** — Prevents the server chip from being "remembered" by Spotify.
12. **Player command (Lp/s2w;-><init>)** — Captures the player instance for server playback commands.

### Runtime Logic (19 classes)
- **`ServerFileProvider`**: ContentProvider in its own process that serves audio tracks via file descriptors.
- **`ServerProcess`**: Manages the separate process lifecycle, skips Spotify init in server process.
- **`LocalServerHook`**: Intercepts MediaStoreReader to inject server files.
- **`LibraryRows`**: Adds server content to Your Library using RxJava Observables.
- **`ServerConfig`**: Manages WebDAV/Jellyfin server configuration.
- **`JellyfinClient`/`JellyfinConnection`**: Jellyfin API client.
- **`WebDav`**: WebDAV protocol client.
- **`MusicCatalog`/`ServerIndex`/`IndexCache`**: Indexes server music library.
- **`RemoteTrack`/`BrowseMetadata`**: Track/album metadata from server.
- **`ServerArtwork`**: Fetches album art from server.
- **`ServerPlayback`**: Commands Spotify's player for server tracks.
- **`ServerConnection`/`StrictRangeReader`**: HTTP connection with byte-range support.
- **`ProviderSession`**: Manages IPC sessions between Spotify's process and the server process.

---

## Why Xposed Cannot Port This

### Fundamental Blockers

1. **Manifest modification is required**: The ContentProvider and Activity must be declared in `AndroidManifest.xml`. An Xposed module **cannot** register new components in the target app's manifest at runtime.

2. **Separate process**: The `ServerFileProvider` runs in `:spicetify_server` — a separate process that requires manifest declaration. Xposed hooks only run in the process they're loaded into.

3. **Content URI authorities**: The `com.spotify.music.spicetify.localserver` authority must be declared in the manifest for `ContentResolver` to route to it.

### Theoretical Workarounds (All Impractical)

| Approach | Why It Fails |
|---|---|
| Register ContentProvider dynamically | Android's `ActivityManager` requires manifest-declared providers; runtime registration via `ActivityThread` reflection is blocked on modern Android |
| Use the module's own process for serving | Xposed module's process is not `com.spotify.music`, so content URIs won't resolve within Spotify |
| Proxy through a separate app | Requires a standalone APK installed alongside the module — loses the single-module simplicity |
| Use LSPatch integrated mode | LSPatch patches the APK at install time, so it *could* modify the manifest — but this is no longer "Xposed hooking", it's hybrid patching |

### Potentially Portable Parts

If the **manifest blockers** were somehow solved (e.g., via LSPatch integration), the remaining bytecode hooks are standard method interceptions that *could* work with Xposed:

- MediaStoreReader hooks → DexKit can find `MediaStoreReader` (named class)
- SpotifyApplication lifecycle hooks → named class, trivial to hook
- Your Library row/chip injection → similar to other list-manipulation hooks
- URI navigation interception → hook the navigator methods
- Artwork loading → hook `LocalFileImageLoader.loadImage` (named class)

---

## Risk Assessment
- **Blocker**: Manifest modification is a hard requirement that Xposed cannot satisfy.
- **Verdict**: This patch is **not portable to pure Xposed hooking**.

## Recommendation
- **Do not attempt** this patch in the SpotXero Xposed module.
- If local server files are desired, consider a **hybrid approach** using LSPatch (which can modify the manifest) combined with the Xposed hooks for the bytecode parts.
- Alternatively, build a **standalone companion app** that serves files and uses a different integration mechanism (e.g., Spotify's own local files directory scanner, if the paths can be faked via symlinks/bind mounts on rooted devices).
