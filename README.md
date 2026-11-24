<p align="center">
  <img src="app/src/main/res/drawable/ic_app.xml" alt="Groq Android JMK Edition" width="80" />
</p>

<h1 align="center">Groq Android JMK Edition</h1>

Minimale Android-App (Tastatur und schwebende Mikrofon-Bubble) für Spracheingabe über den Online-Dienst von Groq (Whisper-API und optionale LLM-Nachbearbeitung). Die App erfordert einen Groq-Account und einen API-Key auf [console.groq.com](https://console.groq.com).

## Fork-Hinweis

Dieses Projekt ist ein Fork des Original-Repositories [brvale97/GroqAndroid](https://github.com/brvale97/GroqAndroid).

## Unterschiede zum Original

- **LLM-Nachbearbeitung:** Optionale Textbereinigung über Groq Chat Completions (`qwen/qwen3.8-27b`, Fallbacks `openai/gpt-oss-120b`, `openai/gpt-oss-20b`) inklusive Modell- und Prompt-Einstellungen.
- **Overlay-Bubble & Einfügen:** Dynamische Deckkraft (20 % im Ruhezustand, 100 % bei Fokus/Aufnahme), `ACTION_PASTE` für Nextcloud Notes, Randabstand und `AudioTrack`-Signaltöne.
- **Networking:** Certificate Pinning in `GroqApiClient` entfernt.
- **Build & Tests:** `gradlew.bat` für Windows, Version `0.1.0` (`versionCode = 2`), Unit-Tests (`GroqApiClientTest.kt`).

## Einrichtung

1. Groq-API-Key auf [console.groq.com](https://console.groq.com) erstellen.
2. App mit `.\gradlew.bat assembleDebug` bauen.
3. API-Key in der App eintragen.
4. Tastatur und Accessibility-Dienst in den Android-Einstellungen aktivieren.

## Dokumentation

- [Projektübersicht](projektinfo.md)
- [Aktueller Stand](status.md)
- [Bubble- und Setup-Dokumentation](docs/DOKUMENTATION_BUBBLE_FIX.md)
