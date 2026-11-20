# Changelog

## 1.1.4
- New: an auto director for guided horse rides (needs Horse Routes; optional). It reads the land ahead and picks the
  shot: a chase on plain ground, high wide and overhead shots over hills, drops, water and buildings, and spots the
  rider rides toward or away from. Shots last 6 to 20 seconds and change when the land does.
- Press W, A, S or D during a guided ride to get the normal view back straight away; it picks up again on a chase
  shot a second after you let go.
- You're never fully hidden from the shot for more than 2 seconds, and a block that swings in front of the lens cuts
  away in about half a second.
- Shots crowded with leaves, tall grass or flowers aren't chosen.
- New option: **Auto director on rides** (on by default). Your existing settings and key binding carry over.
- Without Horse Routes nothing changes except the cleaner shot checks above.
- (1.1.0 to 1.1.3 were unreleased test versions.)

## 1.0.4
- Smooth on moving airships (Sable / Create Aeronautics) and Create trains: follow shots now ride with the vehicle
  as it's drawn, so a slow frame or a lagging server no longer makes the camera shake against the ship.
- A shot too tight against a ship's or carriage's own blocks cuts to an outside shot instead of sitting against the
  hull. When the rider is shut inside a cabin or carriage, the outside shot frames the vehicle.
- Sable and Create stay optional: without them the camera works as before.
- Optional cloud band, OFF unless a pack turns it on: a pack whose shader draws thick clouds can ship
  `config/forgeward_travelcam_clouds.txt` (`<bottom y> <top y>`) so far shots aren't taken through them. Without that
  file, or without a shader pack in use, nothing changes.

## 1.0.1
- First public release.
- Name: Cinematic Travel Camera (the key category and the mod list use it; the key id and the config file are
  unchanged, so existing key bindings and settings carry over).
- Internal test switches removed from the mod. Nothing a player sees changes.
- MIT license.

## 1.0.0
- Unreleased internal version: five shots (trackside, chase, wide, close, overhead) cut every 6 to 12 s, sooner
  when the view is blocked; follow shots slide in before a wall instead of cutting; the wide shot holds its bearing
  and the others turn gently with the rider; snap back on damage (config); HUD, hand, bob and hurt shake off while
  on; sound stays on the player; rides with Sable ships; hides ParCool's stamina display and Icy's Better Horses'
  badges while on.
