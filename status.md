# Projektstatus

Stand: 2026-10-27

Die Bubble wurde auf 20 Prozent Deckkraft im Ruhezustand gesetzt. Für Nextcloud Notes verwendet sie nun `ACTION_PASTE`, um lange Notizen an der Cursorposition einzufügen, ohne den gesamten Inhalt auszutauschen.

Der Debug-Build wurde erfolgreich erstellt und auf dem angeschlossenen Android-Testgerät installiert. Der Accessibility-Service war danach weiterhin aktiviert. Der Nutzer hat positives Feedback gegeben; das Scrollverhalten in Nextcloud Notes wurde nach der Änderung noch nicht systematisch geprüft.

Die Projektablage wurde aufgeräumt. Die aktuelle APK liegt unter `builds/GroqAndroid-2026-10-27-debug.apk`; die ältere Backup-APK bleibt in `backups/`. Die erzeugten Ordner `app/build/`, `.gradle/` und `.kotlin/` sowie die überholte Aufgabenliste wurden nach Freigabe gelöscht. Dabei wurden rund 41,5 MiB entfernt. Der nächste Build erzeugt seine Zwischenstände neu.

Vorhandene Änderungen im Arbeitsverzeichnis können bereits vor dieser Aufräumrunde bestanden haben. Vor einem Commit daher den Git-Status und Diff prüfen.
