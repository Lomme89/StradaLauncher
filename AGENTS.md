# CarLauncher — AGENTS.md

Launcher Android per autoradio. UI in HTML/JS dentro WebView.
Stack: Java (Android shell) + HTML/CSS/JS single-file (`launcher.html`, ~1400 righe).

## Struttura progetto

```
app/src/main/
  assets/launcher.html          ← TUTTA la UI (HTML + CSS + JS)
  java/com/stradalauncher/
    MainActivity.java           ← WebView host, bridge Android↔JS, GPS, media polling
    MediaListenerService.java   ← NotificationListenerService per media session
  res/layout/activity_main.xml  ← Solo <WebView> fullscreen
```

## Layout CSS Grid

```
.shell  →  9vw | 1fr
  .sidebar (9vw)  — sb-apps-btn (drawer), sb-mode-btn (clock), sb-theme-btn (tema manuale)
  .main    (1fr)  — 10vh | 1fr | 14vh
    .topbar    — orologio Orbitron, data, GPS pill, edit-btn, settings-gear-btn
    .apps-area — griglia 3×2 app-card  |  .clock-view fullscreen canvas
    .bottombar — album art, media ctrl, volume slider, speed (km/h)
```

## Variabili CSS chiave

| Var | Default | Note |
|-----|---------|------|
| `--ts` | 1.0 | text scale (slider 0.7–2.0) |
| `--is` | 1.0 | icon scale (slider 0.6–2.0) |
| `--bg/--surface/--surface2/--border/--text/--muted` | palette | override da `applyColorPalette()` |
| `--c1..--c6` | accent | colori per i 6 slot app |
| `--font-body/--font-mono` | DM Sans/DM Mono | override da `applyFontVars()` |

## Sezioni JS (ordine nel file)

| Blocco | Funzioni chiave |
|--------|----------------|
| Costanti | `THEMES` (8×2), `FONTS` (4), `ICONS` (outline/bold/filled), `SETTINGS_DEFAULTS` |
| Config | `loadConfig/saveConfig` → `SharedPreferences button_config` |
| Grid | `renderGrid`, `handleCardClick`, `launchPkg`, `openLaunch/closeLaunch` |
| Edit | `toggleEditMode`, `openPicker`, `assignApp`, `clearSlot` |
| Clock | `_DAYS/_MONTHS` (costanti), `updateClock` (cached `_clockEl/_dateEl`, ogni 10s) |
| Settings | `loadSettings`, `saveSettingsToDevice`, `applyAllSettings`, `applyColorPalette`, `applyFontVars`, `applyBrightness` |
| Theme setters | `setColorTheme`, `setTheme` (dark/light), `setThemeMode` (manual/system/sundial) |
| Theme logic | `resolveIsDark`, `setupThemeMode`, `_systemThemeHandler`, `toggleManualTheme`, `updateSidebarThemeBtn` |
| Sundial | `_getLocationAndSetupSundial`, `_calcSunTimes` (NOAA), `_applySundialTheme` |
| Brightness | `setBrightnessMode`, `onBrightnessDark/Light` |
| Drawer | `openDrawer`, `renderDrawer`, `filterDrawer` (debounce 150ms) |
| Settings modal | `openSettingsModal` (sync tutti i controlli), `closeSettingsModal` (save) |
| Clock view | `toggleClockMode`, `_cwFindFontSize`, `_cwDraw`, `_cwTick` (canvas Orbitron) |
| GPS/Media | `window.onGpsSpeed/onMediaUpdate/onPlaybackState/onAlbumArt` (callbacks Java) |

## Impostazioni persistite (`app_settings`)

```json
{
  "theme": "dark|light",
  "themeMode": "manual|system|sundial",
  "colorTheme": "default|nord|catppuccin|solarized|paper|tokyonight|dracula|gruvbox",
  "fontStyle": "modern|system|mono|serif",
  "iconStyle": "outline|bold|filled",
  "textScale": 1.0, "iconScale": 1.0,
  "brightnessDark": 0.5, "brightnessLight": 0.9,
  "brightnessMode": "hardware|software"
}
```

**themeMode:**
- `manual` — sidebar sun/moon button togola dark↔light; visibile solo in questo modo
- `system` — segue `prefers-color-scheme` OS via `matchMedia`
- `sundial` — `navigator.geolocation` → formula NOAA → auto dark/light; fallback fisso 07:00/20:00

## Modali

| ID | Trigger | Contenuto |
|----|---------|-----------|
| `#picker-overlay` | badge ✎ in edit mode | Assegna app a slot |
| `#launch-overlay` | card con più app | Scelta app da aprire |
| `#drawer-overlay` | sb-apps-btn sidebar | Tutte le app, ricerca (debounce) |
| `#settings-overlay` | gear topbar | Tema, font, icone, scala, luminosità |

## Bridge Java → JS

| JS | Java |
|----|------|
| `Android.getInstalledApps()` | PackageManager → JSON array (base64 icon) |
| `Android.launchApp(pkg)` | getLaunchIntentForPackage |
| `Android.saveSettings/loadSettings` | SharedPreferences `app_settings` |
| `Android.saveButtonConfig/loadButtonConfig` | SharedPreferences `button_config` |
| `Android.setScreenBrightness(float)` | WindowManager.LayoutParams |
| `Android.openSettings(type)` | ACTION_*_SETTINGS |
| `Android.mediaAction(action)` | MediaSessionManager TransportControls |
| `Android.getVolumeInfo/setVolume` | AudioManager.STREAM_MUSIC |
| `window.onGpsSpeed(kmh)` | LocationListener ogni 1s |
| `window.onMediaUpdate/onPlaybackState/onAlbumArt` | pollMediaSession ogni 2s |

## Note performance (autoradio low-end)

- **No `backdrop-filter`** — rimosso da tutti gli overlay (troppo costoso)
- Overlay usa `background: rgba(0,0,0,0.86)` invece di blur
- `will-change: opacity/transform` su `.overlay` e `.drawer-overlay`
- DOM refs orologio cached (`_clockEl/_dateEl`) — no `getElementById` ogni 10s
- `filterDrawer` con debounce 150ms — no rebuild griglia ad ogni keystroke
- Transizioni body: 0.15s (era 0.25s)

## Comandi

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
# Assets richiedono rebuild — non si possono pushare direttamente
```
