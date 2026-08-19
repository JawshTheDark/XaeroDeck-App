# Security

XaeroDeck App is a companion display for your own Minecraft client. It is built
for anarchy-server players, so it assumes a hostile world. Don't trust this
page — verify it; the whole app is open source and unobfuscated.

## What it talks to

Exactly one thing: the XaeroDeck mod's HTTP server on your own LAN (found via
its UDP broadcast beacon, or an address you type). There is no cloud relay, no
account system, no analytics, no crash reporting, no ads, no third-party SDKs,
and no update check. The dependency list is AndroidX + Kotlin coroutines —
nothing that phones home.

## What it sends

- HTTP requests to your mod's server: tile fetches, status stream, and — only
  when you use those features — waypoint edits, chat messages, Meteor/Baritone
  commands. Control requests carry your pairing token in a header.
- Nothing else, to anyone else, ever.

## What it stores (on-device only)

- Map tiles, travel trails, and waypoint snapshots per world, in the app's
  private storage — so your explored maps are browsable offline.
- Your pairing token and settings, in the app's private preferences.
- Nothing is synced or backed up anywhere by the app.

This means the tablet itself holds your base coordinates and map history.
Treat the device accordingly: lock screen on, and don't hand it to someone
you wouldn't show your stashes.

## Permissions, and why

| Permission | Why |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | Talking to your mod's LAN server |
| `ACCESS_WIFI_STATE` + `CHANGE_WIFI_MULTICAST_STATE` | Receiving the mod's UDP discovery beacon |
| `VIBRATE` | Death alerts |

No location, no contacts, no storage access outside the app's own sandbox.

## Verifiable builds

Release APKs (v0.2.2+) are built by GitHub Actions from the tagged source —
open the Build workflow run for any release and compare. Or build it yourself:
`./gradlew assembleDebug`.

Mod-side threat model (endpoints, token gating, opt-ins, "is this a RAT?"):
https://github.com/JawshTheDark/XaeroDeck/blob/main/SECURITY.md

## Reporting

Found a hole? Open an issue or ping J_wsh. Security reports get fixed before
features.
