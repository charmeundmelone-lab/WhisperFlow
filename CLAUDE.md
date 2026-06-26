# WhisperFlow — Projekt-Manifest

## Vision
WhisperFlow ist eine Android-Diktier-App. Sprache wird aufgenommen, via OpenAI Whisper
transkribiert und anschließend durch Claude aufbereitet — als strukturierte Notiz,
Zusammenfassung oder Aufgabenliste.

## Tech-Stack

| Komponente    | Wahl                                |
|---------------|-------------------------------------|
| Sprache       | Kotlin                              |
| UI            | Jetpack Compose + Material3         |
| minSdk        | 26 (Android 8.0 Oreo)              |
| compileSdk    | 35 (Android 15)                     |
| Build-System  | Gradle 8.14.3, Kotlin DSL (`.kts`) |
| Package       | `de.minitraxx.whisperflow`          |
| HTTP-Client   | OkHttp 4.x                         |
| Kein ORM      | Kein Room, kein KSP                |

## Architektur

- **Single-Activity** mit Jetpack Compose Navigation
- **MVVM-lite**: ViewModel + StateFlow/MutableStateFlow
- **Repository-Pattern** für Audio- und API-Zugriff
- Kein DI-Framework — einfache Konstruktor-Injection
- Persistenz via JSON-Datei im internen App-Speicher (kein Room)

## Modulstruktur (geplant)

```
app/src/main/java/de/minitraxx/whisperflow/
├── MainActivity.kt
├── ui/
│   ├── HomeScreen.kt          # Aufnahme-Button + Notizenübersicht
│   ├── NoteDetailScreen.kt    # Detail-Ansicht einer Notiz
│   └── theme/
│       ├── Theme.kt
│       ├── Color.kt
│       └── Type.kt
├── audio/
│   └── AudioRecorder.kt       # MediaRecorder-Wrapper
├── api/
│   ├── WhisperClient.kt       # OkHttp → OpenAI Whisper API
│   └── ClaudeClient.kt        # OkHttp → Anthropic Claude API
├── data/
│   ├── NoteRepository.kt      # CRUD für Notizen (JSON-Datei)
│   └── model/
│       └── Note.kt            # id, text, summary, tasks, timestamp
└── viewmodel/
    └── HomeViewModel.kt       # State-Management für Home
```

## Geplante Features (Reihenfolge)

1. **Audioaufnahme** — Start/Stop-UI, MediaRecorder → M4A
2. **Whisper-Transkription** — Audio-Upload, Text zurück
3. **Claude-Aufbereitung** — Notiz / Zusammenfassung / Aufgaben-Extraktion
4. **Notizliste** — Anzeige aller gespeicherten Diktate
5. **Detail & Export** — Anzeige, Kopieren, per Intent teilen
6. **Settings** — API-Keys via EncryptedSharedPreferences
7. **Offline-Modus** (optional) — lokales Whisper via ONNX Runtime

## API-Integration

### OpenAI Whisper
- Endpoint: `https://api.openai.com/v1/audio/transcriptions`
- Auth: `Authorization: Bearer <OPENAI_API_KEY>`
- Request: `multipart/form-data`, Felder `file` + `model=whisper-1`

### Anthropic Claude
- Endpoint: `https://api.anthropic.com/v1/messages`
- Auth: `x-api-key: <CLAUDE_API_KEY>` + `anthropic-version: 2023-06-01`
- Empfohlenes Modell: `claude-haiku-4-5-20251001`

## CI / Deployment

- Workflow: `.github/workflows/android-build.yml`
- Trigger: Jeder Push auf `main`
- JDK: 17 (Temurin), Gradle: 8.14.3 via `gradle/actions/setup-gradle`
- Output:
  - Debug-APK als GitHub-Artifact (30 Tage Aufbewahrung)
  - APK wird auf Branch `apk-dist` gepusht (`app-debug.apk` + `app-debug-<sha>.apk`)

## Lokale Entwicklung

```bash
# Gradle-Wrapper einrichten (erzeugt gradle/wrapper/gradle-wrapper.jar)
gradle wrapper --gradle-version 8.14.3 --distribution-type bin

# Build
./gradlew assembleDebug

# Auf Gerät installieren
./gradlew installDebug
```

## Coding-Konventionen

- Sealed Classes für UI-State (`Loading`, `Success`, `Error`)
- `Result<T>` für API-Responses — kein `!!`-Operator im Produktionscode
- Compose-State immer zum ViewModel hochziehen (State Hoisting)
- Konstanten in `companion object` oder Top-Level `val`
- Secrets niemals in VCS committen
