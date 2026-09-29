# Publishing to Google Play

ServerBox is published on Google Play as a free app, package `io.github.madeye.serverbox`, with
[fastlane](https://docs.fastlane.tools/) (the same setup as meow-go). The Play build is the
`playRelease` build type: it leaves out the permissions Play restricts (installing the VM companion
apps, the microphone) and hides the features that need them.

## One-time setup

1. **Service-account key.** Put the Play Console service-account JSON key at
   `fastlane/play-store-key.json` (gitignored). Check it with
   `fastlane run validate_play_store_json_key json_key:fastlane/play-store-key.json`.
2. **Signing.** `local.properties` needs the release keystore (see the README). Play App Signing
   uses this key as the upload key.
3. **Create the app in Play Console** (the API can't): *Create app* → name **ServerBox**, default
   language **English (United States) – en-US**, **App**, **Free**, accept the declarations.
4. **App content** (Policy → App content), which fastlane can't fill in:

   | Section | Answer |
   |---|---|
   | Privacy policy | `https://github.com/madeye/ServerBox/blob/master/PRIVACY.md` |
   | App access | All functionality is available without special access |
   | Ads | No, the app does not contain ads |
   | Content rating | Questionnaire, category **Utility, Productivity, Communication, or Other**; answer no to every content question |
   | Target audience | 18 and over (not designed for children) |
   | News app | No |
   | Data safety | Does not collect or share any user data |
   | Government app / Financial features / Health | No / none / none |
   | Foreground service | See below |

   **Foreground service (FOREGROUND_SERVICE_SPECIAL_USE).** *Other*, with this description:

   > ServerBox runs a Linux distribution on the phone as a server. Its foreground service keeps the
   > user's Linux session (a PRoot process tree, or a virtual machine through a companion app) and
   > the SSH server inside it running while the app is in the background, so the user can connect to
   > it over SSH. It is started when the user starts a session (or after a reboot, only if the user
   > turned on "Start on boot"), shows an ongoing notification, and stops when the user stops the
   > session. A second foreground service keeps the built-in SSH terminal's sessions alive while
   > the terminal screen is in the background.

   Play may also ask for a short video of the feature: start a distribution from the Apps tab, show
   the notification, lock the screen, then connect over SSH from another device.

   **Battery optimization (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).** The core function is a server
   that must keep accepting connections with the screen off. ServerBox asks once, the first time a
   session is running, and from Settings → Ignore battery optimization.

## Releasing

```sh
fastlane bundle      # ./gradlew bundleServerBoxPlayRelease, signed (needs JAVA_HOME set to JDK 17)
fastlane internal    # upload the bundle, listing and images to the internal track as a draft
```

Then review the draft release in Play Console and roll it out. While the app has never been
published, Play only accepts draft releases, which is why every lane uploads with
`release_status: "draft"`. `fastlane production` promotes the internal release to production, and
`fastlane metadata` updates only the store listing and images.

Each release needs a higher `versionCode` (`yyyyMMddNN` in the root `build.gradle`) and a changelog
per language at `fastlane/metadata/android/<lang>/changelogs/<versionCode>.txt`.

## Store listing

The listing lives in `fastlane/metadata/android/en-US` and `zh-CN`: title, short and full
descriptions, changelogs, the icon, the feature graphic (sources in `art/icon/feature-graphic*.svg`)
and phone screenshots. Screenshots must be 24-bit PNGs no more than twice as tall as they are wide;
the current ones were taken on an emulator with its screen set to 1080×2160 (`adb shell wm size
1080x2160`) and demo mode on, and the Chinese ones with the app's language set to Simplified Chinese
(`adb shell cmd locale set-app-locales io.github.madeye.serverbox --locales zh-CN`).
