# XaeroDeck App

Android companion for the [XaeroDeck Fabric mod](https://github.com/JawshTheDark/XaeroDeck) —
turns a tablet or phone into a live second-screen map for Minecraft.

## Features

- Live Xaero map with smooth 20Hz player tracking, pinch/button zoom, N/S/E/W compass,
  dimension switcher (LIVE / Overworld / Nether / End), overview tiles when zoomed out
- Per-world offline tile cache + cached-world browser
- Entity radar (players, Meteor friends, mobs in Xaero colors), travel trail history
- Waypoints: tap to jump, long-press map to add (name/Y/16 Xaero colors), long-press list to delete
- Meteor remote control: full-screen module browser with inline settings editors
  (switches, sliders with real bounds, dropdowns), configurable enabled-module color
- Baritone nav mode: tap map or waypoint → #goto, long-press to cancel
- Chat window and Meteor notification toasts with Minecraft color rendering
- Stats dashboard: speed, ping, TPS, HP, totems, elytra durability, potion effect countdowns
- Death alert with vibration; UDP auto-discovery (no IP typing on LAN); pairing-token auth

## Building

```
ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires the Android SDK (compileSdk 35) and JDK 17+. Target device can be anything
Android 8+; developed against a Galaxy Tab S6 in landscape.
