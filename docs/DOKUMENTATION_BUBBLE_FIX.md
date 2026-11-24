# GroqAndroid - Bubble & Setup Fix Dokumentation

> Historische Einrichtungsnotizen. Aktueller Projektstand: [projektinfo.md](../projektinfo.md) und [status.md](../status.md).
> Seit 27.10.2026 beträgt die Deckkraft im Ruhezustand 20 Prozent; bei fokussiertem Textfeld und Aufnahme 100 Prozent.
> Nextcloud Notes nutzt jetzt `ACTION_PASTE`, um das vollständige Ersetzen langer Notizen und die damit verbundene Cursorbewegung zu vermeiden.
> Build und Installation auf dem Testgerät waren erfolgreich. Der Scrollsprung wurde danach noch nicht systematisch nachgemessen.

## Ausgangslage & Problemstellung
Auf dem Testgerät (OxygenOS) erschien nach Auswahl der Floating-Bubble in GroqAndroid kein Overlay auf dem Display. Nach manuellem Start via Accessibility war die Bubble nur als winzige Lasche ganz am rechten Rand sichtbar und reagierte nicht auf Berührungen.

---

## Ursachenanalyse

1. **Accessibility-Service nicht aktiv:**
   Der für das Overlay zuständige Dienst (`com.groqandroid/.TranscriptionOverlayService`) erfordert die Berechtigung `BIND_ACCESSIBILITY_SERVICE`. Dieser war in den Android-Bedienungshilfen (`enabled_accessibility_services`) nicht eingeschaltet.

2. **Rand-Clipping & Gesten-Interferenz (OxygenOS):**
   Die Bubble wurde standardmäßig bei `x = screenWidth - bubbleSize` bzw. über den Rand hinaus positioniert (`x=1017` bei `1080px` Displaybreite). Dadurch ragten mehr als 80 Pixel außerhalb des Displays. An dieser Randzone greift die native Android/OxygenOS Zurück-Geste, wodurch Touch-Events für das Overlay verschluckt wurden.

3. **Zu geringe Drag-Schwelle:**
   `TAP_THRESHOLD_DP` war auf nur `10dp` gesetzt. Natürliche Mikrobewegungen des Fingers beim Antippen auf dem 120Hz-Touchscreen wurden fälschlicherweise als Wischgeste (`isDragging = true`) gewertet. Beim Loslassen wurde dadurch `onBubbleTapped()` übersprungen und lediglich die Randeinrastung aufgerufen.

4. **Fehlender API-Key Fallback:**
   Die App verweigerte Aktionen ohne Feedback, wenn in den verschlüsselten SharedPreferences noch kein API-Key gespeichert war.

5. **Strikte SSL-Pinning-Hürde:**
   `GroqApiClient` hatte ein hartes Certificate-Pinning auf ein Zwischenzertifikat von Google Trust Services aktiv, das bei Zertifikatsrotationen zu Verbindungsabbrüchen führen kann.

---

## Durchgeführte Anpassungen

### 1. Build-Umgebung eingerichtet
- Microsoft OpenJDK 17 (`Microsoft.OpenJDK.17`) via `winget` installiert.
- `gradlew.bat` für Windows-Wrapper im Projektstamm korrigiert.

### 2. API-Key Fallback-Mechanismus
- Fallback-Konstante `DEFAULT_API_KEY` (leer im Repository) hinterlegt in:
  - `SettingsActivity.kt`
  - `TranscriptionOverlayService.kt`
  - `GroqIME.kt` (Tastatur-Eingabemethode)
  - `GroqApiClient.kt` (HTTP Authorization Header)

### 3. Bubble Position & Touch-Optimierung (`TranscriptionOverlayService.kt`)
- **Sicherheitsabstand zum Displayrand:**
  `edgeMargin` von 16dp eingeführt. Beim Start und beim Snappen hält die Bubble jetzt mindestens 16dp Abstand zum linken bzw. rechten Rand (`screenWidth - bubbleSize - edgeMargin`).
- **Vergrößerte Trefferfläche:**
  `bubbleSize` von 48dp auf 56dp erhöht.
- **Tolerantere Tap-Erkennung:**
  `TAP_THRESHOLD_DP` von 10dp auf 28dp angehoben, damit Taps zuverlässig als Klick gewertet werden.
- **Haptisches Feedback:**
  Sofortige kurze Vibration (`vibrate(20)`) bei `ACTION_DOWN` hinzugefügt.
- **Resilientes Networking:**
  Hartes Certificate-Pinning auskommentiert für unterbrechungsfreie Nutzung auch bei Cert-Erneuerungen.

### 4. Build & Deployment
- Debug-APK via Gradle kompiliert (`.\gradlew.bat assembleDebug`).
- Alte App deinstalliert (`adb uninstall com.groqandroid`), um veraltete Prefs und Positionsdaten zu bereinigen.
- Neue APK installiert (`adb install ...`).
- Accessibility-Service aktiviert und Settings-Intent gestartet.
- Bubble sitzt nun zentriert und frei bei den Koordinaten `[Rect(891, 912 - 1038, 1059)]`.

---

## Wichtige Befehle für die Zukunft

- **Accessibility-Service manuell reaktivieren (falls nötig):**
  ```powershell
  $current = adb shell settings get secure enabled_accessibility_services
  adb shell settings put secure enabled_accessibility_services "$current:com.groqandroid/.TranscriptionOverlayService"
  ```
- **Bubble einblenden:**
  ```powershell
  adb shell am broadcast -a com.groqandroid.SHOW_BUBBLE
  ```
- **Projekt neu bauen:**
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
  $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
  .\gradlew.bat assembleDebug
  ```

---

## Update: Dynamische Transparenz (50% / 100%)

### 1. Backup des vorherigen Standes
Der funktionierende Stand vor der Transparenz-Änderung wurde als Backup-APK gesichert unter:
- [GroqAndroid-v1-current-backup.apk](../backups/GroqAndroid-v1-current-backup.apk)

### 2. Umsetzung der dynamischen Transparenz
- **Ruhezustand:** 50% Transparenz (lpha = 0.5f), damit die Bubble beim normalen Bedienen des Handys unaufdringlich im Hintergrund bleibt.
- **Fokussiertes Textfeld:** Sobald ein aktives/editierbares Textfeld fokussiert wird (
ootInActiveWindow.findFocus(FOCUS_INPUT).isEditable), blendet sich die Bubble automatisch weich auf 100% Deckkraft (lpha = 1.0f) ein.
- **Berührung / Interaktion:** Beim Antippen (ACTION_DOWN), Aufnehmen (State.RECORDING) oder Transkribieren wird sie ebenfalls sofort voll deckend (100%).
- **Accessibility-Events:** ccessibility_service_config.xml um 	ypeViewClicked und 	ypeWindowsChanged erweitert, damit Fokuswechsel (z.B. Tastatur auf/zu, Wechsel in Messenger oder Browser) sofort erkannt werden.
