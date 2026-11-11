# Lite IPTV (Android TV)

Kotlin + Views + Media3 ExoPlayer. No database, no DI, no extra network library (HttpURLConnection + streaming JSON).

## Build
1. Open the folder in Android Studio (Koala or newer, JDK 17) and let Gradle sync.
2. Run on an Android TV emulator (API 28+, 1080p) or a real device:  Run > app
   or: ./gradlew assembleRelease  (signed with the debug key so it installs directly).
3. In the app: Playlist > Add Xtream / Add M3U, then back to Home.

## Remote map
- Everywhere: D-pad moves focus, OK selects, Back goes up one level. Long-press OK on a channel/poster = favorite.
- Browse screens: moving focus over a category switches the list instantly.
- Player (live): Up/Down or CH+/CH- = zap, OK/Menu = controls.
- Player (VOD): Left/Right = seek (hold = faster), OK = pause + controls, Next/Prev keys = next/previous episode.

## Files you may want to change
- res/drawable/bg_main.xml : replace with a stadium image (e.g. res/drawable-nodpi/bg_main.jpg, delete the xml).
- res/drawable/banner.xml  : replace with a 320x180 launcher banner PNG.
- Colors: res/values/colors.xml.

## Changes in this version
- Live/Sports: OK on a channel plays it in the preview pane; OK on the same channel again opens full screen; Back returns.
- Long-press OK on a channel: favorite or **delete channel** (Settings > Restore deleted channels brings them back).
- Arabic is the default language (change in Settings).
- Movies/Series are sorted newest first ("added"); categories keep the provider's order.
- No channels, movies or sources are built in: add your own Xtream / M3U in Playlist.
- Build fix: added androidx.activity:activity-ktx (needed by addCallback).
