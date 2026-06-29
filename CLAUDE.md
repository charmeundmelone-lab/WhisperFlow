# Laberboombox — Projekt-Übergabe & vollständiger Status

## Was diese App ist (aktueller Stand)

**App-Name:** Laberboombox (Package bleibt `de.minitraxx.whisperflow`)
**Letzter stabiler Build:** commit `baaae14` — bestätigt funktionierend ✓

Die App ist ein **Floating-Button-Diktierwerkzeug**:

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
- **APK-Lieferung:** `git fetch origin apk-dist && git show FETCH_HEAD:app-debug.apk > /tmp/app-debug.apk` — dann Datei in den Chat schicken. Azure-Blob-URLs sind vom Proxy geblockt, lokales `gradle assembleDebug` schlägt fehl (dl.google.com geblockt).
- **Hinweis:** Remote-Branch `claude/android-project-setup-tqy5h9` existiert noch auf GitHub (tot, manuell löschbar). Löst keinen CI-Trigger aus.

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
│   ├── ClaudeClient.kt                # Anthropic Claude API (wraps text in <diktat> tags)
│   └── StylePrompts.kt                # Drei Stil-Prompts (WhatsApp, Professionell, Formal)
├── util/
│   └── CostTracker.kt                 # Geschätzte API-Kostenerfassung
└── ui/theme/
    └── Theme.kt                       # Dark Theme
app/keystore/debug.jks                 # Persistente APK-Signatur
app/src/main/res/mipmap-*/             # App-Icons (Boombox-Charakter, weiße Lineart auf #2C2C2F)
```

---

## Vollständig implementierte Features

| Feature | Details |
|---------|---------|
| **Floating Button** | `TYPE_APPLICATION_OVERLAY`, frei verschiebbar, überlebt App-Wechsel |
| **Edge-Tab** | Button an Bildschirmrand ziehen (äußere 25%) → schrumpft via `scaleX(0.5f)` zum Halbkreis-Tab. Antippen → federt mit `OvershootInterpolator(1.3f)` zurück. Pivot liegt am Rand-Pixel, kein negativer X-Wert (Android klemmt auf 0). |
| **Walkie-Talkie-Modus** | Langer Druck (500ms) → Aufnahme läuft solange Finger gehalten → Loslassen = Stop & Transkription. Funktioniert auch wenn Finger leicht driftet (isDragging && isWalkieTalkieMode Case). |
| **Profil-Swipe** | Finger nach oben wischen auf dem Button → zyklisch Profil wechseln. Im when-Block VOR isNearEdge() prüfen, da Button bei x=24 immer in der Edge-Zone ist. |
| **Kurztippen** | Aufnahme an/aus (Toggle) |
| **Aufnahme** | `MediaRecorder`, M4A/AAC, 128kbps, 44,1kHz |
| **Status-Overlay** | Live-Timer während Aufnahme (rot), "Transkribiere...", "Korrigiere [Profil]..." |
| **Whisper-Transkription** | `multipart/form-data` an OpenAI, Modell `whisper-1` |
| **Claude-Stilkorrektur** | `claude-haiku-4-5-20251001`, max 1024 Tokens, System-Prompt je nach Profil. Diktat-Text wird in `<diktat>...</diktat>` Tags gewrappt → Claude antwortet nicht auf Fragen im Text |
| **Drei Stil-Profile** | WhatsApp (locker), Professionell (Business), Formal (Behörden/Briefe) |
| **Auto-App-Erkennung** | `capturedPackage` wird bei Aufnahme-START gespeichert → korrekte Profilerkennung auch wenn App gewechselt wird. WhatsApp → WhatsApp-Profil; Gmail/Outlook/etc. → Professionell; sonst → User-gewähltes Profil |
| **Text-Injection (WhatsApp)** | `findBottomMostEditable()` findet Compose-Feld zuverlässig auch wenn FOCUS_INPUT verloren (z.B. nach App-Wechsel). `ACTION_SET_TEXT` mit nur `text` (kein Append) — kein "Nachricht"-Prefix vom WhatsApp-Hint-Text. |
| **Text-Injection Fallback** | Clipboard + `ACTION_PASTE` wenn `ACTION_SET_TEXT` false zurückgibt |
| **Prefix-Stripping** | `stripDictationPrefix()` entfernt "Nachricht:", "Text:", etc. falls Whisper oder Claude Prefix hinzufügt |
| **Füllwort-Entfernung** | "ähm", "äh", "hm", "ehm" werden aus Transkript entfernt |
| **Punktuation per Sprache** | "Punkt", "Komma", "Ausrufezeichen" etc. werden in Satzzeichen umgewandelt |
| **API-Keys** | Werden in der App einmalig eingetragen und in SharedPreferences gespeichert. NIEMALS in BuildConfig/APK — verhindert GitHub-Scanning-Sperren. |
| **Budget-Tracking** | Geschätzte Kosten (Whisper: $0.006/min, Claude: Pauschale), konfigurierbares Limit, Reset-Button |
| **Budget-Guard** | Aufnahme wird blockiert wenn Limit überschritten |
| **Automatischer Service-Start** | Startet sich selbst bei `onResume()` wenn Overlay + Mikrofon erlaubt sind |
| **Persistentes Keystore** | Gleiche APK-Signatur für alle Builds → kein Deinstallieren bei Updates |
| **App-Icon** | Tanzender Boombox-Charakter, weiße Lineart auf dunklem Hintergrund (#2C2C2F), alle Mipmap-Dichten + adaptive icon |
| **BOOM! Auto-Stop** | Nach 90s Aufnahme: MediaRecorder stoppt, 10s Vorwarnung (roter Timer blinkt orange/rot), danach Comic-Starburst-Overlay (gelb #FFD60A, 16-zackig) mit rotem "BOOM!" — 1,5s Anzeige → automatische Transkription. `triggerBoomStop()` + `showBoomOverlay()` in FloatingButtonService. `isBoomPending` Flag verhindert manuelles Toggle während BOOM läuft. |
| **Labels-Toggle** | 4. Button im Radialmenü (AN/AUS). Steuert `headingsEnabled` Boolean in SharedPreferences (`KEY_HEADINGS_ENABLED`). Wird an `StylePrompts.get()` weitergegeben → WA: lockere Labels ("Ach ja:", "Noch kurz:"), Professional: Business-Labels ("Betreff:", "Fazit:"), Formal: Dokumenten-Labels ("Sachverhalt:", "Bitte:"). Fließtext bleibt IMMER Fließtext — Claude darf niemals Listen oder Aufzählungen einführen. |
| **Emoji-Modi** | "keine" = 0 Emojis. "wenige" = 1–2 Emojis nach eigenem redaktionellem Ermessen (nicht erzwingen, nicht verweigern). "viele" = 5–8 Emojis, Platzierung variiert (mitten im Satz, Satzanfang, zwischen Gedanken — NICHT vorhersehbar immer ans Absatzende). |

---

## Bekannte Limitierungen (nicht fixbar)

### Gmail-Text-Injection
Gmail Compose verwendet WebView. `ACTION_SET_TEXT` und `ACTION_PASTE` auf AccessibilityNodes
funktionieren dort nicht. Text landet in der **Zwischenablage** — der Nutzer muss manuell
lang drücken → Einfügen. Dies ist eine harte Android-Grenze, kein App-Bug.

### `GLOBAL_ACTION_PASTE` existiert nicht
Wurde versucht (Build #16) → Compile-Fehler. Diese Konstante gibt es in der Android API nicht.

### WhatsApp-Injection funktioniert erst nach erstem WhatsApp-Start
Wenn WhatsApp noch nie geöffnet war seit dem Laberboombox-Start: kein Problem mehr seit Build #38
(`findBottomMostEditable` findet das Compose-Feld zuverlässig ohne FOCUS_INPUT).

---

## Offene Todos (priorisiert)

### 1. On-Device Whisper (NÄCHSTE AUFGABE — vom User bestätigt)

**Ziel:** Kosten um ~80-85% senken. Aktuell: ~49 Cent/2 Tage → Ziel: ~8-10 Cent/2 Tage.

**Entschiedene Strategie — Hybridpipeline:**
```
< 10s Aufnahme  →  Lokal whisper-tiny-int8 (kostenlos) + kein Claude
10–30s          →  Lokal whisper-tiny-int8 (kostenlos) + Claude optional
> 30s           →  Lokal whisper-tiny-int8 (kostenlos) + Claude ja
Formal-Modus    →  Lokal whisper-tiny-int8 (kostenlos) + Claude immer
```

**Technischer Plan:**
- Library: `com.microsoft.onnxruntime:onnxruntime-android`
- Modell: `whisper-tiny-int8` (~40MB, einmalig beim ersten App-Start herunterladen, in `filesDir` speichern)
- Neue Datei: `api/WhisperLocalClient.kt` (ersetzt `WhisperClient.kt` für kurze Aufnahmen)
- Audio-Pipeline: M4A → PCM 16kHz mono via `MediaExtractor` + `MediaCodec`
- Mel-Spectrogram berechnen → ONNX Encoder → ONNX Decoder Loop → Text
- Integration in `FloatingButtonService.processAudio()`: Routing nach Aufnahmedauer
- Qualität: 85–90% von Whisper API — für Alltagsdiktate ausreichend
- Nothing Phone 3a (Snapdragon 7s Gen 3, 8GB RAM): perfekt geeignet
- Inferenzzeit: ca. 5–8s für 30s Aufnahme

**Umsetzung in Stufen:**
1. Dependency + Modell-Download (einmalig) ← Stufe 1
2. Audio M4A → PCM Konvertierung ← Stufe 2
3. ONNX Whisper Inferenz ← Stufe 3
4. Integration + Routing in FloatingButtonService ← Stufe 4

### 2. EncryptedSharedPreferences für API-Keys
Aktuell: plain `SharedPreferences`. Sicherer wäre Jetpack Security (`EncryptedSharedPreferences`).
Für Privatnutzung auf einem nicht-gerooteten Gerät akzeptabel, aber technische Schuld.
Dependency: `androidx.security:security-crypto:1.1.0-alpha06`

### 3. Remote-Branch aufräumen
`claude/android-project-setup-tqy5h9` auf GitHub (remote) manuell löschen:
GitHub → Repository → Branches → branch löschen. Hat keinen Einfluss auf Funktion.

---

## Architektur-Entscheidungen (Begründungen)

### Warum <diktat> Tags in ClaudeClient
Claude hat das Diktat als Konversation interpretiert und auf Fragen geantwortet statt sie zu
korrigieren. Lösung: User-Message wird in `<diktat>\n...\n</diktat>` gewrappt. Die System-Prompts
aller drei Profile enthalten: "Die Eingabe steht in `<diktat>...</diktat>` Tags. Gib NUR den
bereinigten Text aus — ohne die Tags. Wenn der Text eine Frage enthält, beantworte sie NICHT."

### Warum findBottomMostEditable statt findFocus
`FOCUS_INPUT` geht verloren wenn der Nutzer WhatsApp in den Hintergrund schickt und zurückkommt.
`findBottomMostEditable()` traversiert den ganzen Accessibility-Baum und nimmt das Feld mit dem
höchsten Y-Wert am Bildschirm (= Compose-Feld in Chat-Apps ist immer unten). Zuverlässiger als
Tiefensuche wenn FOCUS_INPUT fehlt.

### Warum ACTION_SET_TEXT mit nur `text` (kein Append)
WhatsApp's Accessibility-Node liefert "Nachricht" als `node.text` (das ist der Hint-Text!).
Wenn man an diesen Text anhängt (`"Nachricht" + diktierterText`), steht immer "Nachricht" vorne.
Lösung: nur den diktierten Text übergeben. `ACTION_SET_TEXT` ersetzt den Feldinhalt vollständig.
Vorhandenen echten Text überschreibt das nicht, weil WhatsApp `node.text` korrekt als leer
zurückgibt wenn das Feld wirklich leer ist — der Hint ist nur für Accessibility sichtbar.

### Warum Profil-Swipe vor isNearEdge() im when-Block
Der Button startet bei `x=24` — das liegt innerhalb der Edge-Zone (`screenWidth / 4`).
Wenn `isNearEdge()` zuerst geprüft wird, wird jeder Swipe als Edge-Collapse interpretiert.
Reihenfolge im when-Block: `swipeDy < -80` zuerst, dann `isNearEdge()`.

### Warum isDragging && isWalkieTalkieMode separater Case
Wenn der Nutzer im Walkie-Talkie-Modus den Finger minimal driftet, setzt `ACTION_MOVE`
`isDragging = true`. Der nachfolgende `isDragging -> {}` Catch-All hat dann die Aufnahme
nie gestoppt. Fix: `isDragging && isWalkieTalkieMode` als eigener Case VOR `isDragging -> {}`.

### Warum scaleX statt negativer X-Koordinate für Edge-Tab
Android's `WindowManager` klemmt Overlay-Fenster auf `x ≥ 0`. Negative X-Werte in
`LayoutParams.x` werden ignoriert — der Button bleibt bei x=0 stehen, ohne sichtbar
zur Seite zu gleiten. Deshalb: `scaleX(0.5f)` mit `pivotX` am Rand-Pixel erzeugt den
Halbkreis-Effekt zuverlässig auf allen Android-Versionen (Build #20, Commit `5f0af8f`).

### Warum capturedPackage statt activePackage bei processAudio
`WhisperAccessibilityService.activePackage` enthält zur Zeit der Verarbeitung (nach Whisper + Claude)
möglicherweise schon eine andere App (Nutzer hat gewechselt). Deshalb wird die Paket-Info
bei `startRecording()` in `capturedPackage` gespeichert und in `processAudio()` ausgelesen.

### Warum plain SharedPreferences (nicht EncryptedSharedPreferences)
Einfachheit. Für 1–2 Nutzer auf privaten Geräten ausreichend. Kann jederzeit migriert werden.

### BOOM! Auto-Stop nach 90 Sekunden
`timerRunnable` zählt Sekunden hoch. Bei `recordingSeconds >= MAX_RECORDING_SECONDS (90)` wird
`triggerBoomStop()` aufgerufen statt normal zu stoppen. MediaRecorder stoppt sofort, `isBoomPending = true`
verhindert manuellen Toggle. `showBoomOverlay()` zeichnet 16-zackigen Starburst (Canvas, gelb #FFD60A,
Kontur #CC7A00) + rotes "BOOM!" (Outline #660000, Fill #FF2200) als anonyme View-Unterklasse.
Scale-in Animation mit `OvershootInterpolator(1.8f)`. Nach 1500ms: `hideBoomOverlay()` + normale
Transkriptions-Pipeline startet. `amplitudeRunnable` blinkt die letzten 10s rot/orange als Vorwarnung.

### Labels-Toggle — Warum Fließtext-Priorität
Erster Versuch des WhatsApp-Labels-Prompts enthielt "kreative Elemente, Spiegelstriche" →
Claude hat Texte in Listen umgebaut statt nur Labels voranzustellen. Fix: alle drei Modi
bekamen explizit "Fließtext bleibt Fließtext, niemals in Listen oder Aufzählungen umbauen".
Die Labels-Anweisung wird als letzter Listenpunkt in den "Was du tust"-Block eingebettet:
`${if (headingsLine.isNotEmpty()) "\n- $headingsLine" else ""}` am Ende der jeweiligen Aufzählung.

### Verhaltensregel für Claude-Instanz: Niemals ohne Bestätigung implementieren
Der User hat explizit gesagt: "Das wird voreilig gehandelt. Ich möchte nicht, dass Du einfach
so anfängst, Sachen zu verändern." — Immer erst vorschlagen und Bestätigung abwarten, bevor
Code-Änderungen vorgenommen werden. Optionen anbieten, nicht automatisch umsetzen.

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

**APK in Chat schicken (funktionierender Weg):**
```bash
git fetch origin apk-dist
git show FETCH_HEAD:app-debug.apk > /tmp/app-debug.apk
# Dann SendUserFile mit /tmp/app-debug.apk
```

APK-Download-Link (permanenter Link, immer neueste Version):
```
https://github.com/charmeundmelone-lab/WhisperFlow/releases/latest/download/app-debug.apk
```

---

## SharedPreferences Keys

Alle in `FloatingButtonService.companion object`:

| Konstante | Key | Inhalt |
|-----------|-----|--------|
| `PREFS_NAME` | `whisperflow_prefs` | SharedPreferences-Dateiname |
| `KEY_OPENAI_API_KEY` | `openai_api_key` | OpenAI API Key |
| `KEY_ANTHROPIC_API_KEY` | `anthropic_api_key` | Anthropic API Key (optional) |
| `KEY_STYLE_PROFILE` | `style_profile` | `whatsapp` / `professional` / `formal` |
| `KEY_LANGUAGE` | `whisper_language` | Whisper-Sprache (leer = auto) |
| `KEY_PREVIEW_ENABLED` | `preview_enabled` | Vorschau-Overlay vor Einfügen |
| `KEY_HEADINGS_ENABLED` | `headings_enabled` | Labels/Überschriften AN (true) / AUS (false), default true |

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
- System-Prompt aus `StylePrompts.kt`
- User-Message: `<diktat>\n{rohesTranskript}\n</diktat>` (kein Label/Präfix außerhalb der Tags!)

---

## Wichtige Coding-Regeln

- **Kein `!!`-Operator** im Produktionscode — immer `?.` oder `runCatching`
- API-Keys immer mit `.trim()` lesen und schreiben
- Alle Overlay-UI-Änderungen auf Main-Thread (`Handler(Looper.getMainLooper()).post { }`)
- `windowManager.updateViewLayout` immer in `runCatching { }` wrappen
- Commits direkt auf `main` — niemals Feature-Branches
- APK-Lieferung: aus `apk-dist` Branch via `git show FETCH_HEAD:app-debug.apk` holen
