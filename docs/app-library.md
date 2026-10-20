# App Library

The App Library is on by default. A frosted pill after the last row of Home apps opens it; press
Down from the last row to reach it. You can also open it from the Settings button's long-press menu.
Turn it off in Launcher settings (Experience → App Library), with the `app_library` bool in
`config.xml`, or with `"app_library": false` in a theme's `config.json`.

The library is one horizontal glass shelf over Home. It lists installed apps with a TV or an ordinary
Android launch activity, preferring the TV entry when both exist. It leaves out services and apps
without a launch activity. Ordinary Android apps may still need touch or a mouse.

Apps missing from Home come first. Apps first installed within the last seven days lead that group,
newest first, with a blue dot; older missing apps are alphabetical. Apps already on Home follow
alphabetically, with a green check. Updating an existing app does not mark it as new.

- Press OK on a missing app to add it to Home. Focus moves to the next missing app instead of
  following the new shortcut across the shelf.
- Press OK on a checked app to open it.
- Hold OK for Open, Add to Home or Remove from Home, and App info, in the same frosted menu as a
  Home tile's.
- On Home, hold OK on a tile and choose **Remove from Home**. The app leaves Home, and its folder if
  it was in one, but stays installed and stays in the library, where one press adds it back.
- Back closes the menu first, then the library. Up from the App Library pill returns to the last row.
  Hebrew mirrors the shelf's direction.

Every app can be removed from Home, including the theme's dock apps. There is no hidden-app section
and no uninstall action.

Choices are stored in the launcher's private `library_apps.txt` and `library_home_removed.txt`, with
at most 128 entries in each. Each file is written atomically, and an unreadable file is left unchanged
if an edit fails. Choices survive restarts and app updates; missing or disabled packages are omitted
until they return. Explicit Home removals override theme defaults until re-added. Theme config, the
saved order and folders, and module-card decisions are not edited. Turning the library off keeps
existing Home choices.

The library refreshes after app changes through Home's existing package receiver. Labels, Home
membership and each app's focus colour are cached per refresh; icons load only for cards on screen.
The panel blurs a small snapshot of the Home behind it once, when it opens; nothing is captured or
blurred again while it is open. The focus light follows the launcher's animation setting, including
instant movement when animations are off. There is no polling, network, new service, permission or
dependency. The manifest's package query covers ordinary LAUNCHER activities on Android 11 and later.
