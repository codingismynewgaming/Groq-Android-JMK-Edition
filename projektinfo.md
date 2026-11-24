# Projektinfo

## Zweck
GroqAndroid ist eine Android-App mit Spracheingabe-Tastatur und frei platzierbarer Mikrofon-Bubble. Gesprochene Sprache wird über die Groq Whisper API in Text umgewandelt und in das aktive Eingabefeld eingefügt.

## Technologien
- Kotlin, Android Gradle Plugin, Gradle Kotlin DSL
- Android minSdk 26, targetSdk 35
- JDK 17 und Android SDK 35
- Groq Whisper API, OkHttp, Kotlin Coroutines
- Audioaufnahme: AudioRecord, 16 kHz, mono, PCM 16-bit

## Architektur
- `app/src/main/java/com/groqandroid/GroqIME.kt`: Eingabetastatur (IME)
- `app/src/main/java/com/groqandroid/TranscriptionOverlayService.kt`: Mikrofon-Bubble, Accessibility-Service und Einfügen
- `app/src/main/java/com/groqandroid/GroqApiClient.kt`: Groq-Transkriptionsanfragen
- `app/src/main/java/com/groqandroid/AudioRecorder.kt`: Mikrofonaufnahme und WAV-Datei
- `app/src/main/java/com/groqandroid/SettingsActivity.kt`: Einstellungen
- `app/src/main/java/com/groqandroid/PermissionActivity.kt`: Laufzeitberechtigungen

Beim Einfügen nutzt die Bubble für Nextcloud Notes (`it.niedermann.owncloud.notes`) `ACTION_PASTE`, um lange Notizen nicht komplett zu ersetzen. Andere Apps verwenden `ACTION_SET_TEXT` mit Clipboard-Paste als Rückfall.

Die Bubble hat im Ruhezustand 20 Prozent Deckkraft. Bei fokussiertem Eingabefeld oder während Aufnahme und Verarbeitung wird sie voll deckend angezeigt.

## Bauen und Installieren
In PowerShell aus dem Projektstamm:

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat --offline assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Die am 27.10.2026 installierte Debug-APK liegt lokal unter `builds/GroqAndroid-2026-10-27-debug.apk`. Die erzeugten Build-Verzeichnisse wurden anschließend bereinigt und entstehen beim nächsten Build neu.

Eine Installation auf dem Handy erfolgt nur nach ausdrücklichem Auftrag.

Gradle legt Build-Ausgaben unter `app/build/` ab. Die Verzeichnisse `builds/` und `temp/` sind für manuell abgelegte, projektbezogene Artefakte beziehungsweise temporäre Dateien vorgesehen; generierte Build-Ausgaben bleiben in Gradles Standardpfad.

## Projektordner
- `docs/`: Projektdokumentation
- `scripts/`: Hilfsskripte
- `temp/`: temporäre Projektdateien
- `ressourcen/`: Quellen und ergänzende Ressourcen
- `builds/`: manuell abgelegte Build-Artefakte
- `backups/`: lokale Sicherungen, nicht automatisch veröffentlichen
- `docs/tasks/`: Projektlearnings
