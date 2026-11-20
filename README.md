# Cinematic Travel Camera

A client-side NeoForge mod for Minecraft 1.21.1: press a key while you travel and the camera turns your ride into a
film. It cuts between trackside, chase, wide, close and overhead shots every 6 to 12 seconds while your keys still
steer. Only the view changes; press the key again and you are back to normal.

Designed by DuskyTheDolphin, written with AI assistance.

## Using it

- **Ctrl+N** turns the camera on and off (Controls > Cinematic Travel Camera, rebindable).
- Ride a horse, a boat, a train or a ship, or just walk. Keep playing as normal: movement keys, mouse
  and vehicle controls all work as usual; the camera only chooses where to look from.
- A shot cuts sooner when something blocks the view for more than about half a second. A passing tree trunk doesn't
  cut; a hill or a wall does. Leaves don't count as a wall unless they would fill the frame.
- While it is on, the HUD and your hand are hidden, view bobbing and the hurt shake are off, and sounds are still
  heard from where you are, not from the camera.
- **Taking damage** snaps back to the normal view. In the config you can switch that off; a red "Taking damage"
  warning shows instead.
- It refuses to start, or turns itself off, when another mod has taken over the camera.

## Auto director on guided horse rides (optional, with Horse Routes)

When the horse you ride follows a plan from **Horse Routes** (auto-ride or a recorded route), the camera reads the
land along the plan ahead and picks the shot: a chase on plain ground, high wide and overhead shots over hills, drops,
water and buildings, and spots the rider rides toward or away from. Shots last 6 to 20 seconds and change when the
land does. Press W, A, S or D to get the normal view back at once; it picks up again on a chase shot a second after
you let go. Without Horse Routes, or with the option off, shots are picked as described above.

## Known limits

- Dense, steep woods make it cut more often: a shot that runs into a slope or a trunk cuts away at once.
- Leaves can drift into a running shot; a high shot that ends up looking through a canopy cuts within about 2 seconds
  if you're hidden.
- On a guided ride, wide and overhead shots take a bit more than half of the time.
- The "picked up an item" notices are still drawn over the shot.
- Only a horse following a Horse Routes plan gets director shots; walking, boats, trains and airships use the normal
  cycle of shots.

## Install

On the **client** only. Servers don't need it, and it sends nothing to the server: no network channels, and your
player stays exactly where it is.

- Minecraft 1.21.1
- NeoForge 21.1 or newer (built against 21.1.250)
- No required dependencies

## Optional: works with

None of these are needed. With them installed, the camera handles them:

- **Horse Routes**: the auto director on guided horse rides (above).
- **Shoulder Surfing Reloaded** (checked with 5.1.1): the travel camera applies its shot after Shoulder Surfing's own
  camera, so the two don't fight.
- **Icy's Better Horses** (checked with 2.1.4): its horse badges (ability banners, harness gauge and shield) ignore
  the hidden HUD; they are hidden while the camera is on. Nothing changes with the camera off, or without that mod.
- **ParCool** (checked with 4.0.1.6): its stamina display is hidden while the camera is on.
- **Sable** ships (checked with 2.0.5): follow shots ride with the ship, and ship blocks count as walls.
- **Create** trains: carriages count as walls, so shots don't end up inside them.
- **Distant Horizons**: the field of view is left alone, so far terrain and near terrain stay one picture.

## Config

`config/forgeward_travelcam-client.toml`, also in Mods > Cinematic Travel Camera > Config.

| Key | Default | Meaning |
|---|---|---|
| `snapBack` | `true` | Return to the normal view when you take damage. `false`: the camera stays on and a warning shows instead. |
| `director` | `true` | When a horse is riding a Horse Routes plan, choose shots from the land ahead. `false`: shots are picked as without it. |

## Log lines

The mod says what it is doing in the client log: one `[forgeward_travelcam] <version> ready: ...` line at start,
then one `on:` and one `off (why):` line per use, with the cuts, shots and wall-check cost. Useful in bug reports.

## Building

`build.ps1` compiles with `javac` against NeoForge 21.1.250's named client jars (`client-1.21.1-*-srg.jar` and
`neoforge-21.1.250-client.jar` in `clientlibs/`, as a NeoForge installer run in client mode leaves them) and a
NeoForge 21.1.250 server's `libraries/` (set `SERVER`, `CLIENTLIBS` and `JDK`). Mod id `forgeward_travelcam`.

## Bugs and ideas

GitHub issues on this repository. Please include your mod list and the `[forgeward_travelcam]` lines from the log.

## License

MIT, see [LICENSE](LICENSE).

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
