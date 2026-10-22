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
| **Continue Watching** | Optional Home shelf for the app selected in the dock, with unfinished items, artwork and progress. Enabling asks for Android’s TV listings permission. Empty shelves stay hidden. |
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

Calendar/city requests and HTTPS poster thumbnails supplied by participating media apps are the app’s
network operations. Thumbnail requests send no viewing history or account credentials. Wallpaper playback,
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

## Continue Watching

Enable **Continue Watching** in Launcher settings. The row follows only the app selected in the dock.
Moving between dock apps replaces its contents immediately; an app with no unfinished items shows
no row or placeholder. Moving into the row keeps that app selected so you can resume its content.
App-grid and folder tiles do not select a Continue Watching source.

It reads searchable Watch Next items from the
Android TV provider and supplements them with unfinished recommendation items (such as Plex).
Only records with a saved position greater than zero and less than their duration, and a valid resume
link into the publishing app, are eligible. Next/new-episode suggestions, watchlists and completed
items are excluded. The shelf deduplicates records, sorts by recent engagement where available and
keeps up to six recent items for the selected app. Each app is cached separately so its items cannot
be crowded out by another app’s larger history. An app’s own published data determines accuracy; this is not access to
private account histories or Google’s cloud-only recommendations.

The shelf sits above the dock in the top-shelf space. Wide landscape artwork, a thin white progress
bar, and titles on the wallpaper match the Apple TV-style Home. The dock keeps its original position
with the feature on or off. **Up** from the dock enters the shelf, **Left/Right** selects content,
**Down/Back** returns to the dock, and **Up** from the shelf reaches the header controls. **Down**
from the dock opens the full app grid. The shelf adapts to the space above the dock and hides when
there is too little space, such as when several app rows are visible. Folders keep their normal presentation.
The live settings preview reflects the shelf when actual items are available.
Dock focus changes fade and gently slide the row in or out. Switching between supported apps fades
the old artwork away before bringing in the new app's cards; quick reversals continue from the
current animation frame. The dock stays fixed, and disabled animations or reduced motion show the
final state immediately. Outgoing cards cannot receive focus or launch content for the previous app.
Hiding the row during dock expansion leaves the grid's existing slide in control of its position,
including a quick Down/Up reversal.

The row and provider observers stop when the feature is disabled. Provider observation and refreshes
also stop while Home is paused. Titles and progress remain in memory only; this launcher does not
write history back or change another launcher’s Watch Next preferences. Poster thumbnails are limited
to 512 pixels on the longest edge, 3 MB encoded input and a 4 MB memory cache. Only HTTPS or the
publishing app’s content/resource URIs are read; unsupported or unavailable images use the app icon.

Resume links are restricted to their publishing package, checked for an exported/accessible activity,
and stripped of URI grants/selectors. This also avoids a system chooser when multiple installed app
variants handle the same link. If a source app is removed, its item disappears on the next refresh.

The privileged ThemeProvider’s `continue-watching-status` reports permission, filtered count and
per-app counts without returning titles. `ContinueWatchingSmoke` exercises real provider fixtures,
the toggle, navigation, launching, disabled state, filters, deduplication and interrupted transitions
on an emulator only.
