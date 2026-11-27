# BandMate

An Android companion app for the **Xiaomi Mi Band 4** — a private, cloud-free replacement for Zepp Life.

## Features
- Connects with the band's auth key (no Xiaomi/Zepp cloud needed after setup)
- Live steps, distance, calories, battery and heart rate
- Step, sleep and heart-rate **history** synced minute-by-minute from the band, stored only on the phone
- Phone steps from **Health Connect** (Google Fit, OnePlus Health), merged with band steps without double counting
- Step summaries on the band: on demand, hourly and every evening
- App notifications with per-app on/off, incoming calls with caller name and reject-from-band
- Alarms, weather (Open-Meteo), and band settings: clock, date, lift-to-wake, night mode, screen order, step goal, idle alert, all-day heart rate, Do Not Disturb
- Find my band

## Getting the auth key
The band only talks to apps that know its pairing key. Pair it once in Zepp Life, then:
- **Email login:** use [huami-token](https://codeberg.org/argrento/huami-token)
- **Google login:** sign in at watchface.zepp.com and run [`tools/get-key.js`](tools/get-key.js) in the browser console

Don't unpair the band in Zepp Life afterwards — that changes the key. Close Zepp Life before connecting BandMate.

## Build
Requires JDK 17 and the Android SDK (compileSdk 35).

```bash
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Notes
The Mi Band 4 protocol details were learned from the open-source [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) project. A watch's own step counter can't be set from a phone; BandMate shows the combined total as a message instead.
