# AdShield — an on-device ad & tracker blocker for Android

AdShield is a small, open-source Android app that filters advertising and tracking
domains for **every app on the phone**, without root, without a remote server and
without a subscription.

It is built as a `VpnService` that only routes DNS lookups (port 53) into a local
filter. Your actual traffic — video, downloads, browsing — never enters the tunnel
and is never inspected, proxied or slowed down.

```
Apps ──► system DNS resolver ──► 10.111.222.2 (inside the tunnel)
                                        │
                        ┌───────────────┴────────────────┐
                        │                                │
                 name is on a list                 name is fine
                        │                                │
                answer locally                   relay to upstream
              (NXDOMAIN / 0.0.0.0)               (1.1.1.1, 8.8.8.8, …)
                        │                                │
                        └───────────► answer back ◄──────┘
```

---

## Read this first: the YouTube question

You asked for an app that removes the ads in the YouTube app. **No app, VPN, DNS
blocker, hosts file or "ad blocker" on the Play Store can do that without modifying
YouTube itself.** This is not a limitation of AdShield, it is how YouTube is built:

* The ad is requested in the same response that carries the video. The ad media and
  the video come from the same hosts (`*.googlevideo.com`).
* Blocking those hosts blocks the video too. Blocking "ad URLs" does nothing,
  because there isn't a separate ad host to block.
* That's also why AdGuard, Blokada, NextDNS, a Pi-hole, `/etc/hosts` and every other
  DNS-based tool leave YouTube pre-rolls and mid-rolls exactly where they are.

What genuinely removes YouTube ads, best first:

| Option | What it is | Reality check |
| --- | --- | --- |
| **YouTube Premium** | Official paid tier | Removes ads, supports creators, keeps downloads/background play |
| **SmartTube** | Open-source client for Android TV / Fire TV | Only for TV boxes, sideloaded |
| **NewPipe** | Open-source YouTube front end | No ads, no login, missing some features |
| **ReVanced** | Patches the official YouTube APK | Closest to the real app with ads removed; unofficial, sideloaded, breaks when YouTube changes — your call whether to trust it |

AdShield is still worth installing, because YouTube is not where most mobile ads
live. It is the games with interstitials, the free utility apps, the browsers and
the "news" apps that shove ads and trackers at you — and those are almost all
served from separate, blockable ad domains.

---

## What AdShield actually removes

* Interstitial, banner and video ads in free apps and games (AdMob, AppLovin,
  Unity Ads, ironSource, Vungle, AdColony, Mintegral, Pangle, Chartboost, …)
* Ad and tracker requests from mobile browsers
* Analytics, attribution and profiling SDKs (Google Analytics, AppsFlyer, Adjust,
  Branch, Kochava, Mixpanel, Segment, Meta pixel, …)
* Ad exchanges and SSPs used by web pages inside apps

About 200 domains ship with the app (ad networks + trackers, plus an optional strict
list that is off by default). You can add big community lists — StevenBlack unified
hosts, AdAway, OISD, 1Hosts — with one tap, or import a file from storage.

## Features

* **No root, no accounts, no telemetry, no remote filtering server.** The only
  network request AdShield ever makes is the DNS query itself, forwarded to the
  resolver you pick.
* **DNS-only tunnel** — only the virtual resolver address is routed, so throughput
  and battery impact are minimal.
* **Local answers** for blocked names (`NXDOMAIN` or `0.0.0.0`/`::`) so ad SDKs fail
  instantly instead of hanging.
* **TTL-aware cache** of real answers for faster page loads.
* **Hijacks hard-coded resolvers** (8.8.8.8, 1.1.1.1, 9.9.9.9, …) so apps cannot
  bypass the filter by shipping their own DNS server.
* **Rule sets**: bundled ad list, tracker list, optional strict list (off by
  default), unlimited downloaded lists, file import.
* **Per-domain exceptions** that win over every list, so you can un-break a login or
  a game with one entry.
* **Query log** kept in RAM only (600 entries, never written to disk), with a
  blocked-only filter.
* **Today's counters**: total lookups, blocked lookups, block rate.
* **Quick Settings tile** and an ongoing notification with a stop action.
* Material 3 UI, light/dark, no analytics SDK inside.

## Build it

Requirements: Android Studio (Koala or newer) or a JDK 17 + Android SDK, plus
`ANDROID_HOME` set. minSdk 24 (Android 7), targetSdk 34.

```bash
# from the repository root
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # installs on a connected device
```

Or open the folder in Android Studio and press Run. The Gradle wrapper, launcher
icons, bundled lists and all sources are in the repo — nothing else to download.

No Android Studio? Every push builds an APK on GitHub Actions:
**Actions → “Build debug APK” → latest run → Artifacts → `adshield-debug-apk`**.
Unzip it and install `app-debug.apk` on the phone.

Sideloading: copy `app-debug.apk` to the phone, tap it, allow "install unknown
apps" for your file manager. A release build needs your own signing key:

```bash
./gradlew assembleRelease        # unsiged unless you add a signingConfig
```

## First run

1. Tap **Protect this device** and accept the Android VPN prompt.
   The key icon in the status bar means the local filter is running — nothing is
   leaving your phone.
2. Turn on **Always-on VPN** for AdShield in Android settings (the button in
   *Settings → Keep it on* takes you there) so it survives reboots.
3. Set **Private DNS** to *Automatic* or *Off*. A Private DNS hostname uses
   encrypted DNS that skips the tunnel, so the filter would be bypassed.
4. Optional: *Rules → Add a list* and pick StevenBlack or OISD for a much larger
   block list.

## Limitations you should know about

| Situation | Why | What to do |
| --- | --- | --- |
| YouTube in-app ads | Ads and video share hosts | Premium / SmartTube / NewPipe / ReVanced |
| Chrome, Firefox | Browsers use their own DNS-over-HTTPS | Turn off "secure DNS" in the browser, or use a browser with a built-in blocker |
| Apps that embed DoH/DoT resolvers | Encrypted DNS bypasses any local filter | Use the app's setting to disable it, or accept the gap |
| Captive portals (hotel/airport Wi-Fi) | The portal needs to be resolved before you log in | Turn protection off, log in, turn it back on |
| Banking / payment apps that dislike VPNs | Some check for an active VPN | Add exceptions, or stop protection while using them |
| Streaming apps that break with strict list | Aggressive rules hit feature flags or login | Disable the strict list, add an exception |
| Very large lists (500k+ domains) | Building the matcher takes a moment and more RAM | Keep one big list rather than five |

AdShield answers UDP DNS only. TCP DNS fallback (rare, used for very large
responses) is not intercepted; those lookups simply fail and the client retries over
UDP.

## How it is built

| Path | What lives there |
| --- | --- |
| `vpn/AdBlockVpnService.kt` | Tunnel lifecycle, packet pump, notification, 4 resolver workers |
| `vpn/Dns.kt` | DNS wire format: question parsing, synthesised block/error answers, TTL reading |
| `vpn/IpPacket.kt` | Minimal IPv4 + UDP parse/build with correct header and UDP checksums |
| `vpn/DnsUpstream.kt` | Protected upstream socket, per-query, with timeout |
| `vpn/DnsCache.kt` | TTL-bounded answer cache |
| `data/RulesRepository.kt` | List storage (JSON metadata + normalised files), downloads, imports, matcher rebuild |
| `data/RuleMatcher.kt` | Immutable suffix-matching rule snapshot; allow-list wins |
| `data/Domains.kt` | Hosts file / AdBlock-syntax / domain list normalisation |
| `service/AdBlockTileService.kt` | Quick Settings tile |
| `ui/` | Compose Material 3 screens (Home, Log, Rules, Settings) |
| `assets/blocklists/` | Bundled curated lists |

Design notes that matter for correctness:

* The tunnel only carries `10.111.222.0/24` (plus optional /32s for hard-coded
  resolvers). Everything else keeps using the phone's own network, so there is no
  NAT, no packet forwarding and no throughput cost.
* Every upstream socket is `protect()`-ed before use, otherwise the reply would loop
  back into our own tunnel.
* The rule matcher is an immutable set pair swapped through a single `@Volatile`
  reference; rebuilds never expose a partially loaded list to the DNS workers.
* The DNS hot path does no allocations beyond the packet itself: no string
  formatting, no logging to disk, no binder calls.

## Privacy

* No internet permission is used for anything except the DNS forward itself.
* Lists you add are downloaded directly from their source; no proxy.
* Query log lives in a `ArrayDeque` in RAM and dies with the process. There is no
  export and no upload.
* No analytics or crash SDK is bundled.

## Status

Version 1.0.0. Built as a reference implementation of a DNS-filtering VPN that is
small enough to read in an afternoon. Contributions welcome — especially additions
to the bundled lists and translations.

## License

MIT. See [LICENSE](LICENSE).
