# 📟 XaeroDeck App

**A tactical second screen for Minecraft.** Native Android companion for the
[XaeroDeck Fabric mod](https://github.com/JawshTheDark/XaeroDeck) — put your
live Xaero's WorldMap, radar, chat, and flight controls on a tablet next to
your keyboard.

Built tablet-first (developed on a Galaxy Tab S6 in landscape), amber-on-black
tactical-HUD theme, Jetpack Compose. Works on any Android 8+ device.

> 🧩 You need the **mod** on your Minecraft client for this to connect to —
> grab it from [XaeroDeck releases](https://github.com/JawshTheDark/XaeroDeck/releases).

---

## Table of contents

- [Install](#install)
- [The map](#the-map)
- [Controls reference](#controls-reference)
- [Route editor — draw your flight](#route-editor--draw-your-flight)
- [Seed overlays: ERA and MARKERS](#seed-overlays-era-and-markers)
- [Chat, notifications and watchdog](#chat-notifications-and-watchdog)
- [Offline mode](#offline-mode)
- [Security](#security)
- [Building](#building)

---

## Install

1. Install the APK from [Releases](https://github.com/JawshTheDark/XaeroDeck-App/releases)
   (sideload — enable "install unknown apps").
2. Have the XaeroDeck mod running in Minecraft on the same WiFi, with the port
   open in your PC firewall (default 8399 — e.g. `sudo ufw allow 8399/tcp`).
3. Open the app — it **finds your PC automatically** via the mod's UDP
   discovery beacon. No IP typing.
4. For anything beyond viewing (waypoints, autopilot, Meteor, chat), open
   **CONFIG** and paste the `token` from the mod's `config/xaerodeck.json`.
   The matching features must also be enabled mod-side — they're opt-in.

## The map

- **Live streaming** — your position at up to 20 Hz over SSE, and map tiles
  that update within moments of terrain rendering in-game (the mod pushes
  "region changed" events; the app refetches only what changed).
- **Three-level zoom pyramid** — full-detail 512-block region tiles up close,
  2048-block overviews mid-zoom, 4096-block super-tiles when you pull way out.
  Zoom from individual blocks to a continent without the map flashing or the
  device running out of memory.
- **Fling inertia** — flick to glide across the map; friction scales with zoom
  so a flick covers a sane distance whether you're at block level or 100k out.
- **Dimension switcher** — LIVE follows your player; Overworld / Nether / End
  browse any dimension's cache (nether pulled from Xaero's cave-layer caches).
- **Entity radar** — players (distinct color), Meteor **friends** (green),
  hostiles/neutrals/passives in Xaero's minimap colors, angered neutrals shown
  hostile. Travel **trail history** persists across sessions.
- **Waypoints** — rendered live from Xaero's minimap set, list auto-sized to
  the longest name. Tap a waypoint to jump to it; long-press the map to create
  one (name, Y, all 16 Xaero colors); long-press a list entry to delete.
- **Stats bar** — speed (bps), ping, server TPS, HP, totem count, elytra
  durability %, and active potion effects with live countdowns.

## Controls reference

Full-word buttons down the **left rail**, compass inset on the right, sized
for fat-finger use while flying.

| Button | Tap | Long-press |
|---|---|---|
| **NAVIGATE** | Cycle OFF → WALK (Baritone) → FLY (autopilot). In a nav mode, tap map/waypoint to go; long-press map to cancel | — |
| **ROUTE** | Open the route editor (below) | — |
| **MARKERS** | Toggle seed structure markers | Filter dialog — toggle each structure type individually |
| **ERA** | Toggle the worldgen-era / modified-terrain overlay | — |
| **CHAT** | Chat window (opens scrolled to newest; **pinch to resize text**) | — |
| **MODULES** | Meteor module browser: toggle any module, edit its settings inline (switches, real-bounds sliders, dropdowns) | — |
| **METEOR** | Quick panel of favorite module toggles | — |
| **CONFIG** | Server address, token, watchdog, alert sound, oracle seed | — |

## Route editor — draw your flight

MS-Paint-style shape tools that hand routes to the mod's elytra autopilot
(steering-only; the mod manages Meteor ElytraFly for thrust — see the
[mod README](https://github.com/JawshTheDark/XaeroDeck#autopilot)):

- **GO** — fly the drawn shape (ellipse flies as an endless orbit, spiral flies
  once outward)
- **LOOP** — tap points to build a multi-leg patrol route, flown in a cycle
- **ELLIPSE** — an ellipse appears on the map: **drag its handles** to stretch,
  drag the body to move, **pinch to scale**. Down to 16-block radii for tight
  orbits.
- **SPIRAL** — a smooth Archimedean spiral overlay; pinch to grow/shrink its
  reach, drag to reposition. The classic basefinding sweep.
- **AUTOMAP** — frame an area; the autopilot flies a lawnmower pattern that
  maps every chunk in it
- **CLEAR / EXIT** — wipe the shape / leave the editor

You see the live route and the autopilot's current leg drawn on the map while
it flies.

## Seed overlays: ERA and MARKERS

When the mod knows the world seed (auto-captured from SeedcrackerX, from the
community seed DB, or typed into CONFIG → oracle seed):

- **ERA** tints chunks by which Minecraft version's worldgen produced them and
  paints **player-modified terrain red** — old-growth bases and stash holes
  light up.
- **MARKERS** draws chunkbase-style predicted structures with two-letter
  glyphs: villages, bastions, fortresses, monuments, mansions, outposts,
  temples, shipwrecks, ruined portals, end cities, strongholds, the end
  gateway ring… each type toggleable via long-press MARKERS. **Slime chunks**
  are computed on-device straight from the seed and overlay in green when
  zoomed in.

## Chat, notifications and watchdog

- **Minecraft color rendering everywhere** — chat and notifications render
  §-codes and RGB colors exactly like in-game.
- **Notification toasts** — big, top-center, streaming from the mod (Meteor
  notifier events, radar mods, seed captures, deaths).
- **Watchdog** 🚨 — pattern-watches your stats and fires a full-volume alert:
  **TOTEM POP** (totem count drops) and **ELYTRA LOW** (durability under
  threshold). Pick any system ringtone/alarm as the alert sound in CONFIG
  (with TEST ALERT button); silent mode supported. A grace period after
  joining a world prevents false alarms.
- **Death alert** — vibrates the device and pins your death coordinates.

## Offline mode

Every tile the app ever fetches is cached per-world on the device. No
connection? The world browser opens any previously-seen world for full
pan/zoom browsing — all dimensions, all zoom levels — from cache.

## Security

The app speaks **only** to the mod on your LAN: no analytics, no internet
permissions used beyond your local network, token sent only to the server you
paired with, and the discovery beacon never carries secrets. Threat model in
the mod repo's [SECURITY.md](https://github.com/JawshTheDark/XaeroDeck/blob/main/SECURITY.md).

## Building

```bash
ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android SDK (compileSdk 35) + JDK 17+. Kotlin, Jetpack Compose Material3,
custom canvas map view. Release APKs are built by GitHub Actions from tagged
source, so you can verify what you sideload.

## License

MIT.
