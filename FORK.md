# MangaBox SD (personal fork)

Changes on top of upstream MangaBox, branch `sdcard-downloads`.

## Download location

A hard drive button in the top bar (Android only) chooses where offline books are saved:

| Option | Where | Notes |
| --- | --- | --- |
| Internal storage | app folder in internal storage | the upstream behaviour |
| SD card | `/storage/<card id>/Android/data/<app id>/files/offline-books` | no permission needed; Android deletes it when the app is uninstalled |
| Auto | SD card when present, otherwise internal | default |
| Choose a folder… | any folder picked in the system folder picker | survives uninstalling the app |

Every book remembers where it was saved, so changing the setting only affects new downloads.
When the card or folder is missing the app says so, keeps the library and refuses to delete.

No storage permission is used. The native part is the local plugin `capacitor-plugin-sdstorage`.

## Text EPUBs

Komga gives page images only for image based EPUBs. Text EPUBs used to open as a black page;
they now open in an EPUB reader (`script-epubreader.js`, epub.js in `lib/`), can be downloaded
for offline reading, and sync the reading position with Komga.

Reader controls: tap the left or right side (or swipe, arrow keys, volume buttons) to turn pages,
tap the middle for the toolbar (contents, font size, light / sepia / dark).

## Building

GitHub Actions (`.github/workflows/android-apk.yml`) builds every push to `sdcard-downloads` and
publishes the APK as a release. The app id is `com.zpaolo11x.mangabox.sd`, so it installs next to
the official app. Builds are signed with the keystore in the `ANDROID_DEBUG_KEYSTORE_B64` secret;
keep it, a different key means uninstalling before the next update.

Local build: `./capacitor-build-android.sh` (Node 22, JDK 21, Android SDK).

## Updating from upstream

```bash
git fetch upstream main
git rebase upstream/main
```

The upstream remote is `https://github.com/zpaolo11x/mangabox`.
