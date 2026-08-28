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
  the longest name, each row showing its **nether/overworld twin coords**.
  Tap a waypoint to jump to it; long-press the map to create one (name, Y,
  all 16 Xaero colors); long-press a list entry to delete.
- **Live ETA** — navigating anywhere shows a time-to-arrive chip computed
  from your real (smoothed) speed, re-estimated every stream frame: straight
  shots, full remaining route length for multi-leg routes and spirals, lap
  time for orbits — plus a **via-nether time** on long overworld hauls.
- **Position readout** — both dimensions at once (`N:` and `O:` lines),
  comma-separated numbers, current dimension in green.
- **Sighting log** — every player the radar passes is journaled per server
  with coords, dimension, and age; friends green, everyone else red, names
  rendered in their Minecraft colors (bot walls of §-code names collapse
  into one entry). Tap a sighting to jump the map there.
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
| **CONFIG** | Server address, token, watchdog, alert sound, oracle seed, structure version | — |
| **NAMES** | *(hold)* Shows the full structure name under every marker while pressed | — |

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
  reach, drag to reposition, and **GAP±** buttons set the ring spacing in
  32-block steps — match it to your render distance for gapless chunk
  coverage without wasted overlap. The classic basefinding sweep.
- **AUTOMAP** — frame an area; the autopilot flies a lawnmower pattern that
  maps every chunk in it
- **DECOY** — anti-trail-hunter radials: tap a center (your position or
  anywhere nearby) and randomized arms radiate outward — the autopilot
  flies to the end of each arm and **back to the hub** before starting the
  next. Every arm's chunk trail reads as an out-and-back run, so neither
  bots scanning NewChunks nor human trail-followers can tell which
  direction (if any) matters. Arm count, angles, and lengths reroll on
  every tap.
- **CLEAR / EXIT** — wipe the shape / leave the editor

You see the live route and the autopilot's current leg drawn on the map while
it flies.

## Seed overlays: ERA and MARKERS

When the mod knows the world seed (auto-captured from SeedcrackerX, from the
community seed DB, or typed into CONFIG → oracle seed):

- **ERA** tints chunks by which Minecraft version's worldgen produced them and
  paints **player-modified terrain red** — old-growth bases and stash holes
  light up.
- **MARKERS** draws chunkbase-style predicted structures with their actual
  Minecraft sprite icons: villages, bastions, fortresses, monuments,
  mansions, outposts, temples, shipwrecks, ruined portals, end cities,
  strongholds, the end gateway ring… Long-press MARKERS for the legend —
  every type with its icon, individual toggles, and an ALL MARKERS master
  switch. **Slime chunks** are computed on-device straight from the seed
  and overlay in green when zoomed in.
  *(Structure icons from the [Minecraft Wiki](https://minecraft.wiki/w/Structure),
  CC BY-NC-SA 3.0.)*

## Chat, notifications and watchdog

- **Minecraft color rendering everywhere** — chat and notifications render
  §-codes and RGB colors exactly like in-game.
- **Notification toasts** — big, top-center, streaming from the mod (Meteor
  notifier events, radar mods, seed captures, deaths).
- **Watchdog** 🚨 — pattern-watches your stats and fires a full-volume alert:
  **TOTEM POP** (totem count drops), **ELYTRA LOW** (durability under
  threshold), **HP LOW**, **PLAYER** (radar contact), and **CONNECTION
  LOST** (the stream dies while you're AFK — crash/kick alarm). Pick any
  system ringtone/alarm as the alert sound in CONFIG (with TEST ALERT
  button); silent mode supported. A grace period after joining a world
  prevents false alarms.
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
