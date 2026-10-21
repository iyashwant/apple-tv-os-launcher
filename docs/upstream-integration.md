# Upstream integration — 22 October 2026

This branch integrates upstream `origin/main` at `622789f07fdc1b4b9914f900905a9065cee8c244`.
The previous integration stopped at `022c57da7c4f07466141229cb5625f6ae4fd7ba0`.
The incoming history contains 489 commits, including merge commits. The UI integration was validated
through `c1da7e10`; a follow-up merge brought in 79 more device-profile, documentation and benchmark
commits, with no further launcher changes.

## UI and data kept from this fork

- The translucent layered dock, cached app-colour focus halo, Outfit typography and six-column Home.
- The expanding grid, folders, folder previews and motion, per-tile menus and remote reordering.
- The glass App Library and quick-settings panel, firmware settings routes, grouped launcher settings
  and persistent navigation-sound controls.
- Saved app order, folder membership and names, App Library choices and existing preferences.
- Package identity `com.gathin.tvhome`, version `3.28` / code `31`, and the existing signing setup.
  The upstream optional `launcherApplicationId` build property is available for firmware allowlists;
  ordinary builds keep the existing identity.

## Incoming behavior adapted to that foundation

| Area | Integration |
|---|---|
| Home | Cinematic artwork, last-opened metadata and position dots are opt-in and use the fork's shelf and typography. Compact network controls, hidden network pill, optional Library shortcut and always-visible app names use its existing glass controls and key router. |
| Wallpapers | Theme / Aurora / Off remain available. The grouped settings row additionally selects a picture from the theme collection. Ordered or shuffled rotation, collection reconciliation and true-color decoding retain the fork's cropped, asynchronous decoder and memory guard. |
| Settings | Rows below the dock use a grouped settings row. Follow theme clears the added row and picture overrides alongside the fork's existing choices. |
| Apps and remote | Per-app actions, labels and bundled banners; scancode app-button mappings; shared LEANBACK query and lazy card-record loading. Spotify keeps its custom glyph treatment. |
| Inputs | HDMI-CEC device deduplication, custom port labels, vendor source-panel activities and omission of misleading AV connection state use the glass input menu. |
| Boot and memory | Theme configuration is cached for boot before external storage mounts. Low-memory cleanup runs only while Home is hidden and restores the layout on return. Clock visibility follows valid system time. |
| Clock and themes | Home-only idle clock, the shared clock scene, local sunrise/sunset calculation, race-weekend information in a glass panel, and the Scenes wallpaper collection. |
| Tools and translations | Upstream device profiles, firmware-drift checks, guarded debloating, screenshot/relay/upload helpers, documentation and six added languages. Strings for replaced stock controls are omitted. |

The stock `dock_row`, `fill_dock` and `reorder_button` switches remain unsupported: the fork owns
its dock and folder layout, with Move on each tile's menu. The upstream ring-based highlight is
covered by the fork's existing cached, diffused halo. Cinematic mode retains the layered glass dock.

The CI APK ceiling is 528 KiB, allowing the fork's bundled artwork, fonts and integrated features
while continuing to guard size growth. The imported maintainer-bot workflow is restricted to the
upstream repository, so publishing an independent fork does not activate that upstream automation.

## Validation

- `testReleaseUnitTest`: 134 tests passed.
- `lintRelease`: zero errors.
- `assembleRelease`, `assembleDebug`, `assembleDebugAndroidTest`: passed.
- Python discovery: 206 tests for the UI integration; 211 including the follow-up benchmark checks,
  passed with one skip.
- Android TV 14 emulator: all 13 smoke instrumentations passed, including 22 new integration
  checks plus folder motion, navigation, reordering, wallpaper, settings and quick-settings coverage.

The emulator fixtures restore the configuration and preferences they change. The physical TV was
not updated by this integration. The existing physical-TV screenshots in the README are reference
images from before this merge.

Recovery point: local branch `codex/tvos-before-origin-main-20261021` at `8cd3bd39`.
