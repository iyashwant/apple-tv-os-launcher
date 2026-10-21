# Features on the TV

Open the gear, choose the sliders button beside **Open All Settings**, then choose a section in
Launcher settings. Every section uses the fork's grouped glass controls and D-pad focus treatment.
Choices apply to Home when you return from settings. The live miniature on the left updates immediately
with the chosen columns, visible rows, current wallpaper, dimming, sky and app artwork. App
customization previews the selected app, including a replacement icon and its display name.

| Section | Controls |
|---|---|
| **Wallpaper** | Theme, Aurora, Off, all 14 bundled Scenes as a rotating collection, or one pinned Scene/theme picture; interval, sequential/shuffle order, full-color decoding, and dimming. |
| **Sky & race weekends** | Sky intensity, city search, race-weekend panel, and calendar update. |
| **Idle clock** | Off or 5/10/15/30/60 minutes, plus a preview. It starts only from idle Home and consumes the wake key. |
| **App customization** | Select an installed app, change its display name or launch action, use its own icon or another installed app’s icon, choose built-in TV artwork, or import an image with the system file picker. Reset restores the app's theme/default behavior. |
| **Remote buttons** | Learn a physical button and choose its app; change or remove an assignment. For operation from other apps, enable **Remote button helper** in Android Accessibility. |

Folder views share Home’s current wallpaper, dimming and sky, including wallpaper rotation while a folder
is open. They reuse the existing full-size wallpaper bitmap. An icon borrowed from another app changes
only the tile’s appearance; its launch destination stays the same. If the source app is removed, the tile
falls back to its own icon.

Scenes ship inside the APK and are extracted into private storage when first selected. No theme
upload is needed. Choosing Scenes starts with an hourly rotation when no rotation interval is set.
Full-color decoding remains a choice because it uses more memory. Image imports are bounded to
8 MB encoded input and 512 pixels on their longest decoded edge; the original document is not modified.

App and remote customizations are stored separately from theme choices. **Follow theme** clears
the visual/clock/race overrides and preserves the city, app customizations, remote mappings,
app order, folders and App Library selections. A blank local app action can explicitly override a
theme action and open the app normally; resetting that app restores the theme's behavior.

The location is a city selected by the user, rather than continuous GPS. City search uses
[Open-Meteo's geocoding API](https://open-meteo.com/en/docs/geocoding-api), with location data
from GeoNames. Sunrise and sunset are then calculated locally on the TV.

Race sessions come from the [Jolpica F1 API](https://github.com/jolpica/jolpica-f1/blob/main/docs/endpoints/races.md).
The app includes a 2026 calendar and keeps the last successful download. Enabling the panel or
choosing Update requests a fresh calendar; while enabled, an outdated calendar is refreshed from
Home at most weekly, with failed retries limited to once per six hours. Network failure keeps the
cached calendar. The panel appears only near a race weekend. It is a schedule, not live results.

These public calendar/city requests are the app's only new network operations. Wallpaper playback,
the clock and sunrise calculations work offline after setup. No account, location tracking or
analytics is involved.

Home, Back, volume, power and D-pad/confirmation keys cannot be captured by button learning.
Cancel or leaving the settings page removes the learning listener. Existing accessibility services
are preserved; the app does not turn them on or replace their system list automatically.

## Deployment and verification

The existing privileged ThemeProvider supports `feature-status` and bounded `feature-settings`
calls for ADB setup. Calls explicitly enforce `WRITE_SECURE_SETTINGS` (or the app's own UID), and
reject unknown keys, invalid types and out-of-range coordinates before saving. This interface does
not expose arbitrary private files or app-order data.

The feature smoke instrumentation runs only on an emulator, restores the preferences it changes,
and exercises the native screens, offline Scene collection, rotation options, app customization,
clock preview, coordinate validation and scancode learning/cancel behavior.
