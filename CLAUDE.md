# WhisperFlow — Projekt-Übergabe & vollständiger Status

## Was diese App ist (aktueller Stand)

WhisperFlow ist **keine** Notiz-App mehr. Das war die ursprüngliche Vision — die App hat sich
vollständig zu einem **Floating-Button-Diktierwerkzeug** entwickelt:

1. Floating Button tippen → Aufnahme startet
2. Nochmal tippen (oder Finger loslassen im Walkie-Talkie-Modus) → Aufnahme stoppt
3. OpenAI Whisper transkribiert die Aufnahme
4. Claude Haiku korrigiert Grammatik & Stil (je nach App/Profil)
5. Text wird direkt in das aktive Textfeld der Vordergrund-App eingefügt

Zielgruppe: 1–2 Personen (Privatnutzung), primär auf Deutsch.

---

## Repo & Branch-Regeln — KRITISCH

- **Repo:** `charmeundmelone-lab/WhisperFlow`
- **Immer auf `main` pushen** — niemals Feature-Branches erstellen
- CI baut automatisch bei jedem Push auf `main`
- APK landet auf Branch `apk-dist` (force-push durch CI): `app-debug.apk` + `app-debug-<sha>.apk`
- **Hinweis:** Remote-Branch `claude/android-project-setup-tqy5h9` existiert noch auf GitHub
  (lokaler Branch ist gelöscht). Er ist tot und kann manuell in den GitHub-Settings gelöscht werden.
  Er löst keinen CI-Trigger aus.

---

## Tech-Stack

| Komponente       | Detail                                          |
|------------------|-------------------------------------------------|
| Sprache          | Kotlin                                          |
| UI               | Jetpack Compose + Material3, Dark Theme         |
| minSdk           | 26 (Android 8.0)                                |
| compileSdk       | 35 (Android 15)                                 |
| Build            | Gradle 8.14.3, Kotlin DSL (`.kts`)             |
| Package          | `de.minitraxx.whisperflow`                      |
| HTTP             | OkHttp 4.x (kein Retrofit)                     |
| Overlay-Typ      | `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE` |
| Transkription    | OpenAI Whisper (`whisper-1`)                    |
| Korrektur        | Anthropic Claude (`claude-haiku-4-5-20251001`)  |
| Kein Room/KSP    | Persistenz nur via SharedPreferences + Keystore |

---

## APK-Signatur (Keystore) — WICHTIG

- Datei: `app/keystore/debug.jks`
- Passwort / Alias: `whisperflow`
- Bewusst ins Repo committed (Privatnutzung, kein Sicherheitsproblem)
- **Status: bestätigt funktionierend** — APK-Update-in-place getestet ✓
- **Zweck:** Alle CI-Builds tragen die gleiche Signatur → APK-Updates installieren direkt
  über die Vorgängerversion, ohne zu deinstallieren → API-Keys bleiben in SharedPreferences erhalten
- Konfiguriert in `app/build.gradle.kts` unter `signingConfigs { create("persistent") { ... } }`
- Gilt nur für `debug` buildType

---

## Dateistruktur (relevant)

```
app/src/main/java/de/minitraxx/whisperflow/
├── MainActivity.kt                    # Setup-UI, API-Key-Eingabe, Stil-Profil-Auswahl
├── service/
│   ├── FloatingButtonService.kt       # Kernlogik: Overlay-Button, Aufnahme, Audio-Pipeline
│   └── WhisperAccessibilityService.kt # Text-Injection in fremde Apps
├── api/
│   ├── WhisperClient.kt               # OpenAI Whisper API
│   ├── ClaudeClient.kt                # Anthropic Claude API
│   └── StylePrompts.kt                # Drei Stil-Prompts (WhatsApp, Professionell, Formal)
├── util/
│   └── CostTracker.kt                 # Geschätzte API-Kostenerfassung
└── ui/theme/
    └── Theme.kt                       # Dark Theme
app/keystore/debug.jks                 # Persistente APK-Signatur
```

---

## Vollständig implementierte Features

| Feature | Details |
|---------|---------|
| **Floating Button** | `TYPE_APPLICATION_OVERLAY`, frei verschiebbar, überlebt App-Wechsel |
| **Edge-Tab** | Button an Bildschirmrand ziehen (äußere 25%) → schrumpft via `scaleX(0.5f)` zum Halbkreis-Tab. Antippen → federt mit `OvershootInterpolator(1.3f)` zurück. Pivot liegt am Rand-Pixel, kein negativer X-Wert (Android klemmt auf 0). |
| **Walkie-Talkie-Modus** | Langer Druck (500ms) → Aufnahme läuft solange Finger gehalten → Loslassen = Stop & Transkription |
| **Kurztippen** | Aufnahme an/aus (Toggle) |
| **Aufnahme** | `MediaRecorder`, M4A/AAC, 128kbps, 44,1kHz |
| **Status-Overlay** | Live-Timer während Aufnahme (rot), "Transkribiere...", "Korrigiere [Profil]..." |
| **Whisper-Transkription** | `multipart/form-data` an OpenAI, Modell `whisper-1` |
| **Claude-Stilkorrektur** | `claude-haiku-4-5-20251001`, max 1024 Tokens, system prompt je nach Profil |
| **Drei Stil-Profile** | WhatsApp (locker), Professionell (Business), Formal (Behörden/Briefe) |
| **Auto-App-Erkennung** | `capturedPackage` wird bei Aufnahme-START gespeichert (nicht bei Verarbeitung) → korrekte Profilerkennung auch wenn App gewechselt wird. WhatsApp → WhatsApp-Profil; Gmail/Outlook/etc. → Professionell; sonst → User-gewähltes Profil |
| **Text-Injection** | `ACTION_SET_TEXT` (primär, hängt Text an vorhandenen an); Fallback: Clipboard + `ACTION_PASTE` |
| **Persistente API-Keys** | `SharedPreferences`, Keys werden beim Speichern und Lesen mit `.trim()` bereinigt |
| **Budget-Tracking** | Geschätzte Kosten (Whisper: $0.006/min, Claude: Pauschale), konfigurierbares Limit, Reset-Button |
| **Budget-Guard** | Aufnahme wird blockiert wenn Limit überschritten |
| **Automatischer Service-Start** | Startet sich selbst bei `onResume()` wenn Overlay + Mikrofon erlaubt sind |
| **Persistentes Keystore** | Gleiche APK-Signatur für alle Builds → kein Deinstallieren bei Updates |

---

## Bekannte Limitierungen (nicht fixbar)

### Gmail-Text-Injection
Gmail Compose verwendet WebView. `ACTION_SET_TEXT` und `ACTION_PASTE` auf AccessibilityNodes
funktionieren dort nicht. Text landet in der **Zwischenablage** — der Nutzer muss manuell
lang drücken → Einfügen. Dies ist eine harte Android-Grenze, kein App-Bug.

### `GLOBAL_ACTION_PASTE` existiert nicht
Wurde versucht (Build #16) → Compile-Fehler. Diese Konstante gibt es in der Android API nicht.

---

## Offene Todos (priorisiert)

### 1. EncryptedSharedPreferences für API-Keys
Aktuell: plain `SharedPreferences`. Sicherer wäre Jetpack Security (`EncryptedSharedPreferences`).
Für Privatnutzung auf einem nicht-gerooteten Gerät akzeptabel, aber technische Schuld.
Dependency: `androidx.security:security-crypto:1.1.0-alpha06`

### 3. Remote-Branch aufräumen
`claude/android-project-setup-tqy5h9` auf GitHub (remote) manuell löschen:
GitHub → Repository → Branches → branch löschen. Hat keinen Einfluss auf Funktion.

---

## Architektur-Entscheidungen (Begründungen)

### Warum scaleX statt negativer X-Koordinate für Edge-Tab
Android's `WindowManager` klemmt Overlay-Fenster auf `x ≥ 0`. Negative X-Werte in
`LayoutParams.x` werden ignoriert — der Button bleibt bei x=0 stehen, ohne sichtbar
zur Seite zu gleiten. Deshalb: `scaleX(0.5f)` mit `pivotX` am Rand-Pixel erzeugt den
Halbkreis-Effekt zuverlässig auf allen Android-Versionen (Build #20, Commit `5f0af8f`).

### Warum capturedPackage statt activePackage bei processAudio
`WhisperAccessibilityService.activePackage` enthält zur Zeit der Verarbeitung (nach Whisper + Claude)
möglicherweise schon eine andere App (Nutzer hat gewechselt). Deshalb wird die Paket-Info
bei `startRecording()` in `capturedPackage` gespeichert und in `processAudio()` ausgelesen.

### Warum keine GLOBAL_ACTION_PASTE
Diese Konstante existiert nicht in der Android SDK. Versucht in Build #16, sofort rausgeworfen.

### Warum ACTION_SET_TEXT statt nur Clipboard
`ACTION_SET_TEXT` fügt Text direkt in das fokussierte Feld ein und hängt an vorhandenen
Text an (kein Überschreiben). Funktioniert in WhatsApp, vielen nativen Apps. Clipboard-Fallback
greift nur wenn `ACTION_SET_TEXT` false zurückgibt.

### Warum plain SharedPreferences (nicht EncryptedSharedPreferences)
Einfachheit. Für 1–2 Nutzer auf privaten Geräten ausreichend. Kann jederzeit migriert werden.

---

## Setup-Flow (aus Nutzersicht, MainActivity)

Die App zeigt 4 Setup-Schritte:
1. **Overlay-Berechtigung** — Button über anderen Apps anzeigen
2. **Mikrofon-Berechtigung** — Sprachaufnahme
3. **Floating Button starten** — startet `FloatingButtonService`
4. **Texteingabe aktivieren** — `WhisperAccessibilityService` in Android-Einstellungen aktivieren

Danach: API-Key-Eingabe (OpenAI Pflicht, Anthropic optional), Stil-Profil, Budget-Limit.

---

## CI / Build / Deploy

```
Push auf main
    → GitHub Actions (.github/workflows/android-build.yml)
    → JDK 17 (Temurin) + Gradle 8.14.3
    → gradle assembleDebug
    → APK signiert mit app/keystore/debug.jks (Passwort: whisperflow)
    → APK als Artifact hochgeladen (30 Tage)
    → APK force-gepusht auf Branch apk-dist:
        app-debug.apk          ← immer aktuellste Version
        app-debug-<sha>.apk    ← versioniert
```

APK-Download für Familienmitglieder (permanenter Link, immer neueste Version):
```
https://github.com/charmeundmelone-lab/WhisperFlow/releases/latest/download/app-debug.apk
```
Installiert direkt über Vorgänger-APK ohne Deinstallation (gleiche Keystore-Signatur).

---

## SharedPreferences Keys

Alle in `FloatingButtonService.companion object`:

| Konstante | Key | Inhalt |
|-----------|-----|--------|
| `PREFS_NAME` | `whisperflow_prefs` | SharedPreferences-Dateiname |
| `KEY_OPENAI_API_KEY` | `openai_api_key` | OpenAI API Key (`sk-...`) |
| `KEY_ANTHROPIC_API_KEY` | `anthropic_api_key` | Anthropic API Key (`sk-ant-...`) |
| `KEY_STYLE_PROFILE` | `style_profile` | `whatsapp` / `professional` / `formal` |

---

## API-Endpunkte

### OpenAI Whisper
- `POST https://api.openai.com/v1/audio/transcriptions`
- `multipart/form-data`: `file` (M4A), `model=whisper-1`
- Auth: `Authorization: Bearer <KEY>`

### Anthropic Claude
- `POST https://api.anthropic.com/v1/messages`
- Auth: `x-api-key: <KEY>` + `anthropic-version: 2023-06-01`
- Modell: `claude-haiku-4-5-20251001`, `max_tokens: 1024`
- System-Prompt aus `StylePrompts.kt`, User-Message = rohes Transkript (kein Label/Präfix!)

---

## Wichtige Coding-Regeln

- **Kein `!!`-Operator** im Produktionscode — immer `?.` oder `runCatching`
- API-Keys immer mit `.trim()` lesen und schreiben
- Alle Overlay-UI-Änderungen auf Main-Thread (`Handler(Looper.getMainLooper()).post { }`)
- `windowManager.updateViewLayout` immer in `runCatching { }` wrapppen
- Commits direkt auf `main` — niemals Feature-Branches
- APK-Lieferung: immer aus `apk-dist` Branch holen und direkt in den Chat schicken
