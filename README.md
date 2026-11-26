# Kadar

**A free film camera for Android.** Pick a film, shoot, and every photo comes out looking like it was taken on that stock: color, grain, the red glow around night lights, all of it.

No account, no ads, no subscription, no server. Your photos never leave your phone unless you send them somewhere yourself.

> *Kadar* (Serbian) — a single frame on a roll of film.

**[⬇ Download Kadar.apk](https://github.com/dzejmi93/film-kamera/releases/download/latest/Kadar.apk)** · Android 7+ (Android 11+ recommended) · ~350 KB

---

## Films

| Film | ISO | Look |
|---|---|---|
| **CineStill 800T** | 800 | Night. Deep blacks, teal shadows, warm signs, strong red halation around lights. |
| **Portra 800** | 800 | Weddings. Warm skin and wood, clean whites, soft contrast. |
| **Portra 400** | 400 | Daylight. Bright, airy, calm colors with a slightly cool, clean feel. |
| **Ektar 100** | 100 | Landscapes. Rich but natural color, deep blue skies, almost no grain. |
| **HP5 Plus 400** | 400 | Black & white. Punchy midtones, slightly lifted blacks, visible grain. |
| **Gold 200** | 200 | Golden. Warm yellow-green, creamy highlights, teal skies. |

Each look was tuned against real scans of that film. The camera also shoots at the film's ISO: Ektar 100 gets darker in low light, 800 films stay brighter and grainier — like the real thing.

You can **change the film after shooting**: the original is kept, so any photo in your roll can be re-developed with another film in one tap.

## Features

- **Real camera, not a filter on top.** Uses the Android camera directly (Camera2), with the film look drawn live in the viewfinder on the GPU.
- **1×, 2× and 3×.** On phones that allow it (tested on a Galaxy S23), 3× uses the telephoto lens.
- **Full-resolution photos**, sharpened after developing so they don't look soft.
- **Tap to focus** with a retro focus frame that pulses when focus locks.
- **Film details:** per-color tuning (like a 3D LUT), organic color grain, halation around bright lights.
- **3:4 or 16:9**, **composition grid** (rule of thirds or golden ratio), **3 s / 10 s timer**, **flash** (screen flash for selfies), orange **date stamp**, **film border**.
- **Location (optional):** saved inside the photo (EXIF), so your gallery and Google Photos show it on the map. Off by default.
- **Volume buttons** take the photo, with a shutter and film-advance sound.
- **Roll (album)** grouped by day, like an event album. Select several photos to save, send or delete.
- **Save to gallery** (`Pictures/Kadar`) or **send to Google Photos / Google Drive** straight from the phone to your own account.
- **Upload** photos from your gallery and develop them with any film.

## Install

1. On your phone, open **[Kadar.apk](https://github.com/dzejmi93/film-kamera/releases/download/latest/Kadar.apk)** and download it.
2. Open the file. If Android asks, allow installing apps from this source (your browser or file manager).
3. If **Play Protect** warns about an unknown app, tap **More details → Install anyway**. This is normal for apps that aren't on the Play Store.
4. Open Kadar and allow the camera.

**Updating:** download the APK again from the same link and install it over the old one. Your roll stays.

## Privacy

Kadar has no server and no analytics. It only uses the internet to load its fonts.

| Permission | Why |
|---|---|
| Camera | To take photos. |
| Location | Only if you turn **Location** on, to write it into the photo. Never sent anywhere. |
| Storage (Android 9 and older) | To save photos to your gallery. |

Photos in your roll are stored inside the app. **Uninstalling Kadar deletes the roll**, so tap *Save* first. Photos you already saved to your gallery, Google Photos or Drive stay where they are.

## FAQ

**Why isn't it on Google Play?** It's a free hobby project. Publishing on Play needs a paid developer account and two weeks of closed testing, so for now it lives here.

**Does it work on my phone?** It should work on Android 7 and newer. On Android 11+ it uses the real camera; on older phones it falls back to a simpler camera. 3× through the telephoto lens depends on whether your phone lets apps use that lens.

**The photo looks dark.** You probably picked a low-ISO film (Ektar 100) in dim light. Try Portra 800 or CineStill 800T, like you would with real film.

**Found a bug or have an idea?** Open an [issue](https://github.com/dzejmi93/film-kamera/issues). Tell me your phone model and what happened.

## Build it yourself

Every push to `main` builds a signed APK with GitHub Actions and publishes it to [Releases](https://github.com/dzejmi93/film-kamera/releases/tag/latest). To build locally: `gradle assembleRelease` (Java 17, Android SDK 34).

The app is a small native Android shell (camera, saving, sharing) around one web page (`app/src/main/assets/index.html`) that holds the UI and the film engine.

---

*Film names describe the look each preset is modeled on. Kadar is not affiliated with or endorsed by Kodak, Ilford/Harman or CineStill; all trademarks belong to their owners.*

*Made in Serbia 🇷🇸 · Kamera sa analognim filmovima, besplatna i bez servera — slike ostaju na tvom telefonu.*
