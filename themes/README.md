# Themes

A theme is a folder with a `config.json` and, optionally, a `wallpaper.jpg`. Pushing one changes the
launcher's dock, layout and look on the TV without rebuilding the APK: the launcher reads
`config.json` as an overlay over its built-in `config.xml` the next time it starts.
Choices made in Launcher settings take priority until you choose Follow theme. Pushing a theme or
using `tools/theme.sh --reset` also clears those on-TV layout, motion, sound, wallpaper, App Library and System info choices.
Launcher settings includes a Navigation sounds toggle and a Wallpaper row: press OK to cycle through
Theme (the pushed wallpaper, or its rotating `wallpapers/` collection), the bundled Aurora picture, and Off. Changes apply on returning Home;
the sound toggle also applies immediately within Launcher settings. These choices leave theme files intact.

```bash
TV_HOST=192.168.1.50 tools/theme.sh themes/aurora          # push a theme and restart Home
TV_HOST=192.168.1.50 tools/theme.sh --reset                # back to the built-in config.xml
powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost 192.168.1.50 themes\aurora
```

| Theme | What it does |
|---|---|
| `default/` | The built-in look written out as JSON, as a starting point for your own |
| `aurora/` | A dark aurora wallpaper (teal and violet on navy, darker at the bottom) with a matching background colour |
| `dussehra/` | Festive skin for Dussehra (ends 21 Oct 2026): a marigold toran, a faint gold rangoli, a diya for Wi-Fi, a bell for Inputs, a marigold for Settings and a petal shower |
| `scenes/` | 14 dark illustrated landscapes in `wallpapers/`, a new one every hour; drawn from code by `tools/scenes_wallpapers.py` |
| `six-columns/` | Six smaller tiles per row and one grid row visible under the dock |

## Keys

Every key is optional; a missing key keeps the value from `config.xml`, an unknown or wrongly typed key
is ignored, and a file that doesn't parse is ignored as a whole, so a theme can never break Home.

| Key | Type | Meaning |
|---|---|---|
| `dock_apps` | list of package names | The dock row, in order; missing apps are skipped |
| `hidden_apps` | list of package names | Never shown as tiles |
| `live_tv_package`, `live_tv_action` | string | The Live TV app and, if it has no leanback entry, the action to start it with |
| `source_panel_service`, `source_panel_action` | string | The TV's own source panel, for sets with no TvInputManager: a service, or an activity (BYINTEK U4). Without a Live TV app, the Inputs pill opens it directly |
| `launch_actions` | list of `"package=action"` | Tiles that open a screen of the app instead of its start screen; an action that no longer resolves opens the app normally (MagentaTV's guide: `com.telekom.onetv.tv.pl=com.telekom.onetv.tv.pl.action.PROGRAM_GUIDE`) |
| `app_labels` | list of `"package=Name"` | A tile's name instead of the app's own (MagentaTV as `com.telekom.onetv.tv.pl=TV`) |
| `app_banners` | list of `"package=banner_name"` | A tile's artwork from a `banner_*` drawable bundled with the launcher (`banner_tv`: magenta with the Live TV glyph) |
| `input_action` | string | Action sent with an HDMI/AV row's channel URI when nothing handles the standard `VIEW` (XGIMI: `com.xgimi.action.hdmiPlayer`) |
| `settings_package` | string | First package tried for settings slice resources, followed by discovered system Settings packages; empty disables slice lookup and discovery |
| `settings_fallback_component` | component string | Firmware settings entry used only when neither the requested public settings page nor general settings is available |
| `settings_slice_action`, `settings_slice_uri_extra` | string | Firmware slice activity action and the name of its content-URI extra |
| `quick_audio_output_component`, `quick_apps_component`, `quick_bluetooth_component`, `quick_screensaver_component` | component string | Optional firmware activities, written as `package/.Activity` or `package/full.class.Name` |
| `quick_audio_output_slice_resource`, `quick_bluetooth_slice_resource` | string | Names of string resources in `settings_package` containing settings slice content URIs |
| `quick_screensaver_action` | string | Optional firmware action that starts the current screensaver |
| `columns` | 1 to 8 | Tiles per row; tiles shrink to fit from 5 up |
| `rows_below_dock` | 0 to 3 | Grid rows left visible under the dock; also adjustable in the grouped Launcher settings |
| `dock_title` | bool | The focused dock app's name in large type on the top shelf (`true`); `false` keeps only the app-colour glow |
| `app_library` | bool | The App Library pill after the last Home row: every installed app, add one to Home or take it off (`true` by default; also in Launcher settings). The Home choices stay on the TV when a theme is pushed or reset; see [docs/app-library.md](../docs/app-library.md) |

| `show_network_pill` | bool | The Wi-Fi/Ethernet pill in the top bar (`true` by default); `false` hides it |
| `icon_only_buttons` | bool | Wi-Fi as a round icon button; Settings already uses the fork's glass gear button and Inputs keeps its label (`false` by default) |
| `wifi_label_width` | 0 to 300 | With `icon_only_buttons`, keep the network name on the Wi-Fi pill up to this width in dp, cut with an ellipsis; 0 leaves only the icon |
| `library_button` | bool | The app library as a round button in the top bar, beside Settings; needs `app_library`. Closing the library returns to it |
| `always_show_names` | bool | Names under every tile, dimmed until focused, the dock included (its band grows to hold them); `false` by default shows only the focused tile's name |
| `wallpaper_true_color` | bool | Decode wallpapers in 32-bit colour (8 MB at 1080p) instead of RGB_565 (4 MB), which bands dark gradients such as a night sky; for TVs with 2 GB or more (`false` by default) |
| `shamsi_date` | bool | Show the Solar Hijri date above the Gregorian one |
| `greeting_name` | string | A name after the greeting, e.g. `"Mr. Trịnh"` for GOOD MORNING, MR. TRỊNH; empty for the greeting alone |
| `module_cards` | bool | Look for module cards and ask about them (`true`); `false` turns the feature off |
| `reduced_motion` | bool | Calmer motion for weak GPUs: stiff highlight spring, no tile overshoot, short fades, no entrance replay |
| `navigation_sounds` | bool | Navigation and button sounds for Home tiles, folders, header buttons and quick settings; defaults to `true`. Set `false` to silence them; follows the TV's system sound setting |
| `vpn_app` | string | App the network pill opens when a VPN is configured; also shows a red "VPN off" badge while no VPN is up |
| `animations` | bool | Motion of tiles, the glass, the top shelf, the expanding grid, folders and menus; true by default. `false` keeps the focused look but jumps each change to its end |
| `advanced_effects` | bool | Optional lifted tile, artwork zoom, smooth ambient colour and app opening effects; false by default |
| `show_home_settings_shortcut` | bool | Keep the optional Default-home tile visible when the TV has a home picker; false by default |
| `show_stats` | bool | One quiet line under the clock with CPU, free memory and free storage (System info in Launcher settings). Off unless set |
| `background_color` | `#RRGGBB` | Colour behind everything; black by default |
| `sky_intensity` | 0 to 100 | Time-of-day backdrop: a dark gradient that follows the clock, 0 hides it (default) |
| `wallpaper` | bool | Show `wallpaper.jpg` from the theme folder (`true`), or ignore it (`false`) |
| `wallpaper_rotation_minutes` | 0 to 1440 | Rotate images in the theme's `wallpapers/` folder at this interval; 0 keeps the single-image behaviour. A negative or non-numeric value is ignored, and 0 means "no rotation", not "every minute" |
| `wallpaper_order` | `shuffle` or `sequential` | How a collection advances. `shuffle` (the default, and what launchers did before this key existed) avoids repeating the current picture; `sequential` walks the folder in filename order and wraps at the end. An unknown value falls back to `shuffle` |
| `wallpaper_dim` | 0 to 90 | Black overlay over wallpaper images, in percent; 0 by default and no shade view at all |
| `festival` | string | Turns on a festive skin: the network pill becomes a diya whose flame follows the connection, Inputs a temple bell, Settings a marigold, and the first Home of the day gets a petal shower. `dussehra` and `diwali` have built-in greetings |
| `festival_until` | `YYYY-MM-DD` | Last day the festive skin shows; it switches itself off after that. A malformed date turns it off |
| `greeting` | string | Replaces the time-of-day greeting while the festive skin is on |
| `accent_color` | `#RRGGBB` | Colour of the focus light while the festive skin is on, in place of each app's colour; diya gold if not set |
| `settings_key_keycodes` | list of keycodes | Remote buttons that open Settings, for `HomeKeyService` (TCL Google TV gear: `[83]`) |
| `settings_key_panel` | bool | The remote's Settings button opens Home's glass quick settings (`true`, the default) or the TV's own Settings (`false`) |
| `input_key_keycodes` | list of keycodes | More remote buttons that open the input switcher, for `InputKeyService` (`KEYCODE_TV` and `KEYCODE_TV_INPUT` always do) |
| `app_keys` | list of strings | Remote buttons that open an app, as `"190:com.google.android.youtube.tv"` or `"KEYCODE_BUTTON_3:com.google.android.youtube.tv"`, or by scancode as `"scan:490:com.wbd.hbomax"` for remotes that share one keycode between app buttons (Sony BRAVIA), for `InputKeyService` or `HomeKeyService` |
| `race_calendar` | list of races | F1 race-weekend mode: from 12 hours before a weekend's first session until 4 hours after the race starts, a panel under the clock shows the next session with a countdown (or the live one) and the weekend's session times in the TV's time zone. Write it with `tools/race_calendar.py <theme folder>`, which fetches the season from the free Jolpica API; the on-TV settings can also refresh the calendar directly |
| `home_idle_clock_minutes` | 0 to 1440 | Minutes Home may sit untouched before it shows the clock screensaver (a thin clock and date drifting over a slow glow, as the system "Clock" screensaver does). Any key returns to Home. It starts only from Home, never over Live TV or an app; 0 is off (default) |
| `cinematic_home` | bool | Cinematic top shelf: the focused dock app's artwork, softened, fills the top of the screen in its colour, with a larger name, "Last opened" (apps opened from Home), dock position dots, a focus glow in the app's colour and a translucent dock band; off by default |
| `ambient_glow` | bool | The glow behind the dock in the focused app's colour (`true` by default); `false` keeps only `background_color` or the wallpaper, e.g. for a calm photo background |

## Firmware settings routes

The launcher uses public Android settings actions, with optional firmware destinations configured in
`launcher/app/src/main/res/values/config.xml`. The built-in defaults retain the known Android TV,
Google TV and Xiaomi destinations. For optional settings slices, the launcher also discovers
launchable system apps handling Android's public Settings action and tries their resource overlays.
The configured package is tried first, duplicate packages are skipped, and ordinary third-party
apps are not automatically used for slice discovery. No vendor-name or model-name check is needed.
Every route key above can also be overridden in a theme;
an empty string disables that route, while an absent or wrongly typed key keeps its built-in value.
They are identifiers, not translated UI text.

The lookup order is:

- **Audio Output:** configured component, configured audio-output slice, public sound settings.
- **Apps:** configured component, public application-management settings, all-app management,
  application settings, general settings.
- **Bluetooth:** configured accessories slice, public Bluetooth settings, configured accessories
  component, public wireless settings, general settings.
- **Screensaver:** configured start action, public screensaver settings, configured component,
  general settings.
- **Picture, Sound and All Settings:** their public Android settings actions.
- **Wi-Fi:** public Wi-Fi settings, wireless settings, general settings.

When a requested public page is missing, the launcher tries general settings and then
`settings_fallback_component`. It skips missing, disabled, non-exported and permission-protected
activities it cannot launch. Missing packages/resources and invalid slice URIs are also skipped.
If nothing is available, the caller shows the existing unavailable message instead of crashing.
The precise pages remain firmware-dependent. This allows standard Android TV and Google TV
implementations from different vendors to resolve their own settings apps, but it does not claim
physical-device validation for every brand or guarantee every specialized page. It does not add
support for TVs running a different operating system.

For example, to disable a dedicated vendor audio-output activity while retaining the other routes:

```json
{ "quick_audio_output_component": "" }
```

On Android 11+, a custom package must be visible to the launcher for availability checks and resource
lookup to work. The APK declares the built-in settings packages and public actions in its manifest's
`<queries>`. If an additional firmware package is not already visible through those declarations,
add its package to `<queries>` when building for that TV. This is a package-visibility declaration,
not a new permission; a theme cannot change the installed APK's manifest.

## Wallpapers

`wallpaper.jpg` is drawn full screen behind the launcher, centre-cropped. Keep it dark, 1920×1080 and
under about 300 KB: the text, the hero name and the dock band are designed for black, and a 1 GB TV
decodes the picture into memory on every Home start. Pictures larger than the screen are scaled down to
it; a picture that is still over 4K (about 8.4 million pixels) after it is sampled down to the screen is not shown at all, and the plain colour stays.

For a wallpaper collection, place JPEG, PNG or WebP files in the theme’s `wallpapers/` folder beside `config.json`, then set:

```json
{ "wallpaper": true, "wallpaper_rotation_minutes": 30, "wallpaper_dim": 40, "wallpaper_order": "sequential" }
```

The last displayed picture is remembered on the TV, so a restart or reboot resumes from it rather than
starting over at the first image in `sequential` mode. Missing an interval while the TV is asleep or
unplugged does not replay the missed pictures: Home moves on at most once when it wakes. Replacing the
collection starts playback from the new collection's first picture. A collection with one picture is
shown once and never blanked on a timer. Images are centre-cropped to fill the 16:9 screen; dimming is
drawn as a black overlay above the picture and below the launcher's text.

When you apply a theme, its collection replaces the one already on the TV. Use filenames containing
only letters, digits, underscores, dots and hyphens. Images can come from any source; check that you
have permission to use them. The launcher considers
up to 100 nonempty images, at most 10 MB each, skips unreadable files and keeps the last good background.
An empty collection falls back to `wallpaper.jpg`, or the background colour if no image is available.
`"wallpaper": false` disables both single-image and collection backgrounds.

Selection is random without an immediate repeat when alternatives exist. The current image and its
change time survive app/process restarts. Rotation runs only while Home is visible; on return from
another app, an overdue background changes then. Images are decoded on a worker thread and bounded
to the screen size. There is no download service, Internet permission or computer-side scheduler.
The original system idle screensaver is left unchanged.

On the TV, Launcher settings has a Wallpaper row: pick one picture from the collection (or the theme's
`wallpaper.jpg`), or None. A pick shows that one picture and stops the rotation until you choose Theme
default, press Follow theme, or push a theme again.

## Reduced motion

`"reduced_motion": true` keeps the look but calms the movement: the highlight spring is stiff instead
of bouncing, tiles zoom without overshoot, fades are shorter, and Home does not replay the tile
entrance when you come back to it. Use it on TVs whose GPU cannot keep up; a MediaTek MT5895 on
Android 10 is the known case. The default, `false`, keeps the normal motion.

```json
{ "reduced_motion": true }
```

## Time-of-day backdrop

`"sky_intensity": 60` draws a dark gradient over the wallpaper and behind everything else, that follows the
clock: night blues, a soft blue day, then a warm horizon that peaks just before eight in the evening.
The glow near the bottom edge travels from left to right with the sun. The colours stay dark so the
white clock, the hero name and the dock band keep their contrast. It is drawn as one 480×270 bitmap
and redrawn once a minute, never animated, so even a 1 GB TV has nothing to keep up with. `0` keeps
the plain background; the default is off (the reference launcher looks exactly as before).

```json
{ "sky_intensity": 60 }
```

## Adding a theme

Open a PR with a new folder here, a one-line row in the table above and a screenshot from a real TV
in the PR description. Keep the dock list generic (the big streaming apps) unless the theme is for a
specific TV, and say which TV you tried it on.

### This fork's on-TV choices

Launcher settings retains **Theme / Aurora / Off**. When a theme provides `wallpaper.jpg` or a
`wallpapers/` collection, the same glass row also offers each picture by filename. Choosing one pins
it; Theme resumes the theme's rotation. Follow theme clears these overrides, including rows below
the dock, without removing saved app order, folders or App Library selections.

The stock upstream `dock_row`, `fill_dock` and `reorder_button` switches are intentionally unsupported:
this branch owns its glass dock and folder layout, and Move remains on each tile's menu.
