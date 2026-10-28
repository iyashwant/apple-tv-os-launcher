# The launcher

[Back to the README](../README.md#the-launcher)

A small native home screen for Android TV: **about 532 KB**, plain Java, built for TVs with 1 GB of RAM.
The reference launcher is offline. This Apple-style fork includes its existing bounded glass snapshots, optional city/race lookups and Continue Watching; see [fork features](features.md). This page has the details the
README leaves out: settings, languages, remote keys and building it yourself. Every key a theme can
set is in [`themes/README.md`](../themes/README.md#keys), and the opt-in switches with their
screenshots are in [`docs/switches.md`](switches.md).

## Which TVs

The **TV** needs Android 8.0 (API 26) or newer: `minSdk` is a compile-time floor in
[`launcher/app/build.gradle`](../launcher/app/build.gradle), and the install script asks the TV for
`ro.build.version.sdk` before it builds, so an older set is told both numbers instead of failing at
`pm install` (see [Troubleshooting](TROUBLESHOOTING.md) for what is left on such a set).

## Build it yourself

It needs JDK 17 and Android SDK platform 34. On macOS: `brew install openjdk@17 android-commandlinetools`
and `sdkmanager "platforms;android-34" "build-tools;34.0.0"`. On Linux the install script finds
`~/Android/Sdk` and the `java` on your PATH.

## Configure

[`config.xml`](../launcher/app/src/main/res/values/config.xml) sets:

- the dock order, hidden apps and the Live TV package;
- columns (tiles shrink from 5 up) and rows under the dock;
- an optional VPN app for the network pill;
- `reduced_motion`, for TVs whose GPU cannot keep up.

Themes set the same keys plus a background colour and a wallpaper.

## Launcher settings and the default home

Click **Settings** for the Apple-style glass quick settings. Its sliders button opens Launcher settings.
Hold OK on Settings for **Launcher settings**, **Native settings** where available, **Restart…** while
the Home helper can open the system power menu, **Module cards**, and **App library**.
Native settings includes the vendor's own pages, such as Xiaomi's Account & Security with its ADB switch.

The grouped settings page includes **Appearance**, **Experience**, **System**, **Wallpaper**,
**Apps per row** 4, 5, 6 or 7, **Rows below dock** 0 to 3, **Animations**, **Advanced effects**,
**Navigation sounds**, **App Library**, **Continue Watching**, **System info**, **Sky & race weekends**,
**Idle clock**, **App customization**, **Remote buttons**, **Follow theme**, **Choose default home**,
and **TV system settings**. The miniature preview updates as choices change.
Wallpaper settings offers **Dock band opacity** 100%, 70%, 50% or 30%, Theme/Aurora/Off, Scenes,
rotation, order, full color and dimming. Idle clock has wallpaper and ambient-glow choices. With
`open_on_start_choice` set, it also offers **Open on start** and a bounded wait for network.
The folder-aware tile menu provides Move and direct dock actions; there is no second stock reorder store.

**Change the default home:** Launcher settings and the optional Default-home tile
(`show_home_settings_shortcut`) open Android's home picker. Choose the original launcher to stop using
CleanYourTV as Home, or CleanYourTV to return. The original launcher must be enabled; if the install
script disabled it, run the [restore script](../README.md#restoring-your-tv) first. Some TVs provide no
home picker.

## Languages

English, German, Danish, Spanish, Catalan, French, Russian, Serbian (Latin), Polish, Czech, Italian, Portuguese,
Turkish, Arabic, Hebrew, Simplified Chinese, Traditional Chinese (Taiwan), Thai, Finnish, Vietnamese,
Bangla, Indonesian, Norwegian Bokmål, Swedish, Dutch, Uzbek (Latin), Ukrainian and Korean. The launcher follows the TV's language. Android 13+ can set it for the launcher
alone: `tools/tv.py 'cmd locale set-app-locales com.gathin.tvhome --locales ca'`.

Hebrew mirrors the layout:

- menu buttons on the left, date and clock on the right with the greeting above;
- dock and app rows starting on the right, with matching remote navigation;
- the input selector's icons and labels on the right;
- dates with the month prefix ב (for example, 19 באוקטובר).

The Clock screensaver right-aligns its date and time. Russian covers every screen, including race
weekends, festival greetings and the Shamsi date. Other languages keep their original layouts.

## Module cards

Any TV app can declare its own tile (label, caption, glow). The launcher asks you once per app, keeps it
hidden by default, and remembers the answer for that app's signing key and card name. Hold OK on Settings
and choose Module cards to change your mind. How to make one: [`docs/modules.md`](modules.md); the
contract: [`docs/specs/card.md`](specs/card.md). Ready-made ones are in [`addons/`](../addons/), and
[cleanyour.tv/addons](https://cleanyour.tv/addons) shows the themes, switches and add-ons together.

## Clock screensaver

Pick "Clock" in the TV's screensaver settings, or over ADB:

```bash
tools/tv.py 'settings put secure screensaver_components com.gathin.tvhome/.ClockDream; settings put secure screensaver_enabled 1'
```

## Remote keys

- An optional accessibility service keeps the remote's **Input** button working on Google TV
  (`INPUT_KEY_SERVICE=1` at install).
- It can bring back other buttons only the stock launcher answered, such as the **YouTube** button
  (`app_keys` and `input_key_keycodes`, in config.xml or a theme; `HOME_KEY_SERVICE=1` reads `app_keys` too).
- A `hold:` prefix on an `app_keys` entry gives that button a second app: a normal press opens the
  unprefixed app, holding for half a second opens the hold one (by keycode or `hold:scan:...`). An entry can
  name an activity (`package/.Activity`), such as an add-on's quick bar, instead of the app's launcher entry.
- In place of an app, an `app_keys` entry can name an action: `back`, `home`, `recents`, `mute`, `sleep`,
  `screensaver`, `settings` or `wifi`, so a spare colour button can mute the TV or, held, put it in standby.
- `HOME_KEY_SERVICE=1` also fixes sticks whose Home key sends ALL_APPS, and opens Settings from the remote's
  **gear** button, even one Android does not map (scancodes or keycodes in config.xml).
- With `settings_key_panel` the gear opens a [small quick settings panel](quick-settings-panel.jpg)
  over Home or any app: TV settings, Display, Network, Apps and Launcher settings.

The full `app_keys` format is in the [`themes/README.md` keys table](../themes/README.md#keys).

## Navigation and artwork

Up and Down move one row. The top buttons never fall through to tiles, and Down from them returns to the
dock tile you came from. The last focused app is remembered, and installing or removing an app refreshes
the tiles while Home is open. App banners load at xhdpi or better, so low-density TV UIs do not downsample
them before the focus zoom.
