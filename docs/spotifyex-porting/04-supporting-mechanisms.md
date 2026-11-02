# Mechanism 4: Supporting Mechanisms (Session, UX, Network)

**Observed source**: SpotifyEx `E.smali` / `b.smali` / `c.smali`; companion module `f1.smali`, `oo.smali`, `p10.smali`, `dl.smali`, `ix.smali`.
**Porting difficulty**: Mixed — two items are explicit UX wins, two are marked **underanalysed**, two are explicitly **not worth porting**. Every claim is assigned one of those labels below.

---

## 4a. Session Preservation (`LogOutPatch`) — *not worth porting (as-is)*

The companion module blocks a `clear` family call on shared preferences with an explicit tag:

```smali
# f1.smali, before-hook branch wired somewhere in the ix hook set
const-string p0, "LogOutPatch"
const-string v0, "\u2605 L3: Blocked SharedPreferences.clear() to preserve session"
invoke-static {p0, v0}, Landroid/util/Log;->w(...)I
iget-object p0, p1, ...MethodHookParam;->thisObject:Ljava/lang/Object;
invoke-virtual {p1, p0}, ...MethodHookParam;->setResult(Ljava/lang/Object;)V
```

Effect: when whatever method is hooked (resolved caller: a `clear()` on the preferences backing the
hooked session state) runs, its result is swallowed with a no-op — the settings that keep the spoofed
product state alive survive a logout/login cycle without the caller noticing.

**Porting call**: do not port. This preserves *hook-written lies* across account switches — the
opposite of clean architecture, easy to break real logout flows, and unnecessary once the product-state
spoof is applied at the seam the client uses to refresh state (doc 01 keeps re-applying on every
attribute read). If Spotlight-quality verification ever shows spoof loss on account switch, solve that
at the refresh seam, not by neutering a platform call.

## 4b. Premium-Upsell & Messaging Removal — *explicit UX win, portable*

Both modules clear the surfaces that would otherwise resurrect shuffle-forcing upsells around the
spoofed attributes:

- SpotifyEx (`E.smali` strings): hooks the messaging SDK's list fetch
  (`com.spotify.messaging.clientmessagingplatform…FetchMessageListRequest` →
  `FetchMessageListResponse` field `messages`) — response `messages` are emptied, so message
  cards (including upsell prompts) never materialise.
- Companion module: fingerprints `pendragonJsonFetchMessageListRequestFingerprint` /
  `pendragonJsonFetchMessageRequestFingerprint` (pendragon is Spotify's client-messaging subsystem
  name — a stable, non-obfuscated identifier).
- SpotifyEx additionally flips remoteconfig boolean flags found by name:
  `remove_ads_upsell_enabled`, `hide_playlist_radio`, `premium_upsell_panel_enabled`
  (`E.smali`, wrapped with the failure tag `"Failed to find boolean flags"`).
- The companion's own settings rows both show the target string — `"Listen in any order, shuffle, or
  Smart Shuffle"` is the upsell line they are removing context for (visible in `dl.smali` beside
  module settings rows `enable_premium` / `Enable Premium`, `enable_adblock` / `Enable AdBlock`).

**Porting call**: port as a small follow-up to doc 01. Resolve the message-list response setter by the
stable subsystem name (`pendragon…`), empty the collection, and flip the three boolean flags the same
way `HidePremiumTabHook`-style flag consumers are handled — behind a single
`PrefKeys` toggle. Low risk: pure content removal, no playback surface touched. Cross-check with
`docs/morphe-porting/02-hide-premium-tab.md`'s lesson: hook the *consumer*, not the shared flag getter,
unless the upsell path is demonstrably the sole caller.

## 4c. Home/Feature Content Filtering — *verified mechanism, unclear target, hold*

Both fingerprint names exist in the module: `homeStructureGetSectionsFingerprint` and
`browseStructureGetSectionsFingerprint`, hooked with a list-filtering body:

```smali
# oo.smali, list-result case
invoke-static {v0, v1}, XposedHelpers;->getIntField(Ljava/lang/Object;Ljava/lang/String;)I  # "featureTypeCase_"
invoke-interface {p0, v0}, Ljava/util/List;->contains(...)Z   # membership in Lf10;->b
invoke-interface {p1}, Ljava/util/List;->iterator() ... iterator.remove()
```

`f10.b` was traced to a list of boxed integers `0x14`/`0x15` (20/21) compared against
`featureTypeCase_` (and an equivalent `sectionTypeCase_` against `f10.c`, whose values were not
fully traced). The mechanism is therefore: **remove specific proto feature/section type cases from
pooled home/browse section lists**. Which Spotify proto enum values 20/21 correspond to cannot be
determined from the smali alone; it takes a dynamic pass against a real client to map them to card
types.

**Porting call**: hold. Until the filtered type numbers are identified against a real Spotify proto
snapshot, this feature can't be specified cleanly, and a wrong guess would hide the wrong cards.
Record for a dynamic pass — hook, log the section names attached to filtered items, map the numbers.

## 4d. Network-Layer Interception — *underanalysed, hold*

Three related items observed, all with anchors quoted so the ports can be re-derived later:

- **OkHttp chain proceeds**: the module loads `okhttp3.Response$Builder`, `okhttp3.ResponseBody`,
  `okhttp3.MediaType` as class-name anchors, then scans methods named `proceed` with a single
  parameter (the `Interceptor.Chain.proceed(Request)` shape) and hooks them
  (`buildQueryParametersFingerprint` in `ix.smali`). What modifications are applied to the built
  requests was not traced end-to-end in this pass.
- **Device-capability check**: a hook whose callback (`oo`) reads the method's result `toString()`,
  checks `contains("checkDeviceCapability=")` and restores the original invocation behaviour via
  `XposedBridge->invokeOriginalMethod(Member;Object;[Object)…` — an interception point at some
  device-capability check, result reported back unchanged in the observed branch.
- **SpotifyEx ad-endpoint blocking** (this one *is* well-understood, and its seam differs from
  SpotXero's): hooks Spotify's native HTTP layer by class name anchors `com.spotify.core.http.NativeHttpConnection` / `com.spotify.core.http.HttpRequest` — hooks field `url` reads and the
  `send(` invocation, returning null for the hard-coded blocklist:
  - `https://spclient.wg.spotify.com/ad-logic/prefetch`
  - `https://spclient.wg.spotify.com/ads/v3/ads?slots=`
  Separately, `"Failed to clean tracking URLs"` wraps a secondary routine that scrubs query params
  (`appendQueryParameter` / `newPlainText` anchors) from URLs before they leave the app.

**Porting call**: the ad-endpoint blocklist is a complement, not a replacement, for SpotXero's
`AdResponseHook` chain — SpotXero neutralises ad *responses* while SpotifyEx refuses the entire ad
*fetch*. Before porting, weigh the preservation rule in `docs/morphe-porting/11-audio-ad-blocking.md`:
audio ad time still plays regardless of which seam is used. The `checkDeviceCapability` and
query-parameter items stay out until they can be observed against live traffic; an unverified
network-layer hook that mutates requests silently is exactly the class of behaviour the `AGENTS.md`
evidence rules forbid.

## 4e. SpotifyEx Remaining Patches (context for completeness)

From `Module.handleLoadPackage` order and failure tags:

| Patch tag | Purpose (from resolved anchors) | SpotXero equivalent |
|---|---|---|
| `"Failed to patch integrity"` | DexKit resolve + reflection into integrity-check surfaces | None (SpotXero hook surfaces come and go via LSPosed/LSPatch loader entry; it never touches integrity checks) |
| `"Failed to block requests"` | SpotifyEx's HTTP blocklist (see 4d) | Partial (`AdResponseHook` family) |
| `"Failed to patch account"` | Product-state attribute spoof — doc 01 | `ProductStateHook` (partial) |
| `"Failed to remove popups"` | messaging/upsell removal — 4b | None today; proposed above |
| `"Failed to patch context menu"` | menu-item surfaces by DexKit shape | None; UX-level, isolated |
| `"Failed to clean tracking URLs"` | query-param scrubbing — 4d | None; privacy nicety, not playback |

Nothing in this remaining-patches table is load-bearing for "queue plays in order"; docs 01–03 carry
that behaviour.

---

## Summary

| Item | Evidence confidence | Port |
|---|---|---|
| 4a Session preservation | High (explicit log line + no-op result) | **No** |
| 4b Upsell/messaging removal | High (SDK class names + flag names) | Yes — small follow-up |
| 4c Section-type filtering | Mechanism High, targets Low (20/21 mystery) | Hold until numbers mapped |
| 4d Network interception | Anchors High, effects Low (except the ad blocklist) | Ad blocklist possibly; rest hold |
| 4e SpotifyEx leftovers | High (named patch tags) | Per-row; none required for the stated goal |

The recurring doc discipline: this suite shipped *as one always-on blob*; SpotXero should cherry-pick
each seam behind its own toggle, fit it to the resolver architecture — including failing closed — and
verify on a device before considering a port done.
