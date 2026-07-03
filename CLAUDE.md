# Laberboombox — Projekt-Übergabe & vollständiger Status

## Was diese App ist (aktueller Stand)

**App-Name:** Laberboombox (Package bleibt `de.minitraxx.whisperflow`)
**Letzter stabiler Build:** commit `57b4bcf` auf `main` — bestätigt funktionierend ✓

Die App ist ein **Floating-Button-Diktierwerkzeug**:

1. Floating Button tippen → Aufnahme startet
2. Nochmal tippen → Aufnahme stoppt
3. OpenAI Whisper transkribiert die Aufnahme
4. Claude Haiku korrigiert Grammatik & Stil (je nach App/Profil)
5. Text wird direkt in das aktive Textfeld der Vordergrund-App eingefügt

Zielgruppe: 1–2 Personen (Privatnutzung), primär auf Deutsch.

---

## Repo & Branch-Regeln — KRITISCH

- **Repo:** `charmeundmelone-lab/WhisperFlow`
- **Entwicklung immer auf `main` pushen** — CI baut nur bei Push auf `main`
- APK landet auf Branch `apk-dist` (force-push durch CI): `app-debug.apk` + `app-debug-<sha>.apk`
- **APK-Lieferung:**
  ```bash
  git fetch origin apk-dist
  git show FETCH_HEAD:app-debug.apk > /tmp/app-debug.apk
  # Dann SendUserFile mit /tmp/app-debug.apk
  ```

### Session-Feature-Branch-Problem (jede neue Claude-Session betroffen)

Claude Code im Web erstellt automatisch einen Feature-Branch (z.B. `claude/xyz`).
CI baut aber nur auf `main`. **Fix nach jedem Commit:**

```bash
git fetch origin main
git rebase origin/main
git push origin HEAD:main
```

Wenn das mit "non-fast-forward" scheitert (weil main schon weiter ist):
```bash
git fetch origin main && git rebase origin/main && git push origin HEAD:main
```

### Branch-Realität in Cloud-Sessions (Fund vom 2026-07-01) — WICHTIG

In Claude-Code-Web-Sessions ist der Git-Zugriffsproxy technisch auf den zugewiesenen
Feature-Branch beschränkt. Direkter Push auf `main`, das Setzen von Git-Tags UND das
Löschen von Branches (`git push origin --delete ...`) schlagen alle mit **HTTP 403** fehl —
unabhängig davon, was oben steht. Nicht mit `--force` erzwingen. Stattdessen:

```bash
git push -u origin <zugewiesener-branch>
```
und dann per `mcp__github__create_pull_request` einen PR nach `main` erstellen. Der Nutzer
prüft & merged (oder bittet die Session, den PR-Merge-Tool-Call selbst auszuführen), CI baut
danach automatisch. Branch-Löschung/Aufräumen muss der Nutzer manuell auf GitHub machen
(Button auf der PR-Seite). Dieser Weg gilt zusätzlich zum Rebase-Fix oben, nicht als Ersatz —
falls direkter main-Push doch mal funktioniert (z.B. lokale/Desktop-Session mit vollen
Rechten), bleibt der Rebase-Fix der schnellere Weg.

### Stop-Hook "Unverified commits" — Fix

```bash
git config user.email noreply@anthropic.com
git config user.name Claude
git rebase --exec "git commit --amend --no-edit --reset-author" origin/<feature-branch-name>
git push --force-with-lease origin <feature-branch-name>
```

Der Hook prüft `git log upstream..HEAD` auf unsigned commits (`%G? == N`).
Nach `--reset-author` sind die Commits korrekt signiert.

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
│   ├── ClaudeClient.kt                # Anthropic Claude API (max_tokens: 2500)
│   └── StylePrompts.kt                # Drei Stil-Prompts (WhatsApp, Professionell, Formal)
├── util/
│   └── CostTracker.kt                 # Geschätzte API-Kostenerfassung
└── ui/theme/
    └── Theme.kt                       # Dark Theme
app/keystore/debug.jks                 # Persistente APK-Signatur
app/src/main/res/mipmap-*/             # App-Icons (Boombox-Charakter, weiße Lineart auf #2C2C2F)
docs/
├── betriebsanleitung.html             # Vollständige Bedienungsanleitung (6 Seiten, illustriert)
└── button-guide.html                  # Einseitige Button-Kurzanleitung (druckbar als PDF)
```

---

## Vollständig implementierte Features

| Feature | Details |
|---------|---------|
| **Floating Button (Option A)** | Immer an linkem oder rechtem Bildschirmrand (`x=0` oder `x=sw-buttonSize`). Drag → `snapToNearestEdge()` mit `DecelerateInterpolator`. Seite wird in `KEY_EDGE_SIDE` gespeichert. |
| **Auto-Minimize (5s)** | Nach 5s Inaktivität (kein Recording, kein Menü): `collapseToEdge()` → gelber Tab-Streifen (16dp×64dp). Antippen → `expandFromEdge()` mit `OvershootInterpolator(1.3f)`. `inactivityHandler` wird bei Recording und Menü gecancelt. |
| **Landscape-Handling** | `onConfigurationChanged()` repositioniert Button/Tab in neuer Orientierung. Requires `android:configChanges="orientation|screenSize|screenLayout"` im Manifest für FloatingButtonService. |
| **Kurztippen** | Aufnahme an/aus (Toggle). Kein Walkie-Talkie-Modus mehr. |
| **Langes Drücken (500ms)** | Öffnet Radialmenü (nur wenn nicht am Edge-Tab und nicht während Aufnahme). |
| **Radialmenü** | 4 Punkte: Profil (WA/PRO/FOR), Emojis (🙂/🎉/—), Sprache (🌐/DE/EN), Labels (AN/AUS). Fächert zur Bildschirmmitte auf. Schließt nach 2,5s automatisch. |
| **Aufnahme** | `MediaRecorder`, M4A/AAC, 128kbps, 44,1kHz. Pill-Animation beim Start/Stop (Breite ändert sich). Rechts-Edge: `params.x = sw - w` damit Pill von rechts wächst. |
| **Status-Overlay** | Live-Timer + Wellenform während Aufnahme (rot), "Transkribiere...", "Korrigiere [Profil]...". Position: Innenseite des Buttons (links wenn rechts-edge, rechts wenn links-edge). |
| **Duration Badge (unten)** | Unter dem Button: zeigt aktuelle Maximaldauer (30s/90s/3m/5m). Tippen zyklisch wechseln. |
| **Mini-Badge ⚡ (oben)** | Über dem Button: aktiviert 10s-Schnellmodus. Leuchtet gelb wenn aktiv. |
| **Emoji-Toggle-Badge (Seite)** | An der Innenseite des Buttons (gegenüber dem Rand). "🙂" gelb = Emojis an (EMOJI_FEW), "—" grau = Emojis aus (EMOJI_NONE). Tippen togglet. Bounce-Animation bei Tap. |
| **BOOM! Auto-Stop** | Nach konfigurierbarer Maximaldauer (Standard 30s, max 90s per Preset). 10s Vorwarnung: Timer blinkt orange/rot. Dann: Comic-Starburst (16-zackig, #FFD60A) + "BOOM!" (rot) für 1,5s → automatische Transkription. `isBoomPending` verhindert Toggle während BOOM. |
| **Whisper-Transkription** | `multipart/form-data` an OpenAI, Modell `whisper-1`. Kosten: $0.006/min sekundengenau. 30s = 0,3 Cent. |
| **Claude-Stilkorrektur** | `claude-haiku-4-5-20251001`, max_tokens: 8192, readTimeout: 180s, System-Prompt je nach Profil. Diktat-Text in `<diktat>...</diktat>` Tags → Claude antwortet nicht auf Fragen im Text. |
| **Drei Stil-Profile** | WhatsApp (locker, kein Umformulieren), Professionell (Business, kein Umformulieren), Formal (Behörden — nur Wort-für-Wort-Ersatz, kein Satzbau-Umbau). |
| **StylePrompts — ABSOLUT VERBOTEN zuerst** | Jeder Prompt beginnt mit ABSOLUT VERBOTEN-Block. Enthält explizit: "Sätze umformulieren, Wörter durch Synonyme ersetzen, oder den Stil des Sprechers verändern." Dann "Was du NIEMALS tust" mit konkreten Verboten. Formal: Darf einzelne Wörter formalisieren, NICHT Satzstruktur ändern. |
| **Auto-App-Erkennung (gefixt)** | `WhisperAccessibilityService.onAccessibilityEvent()` updatet `activePackage` NUR bei `TYPE_WINDOW_STATE_CHANGED`. Filtert eigene Package (`de.minitraxx.whisperflow`) und System-Pakete (`com.android.*`, `android`) heraus. `capturedPackage` wird bei `startRecording()` gespeichert → korrekt auch wenn App gewechselt wurde. |
| **Text-Injection (WhatsApp)** | `findBottomMostEditable()` findet Compose-Feld zuverlässig auch wenn FOCUS_INPUT verloren. `ACTION_SET_TEXT` mit nur `text` → kein "Nachricht"-Prefix. |
| **Text-Injection Fallback** | Clipboard + `ACTION_PASTE` wenn `ACTION_SET_TEXT` false zurückgibt. |
| **Überschriften-Toggle (Labels)** | Im Radialmenü (AN/AUS). Steuert `headingsEnabled`. WA: originelle, witzige Kurztitel. Professionell: knapp & präzise. Formal: sachlich ("Sachverhalt:", "Bitte:"). Fließtext bleibt IMMER Fließtext. |
| **Emoji-Modi** | `none`: 0 Emojis. `few`: 1–2 nach eigenem Ermessen (nicht erzwingen). `many`: 5–8, variiert platziert. Toggle-Badge schaltet nur `none`↔`few`. Radialmenü erlaubt auch `many`. |
| **Füllwort-Entfernung** | "ähm", "äh", "hm", "ehm" aus Transkript entfernen (vor Claude-Korrektur). |
| **Punktuation** | "Punkt", "Komma", "Ausrufezeichen", "Absatz" etc. → Satzzeichen/Newlines. |
| **API-Keys** | In SharedPreferences, niemals in BuildConfig. Mit `.trim()` lesen/schreiben. |
| **Budget-Tracking** | Geschätzte Kosten (Whisper: $0.006/min, Claude: Pauschale), Limit, Reset. |
| **Persistentes Keystore** | Gleiche APK-Signatur → keine Deinstallation bei Updates. |
| **App-Icon** | Tanzender Boombox-Charakter, weiße Lineart auf #2C2C2F, alle Mipmap-Dichten + adaptive icon. |
| **Pulse Ring** | Roter Pulsring während Aufnahme (eigenes Overlay-Fenster, animiert). |
| **Korrektur-Vorschau** | Optional (KEY_PREVIEW_ENABLED): Zeigt korrigierten Text vor dem Einfügen. 10s Timeout. |
| **Dokumentation** | `docs/betriebsanleitung.html`: vollständige illustrierte Bedienungsanleitung. `docs/button-guide.html`: einseitige Kurzanleitung (A4, dunkel, druckbar). |
| **Erweiterte Füllwort-Entfernung** | Kontextsensitiv: "also", "genau", "ne", "halt", "quasi" nur wenn bedeutungslos. Inhaltliche Verwendung bleibt erhalten. |
| **Wisch-nach-unten zum Verwerfen** | Während Aufnahme: dy > 80dp → discardGestureActive → Loslassen verwirft. Button bleibt dabei FIXIERT (kein Mitbewegen). |
| **Mindestdauer 1,5s** | Aufnahmen < 1500ms werden still verworfen — kein Whisper-Call, keine Kosten. Schützt vor versehentlichen Taps. In `stopRecording()` geprüft. |
| **Bottom-Sheet-Editor** | Vorschau-Overlay mit Satz-Tap-Auswahl, Löschen, Undo, Kopieren, Swipe-up-zum-Einfügen, Swipe-down-zum-Verwerfen, Mini-Aufnahme zum Anhängen. |
| **Kreative Absatztrenner** | WA/Professionell: variabel nach Rhythmus (Leerzeile / — / · · ·). Formal: immer Leerzeile. Konfiguriert in `StylePrompts.kt`. |
| **Plattdeutsch-Modus** | Radialmenü Sprache: PLT → whisper_language="de" + Claude-Prompt bewahrt Dialektwörter exakt. |

---

## Bekannte Limitierungen (nicht fixbar)

### Gmail-Text-Injection
Gmail Compose verwendet WebView. `ACTION_SET_TEXT` und `ACTION_PASTE` auf AccessibilityNodes
funktionieren dort nicht. Text landet in der **Zwischenablage** — der Nutzer muss manuell
lang drücken → Einfügen. Dies ist eine harte Android-Grenze, kein App-Bug.

---

## Offene Todos (priorisiert)

### 1. On-Device Whisper — Option 1 umgesetzt (Stand 2026-07-02)

**Umsetzungsstand 2026-07-02 — "Option 1: On-Device nur bis 30s" implementiert.**
Der Nutzer hat sich (Kostenmotiv: 10s-⚡- und 30s-Aufnahmen sind das Gros) für eine
abgespeckte erste Ausbaustufe entschieden: **Aufnahmen ≤30s laufen lokal (ein einziges
Whisper-Fenster, kein Chunking), alles darüber weiterhin Cloud.** Dadurch entfällt der
gesamte Session-/Chunk-Komplex des ursprünglichen Plans (Puffer, Fertig-Badge, ⚡-Umbau,
Übergangsglättung, Gesamtpuffer-Verarbeitung, Auto-Minimize-Änderung) — der Plan unten
bleibt als Referenz für eine spätere Voll-Ausbaustufe stehen.

**Was implementiert ist (Option 1):**
- `app/src/main/cpp/`: CMakeLists (FetchContent whisper.cpp `v1.9.1`, gepinnt) + `whisper_jni.cpp` (JNI-Bridge)
- Gradle: `ndkVersion 27.2.12479018`, CMake 3.22.1, nur `arm64-v8a` (Nothing Phone 3a)
- `whisper/WhisperJni.kt` — lazy `System.loadLibrary` in runCatching, nie beim App-Start
- `whisper/AudioDecoder.kt` — M4A/AAC → 16kHz Mono Float-PCM (MediaCodec + Linear-Resampling); Aufnahme-Pipeline unverändert
- `whisper/ModelManager.kt` — expliziter Download-Button (`ggml-small-q5_1.bin`, ~190MB, HuggingFace), Range-Resume, StateFlow-Progress; Modell unter `getExternalFilesDir("models")` (Stand nach Nachtrag 5: `large-v3-turbo` war ursprünglich zweite Option, wieder entfernt)
- `whisper/LocalWhisperEngine.kt` — eigener Scope, busy-Gate, 150s-Timeout (Engine erholt sich selbst, Aufrufer geht zur Cloud)
- `FloatingButtonService.smartTranscribe()` — Flag AN + Dauer ≤31,5s + Modell da → lokal; JEDER Fehler → stiller Cloud-Fallback; Kosten nur bei tatsächlichem Cloud-Aufruf (`CostTracker.recordAudio` von den Aufrufstellen hierher verschoben). Auch die Mini-Aufnahme im Bottom-Sheet nutzt den Pfad (und wird jetzt ebenfalls kostenerfasst)
- Budget-Gate in `startRecording`: kostenlose lokale Aufnahmen (Preset ≤30s + Modell) starten auch bei aufgebrauchtem Guthaben
- Feature-Flag `KEY_ONDEVICE_WHISPER` (default AUS) + Settings-Card in MainActivity (Switch, Download/Fortsetzen/Abbrechen/Löschen, Progress)
- CI: sdkmanager installiert NDK+CMake vor dem Build
- Status-Text zeigt "Transkribiere (lokal)..." beim lokalen Versuch — so ist auf dem Gerät erkennbar, welcher Pfad lief

**Nachtrag 2026-07-02 (nach Geräte-Test):** large-v3-turbo funktionierte auf dem Gerät
(Qualität "perfekt" laut Nutzer), war aber unbrauchbar langsam (>60s, Gerät warm). Deshalb
zwei Performance-Maßnahmen umgesetzt:
- **Modell-Auswahl im ModelManager/Settings-Card:** "Schnell (small)" (`ggml-small-q5_1.bin`,
  ~190MB, Empfehlung) vs. "Maximale Qualität (large-v3-turbo)". Auswahl in
  `KEY_ONDEVICE_MODEL` (`ondevice_model`: `small`/`turbo`); ohne explizite Wahl nimmt
  `selectedModel()` das schnellste vorhandene Modell.
- **audio_ctx-Optimierung:** Encoder-Kontext wird auf die tatsächliche Audiolänge begrenzt
  (`ceil(len/30*1500)+64`, Kappe 1500, ab ≥29s Default) — Whisper rechnet sonst immer das
  volle 30s-Fenster durch. Größter Gewinn bei 10s-⚡-Aufnahmen (~3x).

**Nachtrag 2 (2026-07-02, Abend):** Auch `small` lief in den 150s-Timeout → Ursache
gefunden: Build ohne ARM-SIMD-Kernels (`GGML_NATIVE=OFF` ohne Arch-Angabe = Skalar-Pfade,
Faktor 4–8x). Fix-Paket v1.2.1:
- `-march=armv8.2-a+dotprod+fp16` (cFlags/cppFlags) + `-DGGML_CPU_ARM_ARCH=...` — Achtung:
  läuft dadurch NUR auf armv8.2+-Geräten (Nothing Phone 3a = armv9, ok; ältere arm64 würden
  mit SIGILL crashen — bewusster Trade-off, Single-Device-App)
- `temperature_inc=0` (Temperature-Fallback kann Decode-Zeit vervielfachen)
- Decoder-Stall-Guard (30s-Deadline in AudioDecoder)
- Engine-Timeout 150s→90s, Timeout-Diagnose nennt jetzt die Phase (Lib/Decode/Modell/Inferenz)
- Vergleichs-Referenz des Nutzers: whisperIME von OpenAPK läuft flott → deren Kern-Trick ist
  TFLite-int8 + korrekte SIMD-Nutzung. Falls v1.2.1 immer noch zu langsam: Plan B = TFLite-
  Engine wie whisperIME statt whisper.cpp.

**Nachtrag 3 (2026-07-03):** v1.2.1-Test — "small" (181MB) brauchte 78,8s für 7s Audio
(~11x realtime, quasi unverändert trotz SIMD-Fix) → SIMD war nicht die Hauptursache.
**Root Cause gefunden: die App ist komplett Android-"debug"-BuildType** (bewusst wg.
persistenter Signatur) — AGP haengt dafuer automatisch `-DCMAKE_BUILD_TYPE=Debug` an den
CMake-Configure-Call, d.h. der gesamte native whisper.cpp/ggml-Code lief **ohne jede
Compiler-Optimierung (-O0)**. SIMD-Intrinsics ohne Optimierungs-Pass bringen praktisch
nichts. Fix-Paket v1.2.2:
- `CMakeLists.txt`: `set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)` direkt nach
  `project()` — überschreibt garantiert den von Gradle injizierten Debug-Wert
- `build.gradle.kts`: zusätzlich `-DCMAKE_BUILD_TYPE=Release` als Argument + explizites
  `-O3` in cFlags/cppFlags als Sicherheitsnetz (letztes `-O`-Flag gewinnt bei Clang immer)
- Threads 4→6 (Snapdragon 7s Gen 3 hat 8 Kerne, vorher ungenutzt)

**Nachtrag 4 (2026-07-03, Geräte-Test v1.2.2):** Nutzer meldet weiterhin ~2min für 10s Audio
— der Release-Fix hat also NICHT geholfen wie erwartet. Statt weiter zu raten: Tiefendiagnose
per CI-Verbose-Build (`-Pandroid.native.buildOutput=verbose --info`, PR #10) hat die tatsächliche
`cmake`-Kommandozeile aus dem echten Build-Log extrahiert:
```
-DCMAKE_C_FLAGS=-march=armv8.2-a+dotprod+fp16 -O3
-DCMAKE_CXX_FLAGS=-std=c++17 -march=armv8.2-a+dotprod+fp16 -O3
... -DCMAKE_BUILD_TYPE=Release
```
plus alle 32 ggml/whisper.cpp-Objektdateien wurden nachweislich frisch kompiliert (FetchContent
lädt bei jedem CI-Run neu, ephemere Runner — kein Cache-Fehlalarm). Zusätzlich per ELF-Analyse
der ausgelieferten APK bestätigt: `ggml_cpu_has_dotprod()` ist fest auf `1` kompiliert, 480
sdot/udot-Instruktionen im Binary vorhanden. **Fazit: Release-Optimierung + ARM-SIMD sind
zweifelsfrei korrekt im Build — das war nicht (mehr) die Ursache.**

Wahrscheinlichste verbleibende Erklärung: das auf dem Gerät **ausgewählte Modell** ist noch
`turbo` (547MB) aus dem allerersten Test, nicht `small` — die Auswahl steht explizit in
SharedPreferences (`ondevice_model`) und wird durch keinen der bisherigen Fixes zurückgesetzt.
2min für 10s Audio passt eher zu turbo auf CPU als zu small mit allen Optimierungen.

Fix-Paket (Diagnose-Instrumentierung, noch kein Verhaltens-Fix):
- `LocalWhisperEngine`: jede Phase (Lib/Decode/Modell/Inferenz) wird einzeln mit Timestamp
  geloggt (`adb logcat -s LocalWhisperEngine`), auch wenn der Timeout die finale Zeile nie erreicht
- Timeout-Meldung (auch in der UI-Diagnose-Card sichtbar, kein logcat nötig) nennt jetzt
  **Modell-ID + hängende Phase + Dauer + ob `ggml_cpu_has_dotprod()` zur Laufzeit true liefert**
  (neue JNI-Funktion `nativeHasDotprod`) — z.B. "On-Device-Timeout: Modell=turbo, Phase='Inferenz'
  (89523ms), dotprod=true"

**Noch offen (Option 1):** Nutzer muss in den Einstellungen prüfen/explizit auf "Schnell (small)"
umstellen, dann erneut testen — die neue Diagnosezeile zeigt dann zweifelsfrei Modell + Phase.
Falls small mit Release-Build weiterhin >>10s braucht, ist der nächste Verdacht Geräte-seitiges
CPU-Throttling/Background-Priority (Nothing OS), nicht mehr der Compile-Pfad. Falls das bestätigt
und weiterhin zu langsam → Plan B (TFLite wie whisperIME). GPU/Vulkan bewusst nicht angefasst.

**Nachtrag 5 (2026-07-03): `large-v3-turbo` komplett entfernt.** Nutzer wollte das Modell
nicht mehr als Option, wenn es ohnehin unbrauchbar langsam ist. `ModelManager.MODELS` enthält
jetzt nur noch `small` — die Auswahl-UI in `MainActivity` ist datengetrieben (`MODELS.forEach`)
und zeigt turbo dadurch automatisch nicht mehr an. `ModelManager.cleanupOrphanedModels()`
(neu, aufgerufen einmal in `MainActivity.onCreate`) löscht eine evtl. schon heruntergeladene
turbo-Datei aus der ersten Testinstallation automatisch von der Platte — kein manuelles
Aufräumen auf dem Gerät nötig. `selectedModel()` fällt für Altinstallationen mit
`ondevice_model=turbo` in den Prefs automatisch auf `small` zurück (turbo ist schlicht nicht
mehr in `MODELS`, kein Migrationscode nötig).

**Ursprünglicher Status (2026-07-01):** Eine vorherige Session hatte das Konzept komplett
durchgeplant (siehe Architektur unten); die damalige Umsetzung wurde bewusst zurückgebaut
(PR geschlossen, nicht gemerged).

**Die Architektur-Entscheidungen unten sind das Ergebnis einer langen, sorgfältigen
Q&A-Planungsrunde mit dem Nutzer — als Ausgangspunkt/Referenz gedacht, NICHT als in Stein
gemeißelt.** Eine neue Session darf und soll sie hinterfragen, falls ein anderer Ansatz
sinnvoller erscheint — aber muss nicht bei null anfangen, falls die Entscheidungen weiterhin
passen.

**Was konkret schiefgelaufen ist / zum Rückbau führte:** kein technisches Problem mit
whisper.cpp selbst (beide Meilensteine liefen erfolgreich durch CI) — der Nutzer war mit dem
Gesamtverlauf der Session unzufrieden (u.a. eine Verwirrung um Branch-Push-Rechte, siehe
"Branch-Realität" oben, und generelles Unbehagen beim Umfang/Tempo) und ist außerdem im
Sessionverlauf ans Wochen-Tokenlimit gestoßen. Eine neue Session sollte daher: (a) kleinere,
noch klarer bestätigte Schritte machen, (b) den Nutzer nicht mit zu vielen Rückfragen auf
einmal konfrontieren, (c) den Tokenverbrauch im Blick behalten und früh genug Bescheid geben,
wenn eine größere Aufgabe (wie natives Whisper.cpp) realistischerweise mehrere Sessions
braucht.

**Architektur-Entscheidungen (aus der Planungsrunde, siehe Begründung oben):**
- **Runtime:** whisper.cpp (GGML), eigene Einbettung (offizielles `ggml-org/whisper.cpp`
  Android-Beispiel als Vorlage). Kein Code-Copy-Paste von Fremd-Apps.
- **Modell:** `large-v3-turbo`, multilingual, quantisiert — beste erreichbare Qualität bei
  noch akzeptabler Geschwindigkeit auf dem Nothing Phone 3a (Snapdragon 7s Gen 3, 8GB RAM).
- **Chunk-Grenze:** feste 30s (Whisper-Architekturgrenze) + bestehende BOOM-Vorwarnung
  (10s-Blinken) als Signal für bewusste Sprechpause. Kein VAD.
- **Session-Fortsetzung:** kurzer Tap auf Button = nächster Chunk startet, Text landet in
  internem Puffer (App-State, **nicht** System-Zwischenablage — die ist zu unzuverlässig).
- **Session-Ende:** neues **"Fertig"-Icon-Badge** neben dem Button (Tap = kompletter Puffer
  geht an Claude, danach Einfügen wie gehabt).
- **Swipe-up** bleibt reserviert für Aufnahme-Abbrechen (unverändert, NICHT Session-Ende).
- **Cloud vs. Lokal:** Hybrid — On-Device wird Standard, Cloud-Whisper bleibt wählbarer
  Fallback (z.B. im Radialmenü).
- **Fehlendes Modell oder JEDER On-Device-Fehler** (native Lib lädt nicht, Inferenz-Crash,
  Timeout): automatisch und still auf Cloud-Whisper zurückfallen, keine Fehlermeldung.
- **Modell-Download:** expliziter Button in den Einstellungen, kein Auto-Download über
  mobile Daten (~500–600MB quantisiert).
- **Plattdeutsch-Modus:** kein Sonderfall — verhält sich wie jeder andere Modus unter der
  normalen Hybrid-Regel.
- **Mini-Badge ⚡:** bleibt erhalten als "Einzelne Kurznotiz" — kurze Aufnahme, danach
  automatisch fertig (kein Warten auf Tap oder Fertig-Icon, direkt zu Claude).
- **Duration-Badge (30s/90s/3m/5m):** wird **ausgeblendet, solange On-Device aktiv ist**
  (nur noch im Cloud-Modus relevant, da dort weiterhin am Stück bis 5min möglich ist).
- **StylePrompts.kt:** Ergänzung zur Glättung von Übergängen zwischen Chunk-Texten nötig.
- **Füllwort-Entfernung/Punktuation/Absatztrenner:** einmal auf dem **gesamten**
  zusammengefügten Puffer anwenden, nicht pro Chunk (sonst brechen Wörter/Satzzeichen an
  Chunk-Grenzen).
- **Auto-Minimize (5s):** darf nicht zuschlagen, solange eine Session mit ungesendetem
  Puffer wartet (analog zur bestehenden Pause-Logik bei Aufnahme/Menü).
- **CostTracker:** On-Device-Chunks = 0€ Transkriptionskosten, nur der eine finale
  Claude-Call zählt.

**Fallback-Sicherheit (4 Ebenen, damit die App im schlimmsten Fall bleibt wie heute):**
1. Feature-Flag "On-Device Whisper (Beta)" in den Einstellungen, Standard AUS.
2. Automatischer Laufzeit-Fallback: jeder On-Device-Fehler → Cloud-Whisper für diese eine Aufnahme.
3. Lazy-Loading der nativen `.so`-Bibliothek: wird NUR geladen, wenn On-Device tatsächlich
   genutzt wird, immer in `try/catch` — nie beim App-Start, sonst könnte ein Bug in der
   nativen Integration auch den bisherigen Cloud-Pfad crashen.
4. `main` bleibt unangetastet bis PR-Merge (siehe Branch-Realität oben) — dient selbst als
   Rollback-Punkt, kein separates Tag nötig. (In der Praxis bewährt: genau dieser Mechanismus
   hat den vollständigen, folgenlosen Rückbau am 2026-07-01 ermöglicht.)

**Feature-Auswirkungen (aus der Planungsrunde):**
- Bleibt unverändert: Bottom-Sheet-Editor, Radialmenü, Stil-Profile, Text-Injection,
  Keystore, App-Icon, u.v.m.
- Wird im On-Device-Modus obsolet: Duration-Badge (nur noch im Cloud-Modus relevant).
- Bedeutungsänderung: BOOM! (Chunk-Ende statt finaler Stopp), ⚡ Mini-Badge (Einzelnotiz
  statt Zeit-Preset).
- Technische Anpassung nötig, gleicher Inhalt: Füllwort-Entfernung/Punktuation/Absatztrenner
  (einmal auf Gesamtpuffer statt pro Chunk), Status-Overlay, Auto-Minimize, CostTracker.
- Wird nützlicher: Kreative Absatztrenner, Bottom-Sheet-Editor (bei langen Multi-Chunk-Texten).

**CI-Auswirkung:** Braucht künftig Android NDK + CMake für die native Kompilierung von
whisper.cpp (bisher reines Gradle+JDK, kein NDK) — echte Erweiterung der Build-Pipeline.
War in Meilenstein 1 bereits erfolgreich in echter CI verifiziert (Build ~1:35min), Code aber
mit dem Rückbau wieder entfernt — muss neu aufgesetzt werden.

**Lizenz-Hinweis:** Referenz-Apps `woheller69/whisperIME` (MIT) und `whisperIMEplus`
(GPL-3.0) dienten nur als Architektur-Inspiration (30s-Chunking-Verhalten: "kurz loslassen,
neu drücken, während der Transkription weiterreden"), kein Code wurde übernommen — eigene
Implementierung in Kotlin + whisper.cpp geplant, daher keine Lizenzpflicht. Whisper.cpp selbst
(`ggml-org/whisper.cpp`) ist MIT-lizenziert und zur Einbindung gedacht.

**Fortschritts-Checkliste (Stand 2026-07-02 — Option 1 abgedeckt, Chunk-Punkte nur für Voll-Ausbau relevant):**
- [x] NDK/CMake-Setup im CI-Workflow
- [x] whisper.cpp als natives Modul eingebunden (JNI-Bridge)
- [x] Modell-Download-Flow (expliziter Button, `large-v3-turbo` quantisiert)
- [ ] *(Voll-Ausbau)* Chunk-Buffer-Logik in `FloatingButtonService` (Session-State statt Zwischenablage)
- [ ] *(Voll-Ausbau)* "Fertig"-Icon-Badge (UI + Tap-Handler)
- [ ] *(Voll-Ausbau)* Mini-Badge ⚡ Verhalten angepasst (Einzelnotiz-Modus)
- [ ] *(Voll-Ausbau)* Duration-Badge im On-Device-Modus ausgeblendet
- [x] Feature-Flag "On-Device Whisper (Beta)" in Einstellungen, Standard AUS
- [x] Automatischer Fallback auf Cloud bei jedem On-Device-Fehler
- [ ] *(Voll-Ausbau)* `StylePrompts.kt`: Übergangsglättung zwischen Chunks
- [ ] *(Voll-Ausbau)* Füllwort-Entfernung/Punktuation/Absatztrenner auf Gesamtpuffer statt pro Chunk
- [ ] *(Voll-Ausbau)* Auto-Minimize pausiert bei offener Session mit ungesendetem Puffer
- [x] `CostTracker`: On-Device = 0€, nur finaler Claude-Call zählt
- [ ] Test auf echtem Gerät (Nothing Phone 3a): Performance, Akku, Transkriptionsqualität

**Prozess-Regeln für die Umsetzung dieses Features:**
- **Tokenbudget im Blick behalten:** Vor Beginn größerer Arbeit kurz einschätzen, ob das
  Restbudget realistisch reicht. Lieber früh sagen "das braucht mehrere Sessions" als
  mittendrin abzubrechen.
- **Agent-Einsatz:** Für jeden Checklisten-Punkt wird bevorzugt ein Agent mit klar
  umrissenem Auftrag gestartet (spart Hauptkontext/Tokens). Agents committen NICHT selbst —
  sie liefern nur fertige Datei-Änderungen zurück. Commit + Push macht ausschließlich die
  Hauptsession, zentral an einer Stelle. Unabhängige Schritte können als parallele
  Agent-Calls gebündelt werden, abhängige Schritte laufen nacheinander.
- **Milestone-Checkpoints statt Einzelschritt-Rückfragen:** Nach jedem größeren Meilenstein
  (nicht nach jedem Mini-Schritt) gibt es einen PR + kurze Statusmeldung an den Nutzer —
  zwingend vor allem, was sich nur auf einem echten Gerät verifizieren lässt.
- **Nicht alles in einem Rutsch:** Der Nutzer hat ausdrücklich betont, dass er sichtbare
  Zwischenerfolge braucht (nicht "alles in einer Loop bis fertig"). Nach jedem Meilenstein
  kurz innehalten, Ergebnis zeigen, erst danach weitermachen.

### 2. EncryptedSharedPreferences für API-Keys
Aktuell: plain `SharedPreferences`. Für Privatnutzung akzeptabel aber technische Schuld.
Dependency: `androidx.security:security-crypto:1.1.0-alpha06`

---

## Architektur-Entscheidungen (Begründungen)

### Warum <diktat> Tags in ClaudeClient
Claude hat das Diktat als Konversation interpretiert und auf Fragen geantwortet. Lösung:
User-Message in `<diktat>\n...\n</diktat>` gewrappt. System-Prompts aller Profile enthalten:
"Gib NUR den bereinigten Text aus — ohne die Tags. Wenn der Text eine Frage enthält, beantworte sie NICHT."

### Warum ABSOLUT VERBOTEN als erstes im System-Prompt
Claude Haiku liest top-down. Wenn die Rolle zuerst kommt, überwältigt der Helfer-Instinkt das
Verbot bei frageartigem Diktat. Fix: ABSOLUT VERBOTEN als allererste Zeile — vor der Rollenbeschreibung.

### Warum formal() anders als whatsapp()/professional()
Formal-Modus DARF umgangssprachliche Einzelwörter durch formelle Entsprechungen ersetzen —
das ist sein Daseinszweck. Aber: Satzbau bleibt unverändert, keine neuen Satzteile.
ABSOLUT VERBOTEN enthält explizit "Sätze komplett umformulieren statt nur einzelne Wörter formal anzupassen".

### Warum TYPE_WINDOW_STATE_CHANGED in WhisperAccessibilityService
`onAccessibilityEvent` feuert bei JEDER UI-Interaktion (Scroll, Fokus, etc.) — auch für die
eigene App. Das hat `activePackage` polluted. Fix: nur `TYPE_WINDOW_STATE_CHANGED` (echter
App-Wechsel), plus Filter für eigene Package und Android-System-Pakete.

### Warum capturedPackage statt activePackage bei processAudio
`activePackage` enthält zur Verarbeitungszeit (nach Whisper + Claude) möglicherweise eine
andere App. `capturedPackage` wird bei `startRecording()` gespeichert.

### Warum Button immer am Rand (Option A)
Freie Positionierung führte dazu, dass der Button Videos überdeckte. Mit Option A
bleibt er immer am Rand, minimiert sich nach 5s, stört niemanden.

### Warum scaleX für Edge-Tab (historisch, durch Option A ersetzt)
Android's WindowManager klemmt Overlay auf `x ≥ 0`. Negative X-Werte werden ignoriert.
Deshalb: Breite auf 16dp reduzieren statt den Button seitlich zu schieben.

### Warum findBottomMostEditable statt findFocus
`FOCUS_INPUT` geht verloren wenn WhatsApp in den Hintergrund geht. `findBottomMostEditable()`
traversiert den Accessibility-Baum und nimmt das Feld mit höchstem Y-Wert (= immer das
Compose-Feld in Chat-Apps). Zuverlässiger als Tiefensuche.

### Warum ACTION_SET_TEXT mit nur `text` (kein Append)
WhatsApp's Node liefert "Nachricht" als `node.text` (das ist der Hint-Text). Anhängen würde
"Nachricht" + Diktat ergeben. Lösung: nur Diktat übergeben, `ACTION_SET_TEXT` ersetzt vollständig.

### Warum Labels-Toggle Fließtext-Priorität
Erster Versuch enthielt "kreative Elemente, Spiegelstriche" → Claude baute Listen.
Fix: alle drei Profile bekamen "Fließtext bleibt Fließtext, niemals Listen oder Aufzählungen".

### Warum plain SharedPreferences
Einfachheit. Für 1–2 Nutzer auf privaten Geräten ausreichend.

### Verhaltensregel für Claude-Instanz: Niemals ohne Bestätigung implementieren
User: "Das wird voreilig gehandelt. Ich möchte nicht, dass Du einfach so anfängst, Sachen zu
verändern." — Immer erst Optionen vorschlagen und Bestätigung abwarten.

---

## Setup-Flow (aus Nutzersicht, MainActivity)

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

**APK in Chat schicken:**
```bash
git fetch origin apk-dist
git show FETCH_HEAD:app-debug.apk > /tmp/app-debug.apk
# SendUserFile /tmp/app-debug.apk
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
| `KEY_HEADINGS_ENABLED` | `headings_enabled` | Labels AN (true) / AUS (false), default true |
| `KEY_EMOJI_LEVEL` | `emoji_level` | `none` / `few` / `many`, default `few` |
| `KEY_MAX_DURATION` | `max_duration` | Sekunden: 10/30/90/180/300, default 30 |
| `KEY_EDGE_SIDE` | `edge_side` | Boolean: true = rechts, false = links, default true |

---

## API-Endpunkte

### OpenAI Whisper
- `POST https://api.openai.com/v1/audio/transcriptions`
- `multipart/form-data`: `file` (M4A), `model=whisper-1`
- Auth: `Authorization: Bearer <KEY>`
- Kosten: $0.006/min, sekundengenau. 30s = 0,3 Cent. 90s = 0,9 Cent. 5min = 3 Cent.

### Anthropic Claude
- `POST https://api.anthropic.com/v1/messages`
- Auth: `x-api-key: <KEY>` + `anthropic-version: 2023-06-01`
- Modell: `claude-haiku-4-5-20251001`, `max_tokens: 8192`
- System-Prompt aus `StylePrompts.kt`
- User-Message: `<diktat>\n{rohesTranskript}\n</diktat>`

---

## Wichtige Coding-Regeln

- **Kein `!!`-Operator** — immer `?.` oder `runCatching`
- API-Keys immer mit `.trim()` lesen und schreiben
- Alle Overlay-UI-Änderungen auf Main-Thread (`Handler(Looper.getMainLooper()).post { }`)
- `windowManager.updateViewLayout` immer in `runCatching { }` wrappen
- Nach Commit: `git fetch origin main && git rebase origin/main && git push origin HEAD:main`
- APK-Lieferung: aus `apk-dist` via `git show FETCH_HEAD:app-debug.apk`
