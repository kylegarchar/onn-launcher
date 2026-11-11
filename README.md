# onn-launcher

**Lumen** is a calm, tvOS-inspired home screen for the onn 4K Pro (2nd gen) and other
Google TV / Android TV boxes. It is an original design: no ads, no recommendations rows,
just your apps.

- A dock of up to five favourites on a glass shelf, with every other app in a five-wide
  grid below.
- The backdrop takes on the colours of whichever app is selected.
- Focus motion: spring scale, soft shadow, a parallax nudge in the direction you moved,
  and a sweep of light across the card.
- Sharp artwork: each app's own TV banner, read at the highest resolution it ships.
  Apps without a banner get a card generated from their icon.
- Hold **OK** on any app to move it, add it to the dock, hide it, open App Info or uninstall.

## Install

You need a computer with [adb](https://developer.android.com/tools/releases/platform-tools)
on the same network as the box.

1. On the box: **Settings > System > About**, click **Android TV OS build** seven times,
   then turn on **USB debugging** under **Settings > System > Developer options**.
2. Connect (the box shows a prompt the first time; choose Allow):

   ```sh
   adb connect <BOX_IP>:5555
   ```

3. Install and set as Home:

   ```sh
   ./scripts/install.sh
   ```

   Or by hand:

   ```sh
   curl -fLO https://github.com/kylegarchar/onn-launcher/releases/download/latest/onn-launcher.apk
   adb install -r onn-launcher.apk
   adb shell cmd package set-home-activity dev.lumen.launcher/.MainActivity
   ```

4. Press **Home**.

### If Home still opens Google TV

Google's home screen can override the default. Once you have opened Lumen and are happy
it works, switch the stock one off:

```sh
./scripts/force-default.sh
```

That runs `pm disable-user` on `com.google.android.apps.tv.launcherx` and
`com.google.android.tungsten.setupwraith`. Nothing is uninstalled, but features that live
inside the Google TV home screen are unavailable until you restore it.

### Going back

```sh
./scripts/restore.sh          # stock home screen back, Lumen removed
./scripts/restore.sh --keep   # stock home screen back, Lumen left installed
```

By hand, if you ever need it:

```sh
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
```

## Remote

| Button | Does |
| --- | --- |
| D-pad | Move between apps. Hold to glide. |
| OK | Open the app |
| Hold OK (or Menu) | Options: Open, Move, Add to / Remove from Dock, Hide, App Info, Uninstall |
| Back | Close a menu, finish a move, or jump back to the first app |
| Home (on the home screen) | Jump back to the first app |

Hidden apps come back from the **Settings** card: hold OK on it and choose
**Show Hidden Apps**.

## Custom icons

Any card can be replaced with your own artwork. Use a 16:9 PNG, ideally 640x360 or larger,
named after the app's package:

```sh
adb push netflix.png /sdcard/Android/data/dev.lumen.launcher/files/icons/com.netflix.ninja.png
adb shell am force-stop dev.lumen.launcher   # then press Home to reload
```

`settings.png` replaces the Settings card. Find package names with
`adb shell pm list packages -3`.

## Building

Every push builds a signed release APK with GitHub Actions and publishes it to the rolling
[`latest` release](https://github.com/kylegarchar/onn-launcher/releases/tag/latest).
To build locally you need JDK 17 and the Android SDK:

```sh
gradle :app:assembleRelease
```

The APK is signed with `app/sideload.jks`, a key kept in this repository so that each new
build installs over the last one. It is not a secret and proves nothing about who built an
APK, so only install builds from this repository's own releases.

## How it stays fast

- Animations only touch GPU layer properties (translate, scale, rotate, alpha) or redraw a
  single card. Nothing is re-measured while you navigate.
- No live blur. The backdrop is a 48x27 pre-blurred bitmap stretched by the GPU; the focus
  shadow is one shared pre-rendered bitmap.
- Cards are rendered once, at the exact pixel size of a focused card, and cached on disk.
- Release builds are minified with R8 and ship Compose's baseline profiles.

## Layout of the code

| File | What it holds |
| --- | --- |
| `MainActivity.kt` | Remote key routing, launching apps, package change listener |
| `LauncherState.kt` | Selection, navigation, options menu, rearranging, saved layout |
| `HomeScreen.kt` | Everything drawn on screen and all the motion |
| `IconLoader.kt` | Banner decoding, generated cards, disk cache, backdrop bitmaps |
| `Apps.kt` | Finding installed apps; saved dock and grid order |
| `Metrics.kt` | Layout geometry |
