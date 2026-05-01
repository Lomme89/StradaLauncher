# CarLauncher — Launcher per Headunit Android

Launcher web-based per autoradio Android, ottimizzato per schermi 1280×720.
Wrappa un HTML/CSS/JS in una WebView nativa con bridge Java↔JavaScript.

---

## Struttura del progetto

```
CarLauncher/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml           ← taggato HOME launcher
│       ├── assets/
│       │   └── launcher.html             ← tutta la UI (modifica qui)
│       ├── java/com/carlauncher/
│       │   ├── MainActivity.java         ← WebView + bridge GPS/Media/Apps
│       │   └── MediaListenerService.java ← accesso MediaSession
│       └── res/
│           ├── layout/activity_main.xml
│           └── values/{strings,themes}.xml
├── build.gradle
└── settings.gradle
```

---

## Come aprire in Android Studio

1. Apri Android Studio → **Open** → seleziona la cartella `CarLauncher/`
2. Attendi il sync Gradle (scarica le dipendenze automaticamente)
3. Collega la headunit via USB (abilita Modalità Sviluppatore + Debug USB)
4. Premi **Run ▶** — l'app viene installata

---

## Primo avvio sulla headunit

### 1. Imposta come launcher predefinito
Premi il tasto Home fisico → comparirà "Seleziona launcher" → scegli **Car Launcher** → "Sempre"

### 2. Abilita accesso notifiche (per i metadati musicali)
```
Impostazioni Android → App → Accesso speciale → Accesso alle notifiche → Car Launcher ✓
```
Senza questo step la velocità GPS e i pulsanti funzionano, ma il "In riproduzione" non mostra titolo/artista.

### 3. Permesso posizione (GPS velocità)
Al primo avvio apparirà un popup → concedi **"Sempre"** per la velocità GPS.

---

## Personalizzazione pulsanti

Nell'interfaccia: tocca **✎ Personalizza** in basso a destra.
Sui pulsanti appare il badge **✎** — toccalo per:
- Assegnare qualsiasi app installata (con icona)
- Svuotare il pulsante

La configurazione viene salvata in `SharedPreferences` e persiste ai riavvii.

---

## Modifica UI

Tutto il layout, i colori e le animazioni sono in:
```
app/src/main/assets/launcher.html
```
Puoi modificarlo con qualsiasi editor di testo e ricompilare.

---

## Bridge Java ↔ JavaScript

Oggetto `Android` disponibile in JS:

| Metodo | Descrizione |
|--------|-------------|
| `Android.getInstalledApps()` | JSON array app installate (label, packageName, icon base64) |
| `Android.launchApp(pkg)` | Lancia app per packageName |
| `Android.openSettings(type)` | Apre impostazioni (`""`, `"display"`, `"sound"`, `"wifi"`, `"bt"`) |
| `Android.saveButtonConfig(json)` | Salva config pulsanti in SharedPreferences |
| `Android.loadButtonConfig()` | Legge config salvata |

Callback Java → JS (chiamate da MainActivity):

| Funzione globale JS | Quando viene chiamata |
|--------------------|-----------------------|
| `window.onGpsSpeed(kmh)` | Ogni aggiornamento GPS (ogni ~1s in movimento) |
| `window.onMediaUpdate(track)` | Cambio traccia o pausa/play |

---

## Requisiti

- Android Studio Hedgehog (2023.1) o successivo
- Android SDK 26+ (Android 8.0) sulla headunit
- JDK 8+
