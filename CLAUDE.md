# Free My TV: notes for Claude

A home screen for Google TV and Android TV that replaces the stock one, plus a settings app that switches between
Free My TV and the TV's original home screen. A plain Android app in Java, built without Gradle by `build.sh`.
LG TVs (webOS) and Samsung TVs (Tizen) get the same home screen as a web app: `web/`, packed by `webos/` and `tizen/`
(see below).
The website (freemytv.io) is in `docs/`: static HTML, CSS and JS, no build step. A push to `main` that changes it
publishes it to GitHub Pages (`.github/workflows/pages.yml`). `docs/index.html` (English) and `docs/pt/index.html`
(Brazilian Portuguese) are the same page in two languages, so change both; visitors from Brazil land on the
Portuguese one by themselves, and the PT/EN switch in the menu is remembered.
Before you push, run `tools/check.sh`: the Google TV build, `translate.py --check`, the LG and Samsung packages and
ES5 in `web/` (syntax and the newer calls old TVs lack). Every pull request runs it too (`.github/workflows/check.yml`).

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Installing it for someone

1. **Ask** for the TV's IP address and their city, if they didn't say.
2. **Tools:** a JDK 17+, the Android SDK command-line tools (build-tools 34+, platform android-34) and adb. Check
   what's already there (`java -version`, `$ANDROID_HOME`, `adb version`) and install only what's missing.
   - macOS: `brew install --cask temurin android-commandlinetools android-platform-tools`, then
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17, Android's command-line tools and adb from the package manager, then the same `sdkmanager` line.
   - Windows: Git Bash (what Claude Code runs there; `build.sh` handles it) or WSL, then as on Linux.
3. **Settings:** run `./build.sh` once; it creates `config.properties`. Fill in `latitude` and `longitude` (decimal
   degrees) and `city` for their city, `tmdb_region` (two-letter country), `show_crypto`, `currency` and `temperature` to match.
4. **The TMDB key is theirs and secret.** Ask them to create one (free: themoviedb.org → Settings → API) and paste it
   after `tmdb_key=` in `config.properties` themselves. Don't ask for it in the chat, never print it, never commit
   it. If they paste it in the chat anyway, put it only in `config.properties`.
   Without a key everything works except the picks and the screensaver photos.
   The same goes for the optional `watchmode_key` (free, watchmode.com; makes picks open the title's page): theirs, secret, only in `config.properties`.
5. **Build:** `./build.sh` → `FreeMyTV.apk`. Keep `freemytv.keystore`: updates must be signed with the same key.
6. **Install:** `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*. Then `adb install -r FreeMyTV.apk`. On TCL, also `adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"`
   (some firmware calls the operation `APP_AUTO_START`; this tries both):
   TCL's `TclAppBoot` blocks background jobs by default, so the switch (and the watchdog) never run. The log shows
   `forbid bind service ... ModeJob`. Some TCL firmware has neither (a 2024 MediaTek Google TV, Android 14, has no
   `TclAppBoot`): both names answer `Unknown operation string`, and nothing needs allowing.
   On a Mac, `adb connect` can fail with `No route to host` while the TV answers (`nc -z <IP> 5555` connects): macOS's
   Local Network privacy blocks the adb server that adb starts in the background. Start it from the same terminal
   instead (`adb kill-server; adb nodaemon server &`), or allow the terminal app under System Settings → Privacy &
   Security → Local Network.
7. **Tell them what the switch does** before they make it: the home screen and screensaver change, a short list of
   promo apps is turned off (never uninstalled), and *Original* brings it all back. On TCL, the remote's Netflix,
   YouTube and Prime buttons keep working through a small accessibility service that only reads those buttons
   (*Free My TV* → *Remote Buttons* picks what each opens); *Original* turns it off.
8. **They switch on the TV:** *Free My TV* → *Home Screen* → *Free My TV*. The app uses the TV's own ADB on
   127.0.0.1 with its own key, so the TV asks once more to allow debugging. Don't switch modes from the computer.
   If the TV's Wi-Fi was saved during its first setup, the switch stops before changing anything and asks them to
   forget the network and connect again in Settings (Network & Internet); then the switch goes through. Saved
   networks out of range don't show in Settings: find the ones with `cname=com.google.android.tungsten.setupwraith`
   in `adb shell dumpsys wifi` (each block starts with its `ID:`), and with their OK remove them with
   `adb shell cmd wifi forget-network <id>`.
9. **Check:** `adb logcat -s FreeMyTV` shows what the switch did.

## Installing it on an LG TV (webOS)

On LG it's an app, installed through LG's Developer Mode, and it changes nothing else on the TV: LG's home screen
stays, and Free My TV opens with a long press of a number key.

1. **Ask** for the TV's IP address and their city, if they didn't say. Tools: Python 3 and OpenSSH (`ssh`,
   `ssh-keygen`), already on macOS, Linux and Windows 10+.
2. **On the TV** (they do it): LG Content Store → *Developer Mode* → sign in with a free LG developer account
   (webostv.developer.lge.com) → *Dev Mode Status* on (the TV restarts) → *Key Server* on. It shows the IP and a
   passphrase.
3. **Settings:** the same `config.properties` as on Android (the first `python3 webos/build.py` creates it). The
   TMDB key rules above apply.
4. **Build:** `python3 webos/build.py` must end with `Built FreeMyTV-webos.ipk`.
5. **Install:** the first time needs the TV's passphrase, typed by them: ask them to run
   `python3 webos/install.py <IP>` in their own terminal (yours can't answer its prompt, and the passphrase doesn't go
   in the chat). It keeps the unlocked key in `~/.ssh/freemytv_webos_<IP>`, so later updates run from your terminal
   with the same command.
6. **Tell them:** while Free My TV is open, holding a number key pins it to that key. Developer Mode lasts about 40
   days; *EXTEND* in that app renews it (if it runs out, the TV removes developer apps, and installing again brings it
   back). `--uninstall` removes Free My TV.

## Installing it on a Samsung TV (Tizen)

On Samsung it's an app too (2017 and newer, Tizen 3+), installed through Samsung's Developer Mode; Samsung's home
screen stays. Two ways:

- **The release, no tools:** TizenBrew Installer (github.com/reisxd/TizenBrewInstaller) installs the latest GitHub
  release when they type `simbasjungles/free-my-tv`, and on 2023+ TVs signs it with their Samsung account. That build
  has no TMDB key (no picks) and takes the city from the TV's time zone. Walk them through the README's steps.
- **Their own build** (their city, their TMDB key), below.

1. **Ask** for the TV's IP address and their city, if they didn't say.
2. **On the TV** (they do it): *Apps* → type `1 2 3 4 5` on the remote → *Developer mode* on, *Host PC IP* = this
   computer's IP → restart the TV.
3. **Tools:** Samsung's `tizen` command to sign (Tizen Studio's CLI, or Samsung's Claude Code plugin
   `tizen-sdk-skills` from github.com/Samsung/tizen-agent-skills, which installs it and makes Samsung certificates),
   and `python3 tizen/install.py` to install, which speaks SDB itself. `tizen` runs on Java; Samsung's `sdb`, its new
   `tz` and Tizen Studio's installer (with its own JDK) are Intel binaries on macOS. On Apple Silicon without Rosetta,
   take the CLI from Samsung's package repository (download.tizen.org/sdk/tizenstudio/official, `pkg_list_macos-64`;
   check each zip's SHA256 there): unzip the `data/` of `new-common-cli`, `certificate-generator`, `sdk-utils` and
   `sdk-utils-core` into `~/tizen-studio`, then write `~/tizen-studio/sdk.info` with three lines,
   `TIZEN_SDK_INSTALLED_PATH=<that folder>`, `TIZEN_SDK_DATA_PATH=<home>/tizen-studio-data` and `JDK_PATH=<a native
   JDK 17's .jdk folder>` (Homebrew: `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk`). That `tizen` signs natively.
4. **Certificate, once:** 2023+ TVs (Tizen 7+) need a Samsung certificate, made with their Samsung account for this TV
   (its DUID): the plugin's Samsung profile opens the browser for them to sign in, or Tizen Studio's Certificate
   Manager does it. 2017-2022 TVs take a Tizen certificate (`tizen certificate`, then `tizen security-profiles add`
   and `tizen cli-config "profiles.path=<home>/tizen-studio-data/profile/profiles.xml"`; on macOS the passwords go to
   the login Keychain). Certificate passwords are theirs: they type them in their own terminal, never in the chat.
5. **Settings:** the same `config.properties`; the TMDB key rules above apply.
6. **Build and sign:** `python3 tizen/build.py --sign <profile>` must end with `Built FreeMyTV-tizen.wgt (...,
   signed with "<profile>")`.
7. **Install:** `python3 tizen/install.py <IP>`: it pushes the app over SDB (port 26101), installs it with the TV's
   `0 vd_appinstall` and opens it with `0 was_execute` (tested on 2017 Tizen 3.0 and 2019 Tizen 5.0 TVs); it only
   reports success when the TV says `install completed` (`cmd_ret:0` comes after failures too). `--uninstall` removes
   it; some TVs' sdbd doesn't answer that, and then it's removed on the TV (Apps, Settings, Free My TV, Delete).
   With Samsung's tools instead: `sdb connect <IP>`, `tizen install -n FreeMyTV-tizen.wgt -t <serial from sdb
   devices>` and `tizen run -p FreeMyTVio.FreeMyTV -t <serial>`. Updates need the same certificate: the TV refuses
   one signed by another (uninstall first). A retail TV's sdbd only takes files (`filesync_support:push`): nothing
   can be pulled from it. To look inside the running app, `0 debug FreeMyTVio.FreeMyTV 300` over SDB opens it with
   a Web Inspector port (Runtime.evaluate works on Tizen 3; screenshots don't).
8. **Tell them:** *Autorun Last App* in the TV's settings (under Start Screen Options; where it is changes by year)
   makes the TV turn on into Free My TV.

## Releases

TizenBrew Installer takes the first `.wgt` of the latest GitHub release, so every release carries
`FreeMyTV-tizen.wgt`: built with `--generic` (no config.properties: no key, the city from the time zone) and signed
with a Tizen certificate (`--sign`), which 2017-2022 TVs install as it is; on 2023+ TVs the installer signs it again
with the person's Samsung certificate. Sign every release with the same author certificate: older TVs refuse an
update signed by another. `python3 webos/build.py --generic` makes the LG `.ipk` that goes along.

## Adapting it to another TV

Read first, change after. Read-only commands that tell you what the TV has:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd role get-role-holders android.app.role.HOME
adb shell settings get secure screensaver_components; adb shell settings get secure screensaver_default_component
adb shell pm list packages -s     # system apps (-3 for the ones they installed)
```

Then, in the code:

- `HomeMode.DEBLOAT`: add the brand's promo, demo, store-mode and telemetry apps, one comment per line. Only those.
- `HomeMode.PROTECTED`: add the brand's system pieces: picture, sound, inputs and HDMI-CEC, the tuner and live TV,
  remote and Bluetooth pairing, setup, updates, system UI. When in doubt, protect it. `mayTurnOff` also refuses
  Android's own pieces (`CORE`: with the permission controller or package installer off, a TV may not start again) and
  factory menus (`FACTORY`), and `neededByTv` keeps on, on the TV itself, anything that asks for `TV_INPUT_HARDWARE`
  (the layer under HDMI and the tuner: off, inputs show "No signal") or receives the remote's Input key
  (`fusion.android.tv.demo` does on several MediaTek boards).
- `HomeApps.SHELF`, `LIVE_TV_APPS` and `HIDDEN`: the shelf apps, the brand's live TV app, apps to hide from All Apps.
- Build, install, have them switch on the TV, read `adb logcat -s FreeMyTV`, then have them switch back to
  *Original* once and confirm everything returned.

LG and Samsung: the web app above. Other TVs that don't run Google TV or Android TV: the way is a Google TV streamer on
HDMI (Chromecast with Google TV, Google TV Streamer, onn 4K, NVIDIA Shield). Set Free My TV up on the streamer.

**Uninstalled while in Free My TV mode?** The apps it turned off stay off. Bring them back with
`adb shell pm enable <package>` (the `DEBLOAT` list and the stock home screen), then
`adb shell cmd package set-home-activity <stock home activity>` and
`adb shell settings put secure screensaver_components <stock screensaver>`.

## The code

- Java 8 language level, minSdk 26 (Android 8), targetSdk 33. No Gradle, no libraries: only the Android SDK.
  Android 9 and older have no RenderNode (`Recording` wraps it, with a Picture there) and no `cmd role`. On Android 8 to 11
  (`Ui.LEGACY`) the home screen skips the GPU layers, draws blurred shadows from cached bitmaps (`Shadows`) and
  rounds images with a shader instead of clipPath: their GPUs (a Mi Box 3's Mali-450) choked on those.
  Android 11 and 12 run the screensaver as `ScreensaverAlone`, in the main process (its DreamActivity needs the dream in
  the app's default process, without a home activity; a BRAVIA BF1 on Android 12 crashed with it in `:home`);
  `res/values-v30/bools.xml` picks it (`values-v33` sets it back), and `HomeMode.DREAM` matches; every other version
  keeps `Screensaver` in `:home`. Keep them in step.
- Your own photos (`Photos`): `tools/google_photos.py <IP>` signs in on the computer (an OAuth "Desktop app" client in
  `config.properties`: `google_client_id`, `google_client_secret`), opens the Google Photos picker in the browser and
  copies the picked photos over adb to `/sdcard/Android/data/io.freemytv/files/photos` (Google's TV sign-in can't ask
  for Photos, and apps can't read albums, only picked photos). With any there, the home background is one of them
  (whole, with a veil: `Backdrop.forPhoto`) and the screensaver shows them instead of the picks. How often each
  changes is in Home Settings (`Prefs.photoHomeMinutes`, `photoDreamSeconds`: the last two rows). `--add` keeps the
  ones on the TV (files are named date + a hash of the Google id, so a photo picked again replaces itself); `--clear`
  removes them. Easier: Settings → Your Photos (`PhotosActivity`) shows a QR code for `PhotoServer` (port 8765, only
  while that screen is open, a random key per visit): the phone's page shrinks each photo to 2560 px and posts it.
- Cinematic home (`HomeView.cinematic()`, without the picks): dark tone unless Theme is Light, the photo darkened
  toward the bottom (`Backdrop.cinematic`), as on Apple TV: Home · Apps · Settings in a capsule at the top right (next to the buttons; the chips are at the top left),
  the clock and date, the shelf (`C_SHELF_TOP`; `onMeasure`/`onLayout` move its layers), then Continue watching
  (`History`, zone `Z_ROW`; Down from it opens the drawer); weather and cricket chips by the top buttons.
- Photo sets: sub-folders `morning`, `afternoon`, `evening`, `night`, `favourites` under the photos folder (the phone's
  upload page has an "Add them to" choice; photos without one are "every day"). Home Settings → Wallpaper photos
  (`Prefs.wallpaperMode`: all, time of day, favourites) decides what `Photos.pool` gives the home screen and the
  screensaver; an empty set falls back to the everyday photos, then to all. Your photo as the wallpaper fades in over
  2.6 s and drifts slowly (`HomeView.drawLightImage`: zoom 1.035 to 1.105, never showing an edge; the sky layer is
  redrawn at about 30 fps while it shows and nothing covers it).
- Sharpness (your photos): `Backdrop.forPhoto` keeps the photo's own pixels (decoded up to about 3000 px, no pre-resize,
  no baked tint; `srcRect` is the 16:9 part that fills the screen) and `HomeView.drawPhotoShade` draws the shading over
  it live. Each extra resampling softens, so don't add one. A light 3x3 unsharp mask in `Backdrop.sharpen` offsets the
  GPU's bilinear softening (measured on the BF1 against a Lanczos reference: 0.75 without it, about 1.15 with it;
  4x the strength gave 3.8 and looked harsh). `Photos.pool` leaves out photos under 1600 px on the long side
  (`MIN_LONG_SIDE`): on a 1080p screen they look blurred however they are drawn; they stay on the TV and are counted
  on Your Photos.
- Telegram wallpapers (`Telegram`): a bot (`telegram_bot_token`, made with @BotFather) that is admin of the channel
  (`telegram_channel`, optional filter). Every 10 minutes while the home screen is on: `getUpdates` (new posts; also
  learns the channel's id and your /start), then a walk by post number (`forwardMessage` to your chat with the bot,
  read, `deleteMessage`; one a second, 60 a visit): nothing is missed when the TV was off longer than the day Telegram
  keeps updates, and older posts come in too. Only a channel admin's /start is accepted (`getChatMember`). Photos are
  saved as `yyyy-MM-dd_tg<file_unique_id>.jpg` (largest size; files up to the Bot API's 20 MB); a caption with
  `#morning`/`#afternoon`/`#evening`/`#night`/`#favourites`/`#fav` picks the set. Normal Telegram photos are 1280 px,
  under `Photos.MIN_LONG_SIDE`: post as a file or in HD.
- Phone notifications: `phone/` is a second app, Free My TV Phone (built first by `build.sh` into
  `app/assets/phone.apk`, same key). Its `Forwarder` (a NotificationListenerService) posts alerts to `NotifyServer`
  (port 8766, main process, kept alive by `PlaybackListener`; key in `phone-key.txt`), which shows `AlertOverlay`
  (a small card under the top buttons, 6 s; drawing over other apps: appops `SYSTEM_ALERT_WINDOW`, in `History.ensureAccess`). Settings → Phone Notifications
  (`PhoneActivity`) has the pairing QR: the page serves the phone app and opens it with `freemytv://pair`. Sideloaded
  on Android 13+, the phone app needs "Allow restricted settings" (App info → ⋮) before notification access.
- Cricket (`Cricket`): CricketData.org `currentMatches` with `cricket_key`; only live matches of `cricket_teams`;
  every 15 min during one, 30 otherwise (the free plan has 100 lookups a day).
  To check for newer APIs, compile against `platforms/android-26/android.jar`: only AGSL (`RuntimeShader`, guarded
  by a try/catch), `BitmapShader.setFilterMode` (guarded), `Recording.Node` and `Ui.Weighted` (the Android 9 font weight) should come up.
- The home screen is drawn by hand on one Canvas (`HomeView`); the glass and the sky are AGSL shaders (`Shaders`,
  Android 13+, with a fallback for 8 to 12). Keep it at 60 fps: no allocations in `onDraw`, no work on the UI thread.
- Some TVs draw their UI at 1280×720 on a 1080p panel (TCL's 1 GB models: `ro.surface_flinger.max_graphics_width`
  and `_height`, `ro.config.low_ram`). There every pixel and every MB counts: `Backdrop` builds its images at the UI's
  size (and the picks use TMDB's w1280), and the shelf logos have a copy at the resting size and one at the focused
  size, so both draw pixel for pixel.
- `HomeMode` runs every switch as a background job (`ModeJob`) in the main process; the UI is in the `:home`
  process. `Watchdog` goes back to the stock home screen after 3 crashes in 5 minutes.
- On-screen text: `Ui.tr("English")`, translated from `app/assets/i18n/<lang>.json` (keyed by the English; one
  HashMap lookup, English when a language lacks it). Text built at runtime is a pattern: `Ui.trf("{n} min ago", "n", 12)`,
  never concatenated words. A key `"context|Text"` is the same English where a language may need another word
  (`"unit|Automatic"`, `"format|h:mm"` holds a SimpleDateFormat pattern). A count with a word is
  `Ui.plural("plural|one:{n} season|other:{n} seasons", n)`: each catalogue gives one form per CLDR plural category of
  its language (`one`/`few`/`many`/`other` in Russian, Ukrainian, Polish; only `other` in Japanese, Chinese…), and
  ICU's rules pick one. Portuguese, Spanish, German and Russian are hand translations: the reference, edited by hand,
  never replaced with machine ones. Dates and numbers use `Ui.locale()`. Text
  drawn every frame is cached and made again when `Ui.generation` changes. After changing text, run
  `python3 tools/translate.py`: it rebuilds `en.json` from the code and lists what each language lacks; translate those
  in every catalogue (or `--fill` with `ANTHROPIC_API_KEY`, which sends only the strings), and keep placeholders as they
  are. Run `python3 tools/translate.py --check` to validate all catalogues without writing files.
  No right-to-left languages yet (the layout isn't mirrored); CJK and Devanagari use the system font.
  Comments in English, short, only where the why isn't obvious.
- Settings come from `config.properties` through the generated `out/gen/io/freemytv/Config.java` (never edit that).
  What the person changes on the TV (`Prefs`: Home Settings, Shelf, Remote Buttons) wins over those first values.
- The remote's app buttons (`RemoteKeys`, `RemoteKeyService`) use each brand's key codes: they run only on TCL,
  Vestel, KTC (NextStar) and Sony TVs and Formovie projectors, the service is on only in Free My TV mode, and *Original* takes it out of the enabled accessibility
  services. The system waits for the service on every key, so it lives alone in the `:keys` process: what it reads is
  in `remote-keys.txt` (`Prefs.keyApp`), never in SharedPreferences, and `onKeyEvent` does no heavy work.
  Vestel (F&U and other store brands; MediaTek, Android TV 14 tested) maps its Netflix button to `KEYCODE_STEM_1`, a
  global key (`dumpsys window policy` lists them) that the key filter sees first; its YouTube, Prime Video and Google
  Play buttons stay with the system. The stock lists needed no Vestel entries.
  KTC (NextStar; MediaTek, Google TV 14 tested) maps its Tabii button to `KEYCODE_BUTTON_8`, also a global key, which
  KTC's own hot-key receiver would open Tabii with; its Netflix, YouTube and Prime Video buttons stay with the system.
  Sony (BRAVIA VH22, Android TV 12 tested) sends `KEYCODE_BUTTON_9` for its BRAVIA CORE, Disney+, Prime Video,
  YouTube and Crunchyroll buttons, `KEYCODE_BUTTON_4` for Netflix and `KEYCODE_BUTTON_8` for the Google Play button of
  older remotes (BRAVIA 4K UR3, Android 10 tested), global keys for `com.sony.dtv.sonyvendorservice`;
  only the scan code tells them apart, so `Prefs` keys them as `SONY_KEY` + scan code. "Sony default" (empty) leaves
  the key to the TV. Sony ships its own Apple TV build, `com.apple.atve.sony.appletv`.
  Xiaomi projectors made by Formovie (Fengmi; L1, MediaTek, Google TV 14 tested) send the same `KEYCODE_STEM_1` for
  Netflix (`RemoteKeys.STEM_NETFLIX`); their YouTube button goes to MediaTek's `GlobalKeyReceiver` and stays with the system.
- Settings → Extras (`SettingsActivity` EXTRAS list): Volume limit (`VolumeLimit`, main process: VOLUME_CHANGED
  receiver + a minute tick, out of 100 scaled to the stream max; "At night" = 22:00–7:00; VolumeHold and the phone
  remote stop at it), Break reminder (`ScreenTime`, main process, minutes with the screen on, reset when it goes off,
  card via AlertOverlay), Festival themes and Birthdays (`Celebrate`). Prefs for these use MODE_MULTI_PROCESS getters.
- Vision Pro glass (cinematic home only, `HomeView`): the focused shelf tile is drawn by `drawTile3d` (lifted 6 px,
  `applyDepth` = android.graphics.Camera rotateX/Y around its centre, location z -16; lean = `tilt` spring kicked by
  ← → in `move` (TILT_KICK) + a slow sway, so the tiles layer redraws at ~30 fps while the shelf has focus), then
  `drawGlassLight` (rim-light stroke + skewed glint following the tilt). The focused Continue watching card leans with
  the same spring (picture only, not its title; no idle sway: the hero layer has glass chips, too costly to redraw
  continuously). The shelf's glass gets the rim light too. Parallax: `parallax` spring follows `focusX()`; the photo
  shifts PARALLAX_PX (zoom base raised to 1.075 so no edge shows) and the hero layer CLOCK_PARALLAX_PX the other way
  (view translationX, no redraw).
- Depth of field: `Backdrop.soft` (a photo's 480x270 lightly blurred copy, made in `forPhoto` before `glass` is
  darkened) is drawn over the photo, inside the drift transform, with alpha = `depthBlur` spring (1 while Z_ROW has
  focus). The same `soft` is the frosted room behind the glass All Apps sheet (`drawSheet`: each app on a pane with
  `rimLight`; the focused pane lifts, leans by `tilt` (← →) and `tiltV` (↑ ↓) via `applyDepth`, glint) and behind the
  settings screens (`GlassDrawable.room`). `GlassDrawable`: smoked (pop-ups: AlertOverlay, Briefing) / clear (QR
  screens' window, SettingsActivity's menu window) glass with a lit top edge; focused rows and pills are bright glass.
- Cricket row (cinematic, `Z_CRICKET`): `Cricket.matches` comes from the same currentMatches lookup (no extra calls;
  the raw list cached as "data"), sorted live → upcoming (≤3 days) → ended (≤~22 h), your teams first (`isYours`: also
  "India Women", "India A"…), max 12; flags from teamInfo.img cached in files/flags. Drawn under Continue watching (or
  in its place) by `drawCricketRow`; the hero layer is 440 px taller than the screen and `pageScroll` moves the home
  layers up (updateMotion) while it has focus; `cricketShift` slides the row. OK opens JioHotstar. The old top-left
  live chip is gone: `drawNextMatch` puts your team's live score / next match under the top buttons.
- Top buttons: `HomeApps.TOP_ALL` (control, recent, live, inputs, wifi, settings), default control/inputs/wifi/settings;
  saved choices get control + wifi once (`Prefs.topButtonsV2`). "tune"/"wifi" icons are drawn by `drawButtonIcon`.
- Control Center (`ControlCenter`, its own HomeView layer, keys go to it first while open): sleep timer
  (`Prefs.sleepAt`, ended by ScreenTime in the main process via `TvActions.sleepNow` = adb KEYCODE_SLEEP; "handled" kept
  in the main process's own prefs file, never written to "settings" from there), turn off, free memory (adb
  `am kill-all`), network (adb `cmd wifi status` for the SSID), speed test (Cloudflare 40 MB, ≤8 s), sound output
  (AudioManager devices), screensaver (SettingsActivity EXTRA_SCREENSAVER → DreamView, finish on close), appearance
  (Prefs.theme; the activity recreates when the panel closes).
- TV Cleanup (`Cleanup`, `CleanupActivity`; Settings → Extras → TV Cleanup): Clean Your TV's (Gathin23/cleanyourtv,
  MIT) Sony BRAVIA BF1 list, on the TV through its own ADB: `pm disable-user --user 0` / `pm enable`, never uninstall.
  Groups: ads/tracking (Samba TV, Sony promos/Select, Google feedback), shop demo (3 Sony packages), preloaded
  (Google TV/Play Movies, Sony Pictures Core unless the BRAVIA CORE button opens it), optional voice search (not
  recommended). launcherx/setupwraith/dreamx/YouTube Music/Play Games stay Nook mode's (HomeMode.DEBLOAT). What Cleanup
  turned off is in files/cleanup.json; "Turn everything back on" and HomeMode.applyOriginal enable those (then
  `Cleanup.forget`). com.sony.dtv.applicationdisabler re-enables the IoT packages at boot: not listed.
- Nook Phone 2.0 (phone/): launcher = `RemoteActivity` (WebView of the TV's /remote page, the `Nook` JS bridge for the
  phone clipboard, the phone's volume keys → `nookVolume`); `ShareActivity` (Share → Nook TV: links → /remote/open,
  text → /remote/text, images → /remote/photo); `MainActivity` is setup (pairing, notification access, muted apps).
  TV side (`PhoneRemote`): tabs Remote/Control/Cleanup/Days; GET/POST /remote/control (sleep via `Prefs.sleepAt`, now
  its own "sleep" prefs file so the main process can write it safely; free, speed, saver, off) and /remote/cleanup
  (latch-waits on Cleanup's async calls); /remote/photo saves like PhotoServer (date_phone<random>.ext, set param).
- Phone Home tab (`PhoneHome`, main process): GET /remote/home (greeting, weather, reminder, shelf, Continue watching,
  cricket from the "cricket" prefs' cached data via `Cricket.matchesFrom`, wallpaper name), /remote/wallpaper (1280 px
  JPEG, cached per photo), /remote/icon?p= (tile drawn like the TV's: `HomeView.tileStyle` + logo), /remote/art?i=;
  POST /remote/watch {i}. The page is glass throughout (backdrop-filter), with the wallpaper drifting behind and blurring
  on other tabs / when scrolled.
- `Celebrate`: greeting override in `HomeView.updateClock` (birthdays, then anniversaries, own festivals, then the
  built-in Diwali (day before + day, table to 2030), Sankranti 13–15 Jan, Christmas 24–25 Dec, New Year 31 Dec evening +
  1 Jan); `HomeView.drawFestive` draws snow/sparks/confetti/diamonds from time alone over the sky layer (kept animating
  via `festiveMoving`). Built-in festival photo sets are Photos.SETS sub-folders (diwali, sankranti, christmas,
  newyear): shown only on their day. The family's days: `files/days.json` (kind birthday/anniversary/festival/reminder,
  repeat yearly/monthly/once, y/m/d, min = reminder time), written by the main process (phone page), read everywhere;
  the old `birthdays.txt` is migrated once. Past one-time reminders are pruned on the next add/remove.
- `Reminders` (main process, ScreenTime's minute tick, screen on): a 20 s AlertOverlay card at/after each reminder's
  time, once a day (SharedPreferences "reminders"). Today's first reminder also shows under the date on the home screen.
- `Briefing` (:home, from KeepWarm's SCREEN_ON): first screen-on between 5:00 and 12:00, 3 s later, a 15 s overlay card:
  HomeData weather (refreshed if over an hour old), today (Google Calendar secret iCal URL in `files/calendar-url.txt`,
  set from the phone page; single events + DAILY/WEEKLY(BYDAY)/MONTHLY/YEARLY with INTERVAL/UNTIL/EXDATE; plus
  reminders and celebrations), and Google News India's first headline. Settings → Extras: on/off and "Show now".
- Phone remote (`PhoneRemote`, routed from NotifyServer on port 8766 with the pairing key; QR in `RemoteActivity`):
  keys/typing/opening apps go through the TV's own ADB (`Adb`, one kept connection, single-thread queue; whitelisted
  key names; `input text` gets printable ASCII only, single-quoted), volume through AudioManager. Also the Special days tab
  (days + the calendar link).
- Faster wake (`KeepWarm`): a service in :home bound from the main process (PlaybackListener, Free My TV mode only), so
  :home and its decoded wallpaper aren't dropped while the TV sleeps; SCREEN_ON starts that moment's photo decoding.
- `update.sh` (project root): build + install -r + re-grant READ_LOGS / SYSTEM_ALERT_WINDOW, opens nothing on the TV.
- Holding Volume (`VolumeHold`, started by `RemoteKeyService`): with the key filter on, the TV policy takes volume keys
  in KeyboardInterceptor, so they never repeat and never reach the service; the key coming up doesn't change the volume
  and VOLUME_CHANGED broadcasts arrive up to 0.5 s late, so neither tells a hold. Sony's key daemon logs every key
  (`logcat -s asd:I`, "KeyEvent { action=ACTION_DOWN, keyCode=KEYCODE_VOLUME_UP ..."): with READ_LOGS (`pm grant`, done
  in `History.ensureAccess`) VolumeHold follows it and steps the volume while the key is down (after 400 ms, every
  110 ms, 12 s cap, stops at the limits and on any other key). `/dev/input` can't be written from adb (no sendevent tests).
- The remote's gear (`KEYCODE_SETTINGS`, or `KEYCODE_NOTIFICATION` scan 479 on a BRAVIA BF1) is handled in `RemoteKeys.onKey` (opens the TV's settings): with the
  stock home screen off nothing else acts on it. Unknown keys are logged once (`remote key ...`) to find what a
  button sends. Never `am force-stop io.freemytv` while testing: it switches the key service off (it comes back after
  a minute or on the next update).
- After any change, `./build.sh` must end with `Built FreeMyTV.apk`.
- LG and Samsung (`web/`): plain ES5 JavaScript and CSS without variables, grid or backdrop-filter, since older TVs
  (webOS 3 and 4, Tizen 3) run a Chromium from 2015 to 2017; only transform and opacity animate. `web/tv.js` is the
  TV's side: on LG, Luna (`listLaunchPoints`, `launch`, the inputs; where webOS 6 and newer deny `listLaunchPoints`
  to Developer Mode apps, the list comes from the TV's remote control API on `ws://127.0.0.1:3000` once the TV has
  allowed it (it asks once; a "no" isn't asked again), and until then from `getAppLoadStatus` over a list of known
  app ids, which shows their names without icons); on Samsung, `tizen.application` (`getAppsInfo`,
  `launch`), the `VIDEOSOURCE` property and `tizen.tvwindow.setSource` for the inputs, then Samsung's viewer
  (`org.tizen.tv-viewer`) to show them. Samsung's own data is unreliable: on Tizen 3 no app's `iconPath` loads (on
  Tizen 4 and 5, YouTube's and Prime Video's are a placeholder), so logos are bundled in `web/icons/` and keyed by app
  id in `SAMSUNG_ICONS` (SVGs need a width and height for old WebKit); some names are empty or long
  (`SAMSUNG_TITLES`); and Tizen 3 answers no "settings" app control, so TV Settings opens `org.tizen.menu`.
  `web/home.js` follows `HomeView`'s layout and behavior, so a design change on
  Android belongs there too. Text: the same catalogues as Android, packed into `i18n.js` by `tools/webapp.py` (only
  the keys `web/` uses): `tr('Live TV')`, `trf('{n} min ago', {n: 12})`, `plural('plural|…', n)` and
  `formatDate('format|h:mm a', at)` (the catalogue's SimpleDateFormat pattern, written by `web/strings.js`), and
  `tools/translate.py` reads those calls too. Built without a city (`--generic`), `web/zones.js`
  (`tools/make_zones.py`, from the tz database) turns the TV's time zone into the city, the currency and the region.
  `tools/webapp.py` is what both builds share. Preview: `python3 webos/build.py --preview` (or `tizen/build.py`), then
  open `out/webos/index.html` in a browser (a stand-in lists sample apps).

## Always

- Google TV's setup app (`setupwraith`) goes off before the stock home screen and comes back after it, everywhere
  (`targets` order, `applyOriginal`, `revertHomeNow`, `verifyNow`): with the home screen off and it on, its
  RecoveryActivity takes Home, and a restart there factory-reset a Xiaomi. If it can't go off, the home screen stays on.
  The Kalley 43G200 (`R3_GTV`) turns it back on at every start and boot-looped without its home screen: the switch
  refuses there.
- Never turn off an app that saved a Wi-Fi network: Android deletes its networks and Original can't bring them
  back (`wifiOwners` in `HomeMode`). When the Wi-Fi service doesn't answer, the switch stops on Wi-Fi, and the boot
  check leaves the setup app and the stock home screen for later.
- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`, so
  *Original* can bring it back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.
