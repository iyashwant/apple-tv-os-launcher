# Upstream integration — 28 October 2026

The throwaway branch `codex/tvos-upstream-review-20261028` integrates upstream
`095b71ad93d99ea28d94d97b06385709d354b795` into the Apple-style fork at
`333e0c9e4742e0a226d24a2be0686301bec43ef5`. The shared base is
`622789f07fdc1b4b9914f900905a9065cee8c244`. Upstream contributes 2,553 commits
(including merges), 2,252 changed paths and 60 new resource configuration keys.
The initial three-way preview had 52 conflicted paths.

## Decisions

- OK opens every app in the glass App Library; hold manages its Home shortcut.
- The existing folder-aware glass tile menu gains Menu-key access and direct dock commands.
- Helper recovery support is included, with every recovery option off by default.

## Integration ownership

| Area | Result |
|---|---|
| Home and folders | Keep the layered dock, cached app-colour halo, six-column expanding grid, folder previews/transitions and `home-layout-v2.json`. Dock commands use that same atomic store, including unavailable-app ranks. The stock `Order`/`DockSlots` stores are not reintroduced. |
| Library | Preserve the frosted shelf and menus; port asynchronous catalogue/label/icon loading, stale-load cancellation, selection/open modes, pre-seeding and exclusions. |
| Settings | Keep grouped glass rows, custom firmware routes, personal overrides and live preview. Add revision tracking for exits through Home, vendor/power-menu destinations, dock opacity, clock wallpaper/glow, and optional startup-app/network-wait choices. |
| Remote and inputs | Keep button learning and personal precedence. Port tap/hold/front mappings, action/input/guide targets, controller/volume compatibility, vendor source routes and connected-only input folding into the existing menu. |
| Launching | Tiles, Library, app-action editor and header shortcuts share package-scoped URI/action semantics. TV inputs route through the existing picker implementation. Continue Watching keeps its stricter publishing-app resume-link validation. |
| Wallpaper and clock | Keep Scenes, Aurora/Off/pinned choices, cropped bounded decoding and shared folder backdrop. Add time-of-day groups/captions, dim once into the bitmap, prepare GPU upload, trim recovery and Home wallpaper behind the moving clock. |
| Sports and dates | Keep cached/network-updated F1 data and the glass race panel; optionally rotate Game Day add-on data in that panel. Add lunar dates, launcher language override and new locales, retaining fork strings and font fallbacks. |
| Tools and content | Import maintained safety/tool improvements, 155 new TV lists, generated metadata/indexes, optional add-on source and themes. Add-ons remain separate apps. |
| Packaging | Keep package identity, version 3.37/code 40, Scene asset preparation and instrumentation runner. Import upstream CI checks and the separate add-on build workflow; retain the fork’s APK gate and upstream-only maintainer-bot guard. |

## Intentional differences

The fork does not use the stock `single_row`, `dock_band_all`, `dock_row`, `fill_dock`,
`reorder_button` or `top_menu` layout engines. Its own expanding grid, folders and quick settings
remain authoritative. Stock `app_glow`/`glow_style`/`dock_glass` renderers do not replace the
existing app-colour light and glass material. Top-bar app shortcuts preserve Home membership.
YaOS routing catches a permission refusal; this integration does not add its vendor permission.

The fork already declares network and TV-listings permissions for city/race data and optional
Continue Watching. The accepted recovery work additionally declares WRITE_SECURE_SETTINGS and
dormant recovery components. Normal installation grants no secure-settings permission and enables
no notification listener. Recovery flags and standby opt-ins remain off.

The old screenshots document the physical TV before this integration. They are design references,
not evidence of the resulting merge. No TV has been modified by this work.

## Validation

- Unchanged fork baseline: 211 Python tests passed, one skipped.
- Integrated repository: 2,132 Python tests passed, 30 skipped.
- Android: 299 JVM tests passed; lint had zero errors and 157 warnings.
- Release assembly and instrumentation-test APK compilation passed.
- Release APK: 1,656,774 B, below the existing fork limit of 1,835,008 B.
  The size limit was not changed.
- Focused dock-model tests cover grid insertion, swaps, missing-app ranks, folder extraction,
  last-folder-item handling and stale targets.

Physical-TV D-pad, remote hold/volume behavior, reboot/standby/recovery and visual acceptance have
not been run. Instrumentation compilation is not an instrumented device run. The merge is a local
review draft; it does not update the source branch, push to GitHub or install on a TV.
