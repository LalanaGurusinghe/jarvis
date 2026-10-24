# Jarvis

A personal voice assistant for a tablet, built as an installable web app (PWA). It scans the tablet it runs on and tunes itself to the hardware: animation quality, voice, microphone mode and power saving.

## What it does

- **Talk or type.** Tap the core to speak, or turn on the wake word and say "Jarvis, …".
- **Answers out loud** with a Groq neural male voice (Daniel, Troy or Austin), or the tablet's own voice as a free offline backup.
- **Timers** with sound, vibration and background notifications.
- **Tasks and memory** kept on the tablet.
- **Live weather** for your location or a set city (Open-Meteo, no key needed).
- **Diagnostics**: device model, OS, battery, network, processor, memory and feature support.
- **Briefing** button: weather, tasks and timers in a few spoken sentences.
- Works offline for time, timers, tasks and diagnostics.

## Brains

| Option | Key | Cost |
|---|---|---|
| Groq (default) | [Groq console](https://console.groq.com/keys) | Free tier; also powers the neural voice |
| Google Gemini | [AI Studio](https://aistudio.google.com/apikey) | Free tier |
| Claude | [Claude Console](https://console.anthropic.com/) | Pay per use |
| Offline | none | Free, commands only |

Set a key in Settings, or open the app once with `#groq=YOUR_KEY` after the address. Keys are stored only in the tablet's browser storage and are sent only to their own provider.

## Install on a tablet

1. Open the hosted address in **Chrome** or **Samsung Internet** (Android) or **Safari** (iPad).
2. Android: tap **Install** in the header or browser menu → *Add to Home screen*. iPad: **Share → Add to Home Screen**.
3. Open it from the home screen, tap **Engage**, then **Settings** to add a key.
4. In **Systems**, allow microphone, notifications and location.

## Limits

- A web app can't open other apps or change device settings. For that on Android, pair it with Tasker + AutoVoice.
- Timers keep running while the app is open or in the background, but not after it is closed.
- The wake word listens only while the app is open; Android may beep when the mic restarts.

## Files

`index.html` (the whole app) · `manifest.webmanifest` (install info) · `sw.js` (offline cache, notifications) · `icons/`
