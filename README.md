# Drip

Rain alerts for drying clothes in Bengaluru. Two companions that share the same rules:

- `android/` Kotlin + Jetpack Compose app. Polls every 15 min in the background, sends a rain alert when clothes are out.
- `mac/` Swift menu bar app. Polls every 5 min while running, shows minutes-to-rain in the menu bar, native notifications.

Both use Open-Meteo (free, no key) for the forecast and, once you add a key, Weather Union stations for rain-on-the-ground upwind. Home defaults to Koramangala; change it in Settings in either app.

## Mac: run it

Needs Xcode command line tools (`xcode-select --install` if `swift` isn't found).

```
cd ~/Desktop/dryline/mac
chmod +x run.sh
./run.sh
```

A cloud or shirt icon appears in the menu bar. Click it for the panel. Leave the Terminal window open, or run it detached:

```
nohup .build/release/Drip >/dev/null 2>&1 &
```

To start it at login, add `~/Desktop/dryline/mac/.build/release/Drip` under System Settings, General, Login Items.

The first notification may need you to allow "Script Editor" notifications once in System Settings, Notifications.

## Android: build the APK

Option A, on this Mac with Android Studio installed:

```
cd ~/Desktop/dryline/android
./gradlew assembleDebug
```

APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`. AirDrop or `adb install` it. If Gradle can't find the SDK, create `android/local.properties` with `sdk.dir=/Users/<you>/Library/Android/sdk`.

Option B, GitHub builds it for you:

1. Create an empty GitHub repo, then from `~/Desktop/dryline`: `git init && git add . && git commit -m "Drip" && git remote add origin <repo url> && git push -u origin main`
2. Open the repo's Actions tab, wait about 5 min, download `drip-debug-apk`.

On the phone: install the APK, allow notifications, then Settings in the app and open the battery page. On Xiaomi, Vivo, Oppo, Realme set "No restrictions" and allow autostart, or background alerts stop after a day.

## Weather Union

Free key at weatherunion.com. Paste the key in Settings, plus the locality IDs from their station list, upwind first (south-west of you during the monsoon). With no key the apps run on the forecast alone.

## Rules (same in both apps)

- Rain alert when any of: an upwind station reports rain; forecast probability within 45 min is at or above the threshold set by the sensitivity slider (30% early to 85% only-when-sure); rain intensity at home crosses the drying-spot floor (open 0.2, partly covered 1.0, covered 4.0 mm/h). One alert per 40 min. Only when clothes are out, unless you turn that off.
- Morning summary once between 7 and 9 am.
- Dusk reminder once after sunset if clothes are still out.
