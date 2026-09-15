---
name: release
description: Cut a Sony GPS Link release — bump version, commit, push main, watch the CI build and verify the GitHub release. Use when the user says "Release bauen", "Release schneiden", "neue Version veröffentlichen" or asks how a release works.
---

# Release cutten

Releases werden **nicht lokal gebaut**. Die signierte APK entsteht in GitHub Actions,
sobald `release.version` auf `main` geändert wird. Der Signing-Keystore liegt nur als
Secret in CI. Ein lokaler Build dient ausschließlich der Vorprüfung.

## Ablauf

1. **Voraussetzungen prüfen**
   - Branch `main`, Working Tree sauber, `git status -sb` zeigt kein „ahead“/„behind“.
   - Alle Feature-Änderungen sind bereits committet, idealerweise mit einem Eintrag
     unter `## [Unreleased]` in `CHANGELOG.md` im selben Commit.
   - Nie Feature-Änderungen mit dem Release-Commit mischen.

2. **Lokale Vorprüfung** (Pflicht, weil CI auf `main` sonst erst beim Release baut)
   ```
   ./gradlew assembleDebug lintDebug --no-daemon -q
   ```
   Lint-**Fehler** brechen den Build ab; Warnungen sind erlaubt. Ein Fehler in
   `NewApi` heißt meist: API-Aufruf oberhalb `minSdk = 26` ohne Versionsprüfung.
   Toolchain-Hinweise (JDK 21, SDK-Pfad) stehen im Memory `sonygps-local-build-toolchain`.

3. **Version bestimmen**
   - Aktuelle Version: `versionName` in `app/build.gradle.kts` und Tag in `release.version`.
   - Neue Funktion oder Verhaltensänderung → Minor (`0.5.0` → `0.6.0`).
   - Reiner Bugfix ohne neues Verhalten → Patch (`0.6.0` → `0.6.1`).
   - `versionCode` immer um 1 erhöhen; der In-App-Updater vergleicht `versionName`,
     Android den `versionCode`.

4. **Changelog schreiben** (Pflicht — ohne Abschnitt bricht der Release-Workflow ab)
   - In `CHANGELOG.md` einen Abschnitt `## [X.Y.Z] - JJJJ-MM-TT` direkt unter
     `## [Unreleased]` anlegen; dort gesammelte Einträge hineinziehen.
   - Der Abschnitt wird 1:1 zum Release-Text auf GitHub **und zum Text im
     Update-Dialog der App** (erste 1500 Zeichen). Er ist also die Nutzerkommunikation,
     nicht ein Commit-Log.
   - Aus Nutzersicht schreiben: Was ändert sich beim Fotografieren, nicht welche Klasse
     oder Methode. Wichtigstes zuerst. Kategorien **Neu / Geändert / Behoben / Entfernt**,
     leere weglassen. Bugfixes nennen das sichtbare Symptom („Kamera verlor im Akku-Modus
     bei ausgeschaltetem Display die Position“), damit Betroffene sich wiedererkennen.
   - Am Dateiende die Link-Referenzen ergänzen: `[X.Y.Z]: …/compare/vALT...vX.Y.Z` und
     `[Unreleased]` auf `vX.Y.Z...HEAD` umstellen.
   - Gegenprobe der Extraktion, muss den Abschnitt ausgeben:
     ```
     awk -v v="X.Y.Z" '/^## \[/ { if (found) exit; found = (index($0, "## [" v "]") == 1); next } /^\[.*\]: / { if (found) exit } found { print }' CHANGELOG.md
     ```

5. **Version bumpen** — genau drei Stellen, sonst nichts:
   - `app/build.gradle.kts`: `versionCode` +1, `versionName = "X.Y.Z"`
   - `release.version`: letzte Zeile auf `vX.Y.Z` (Zeilen mit `#` bleiben)
   ```
   sed -i -e 's/versionCode = 6/versionCode = 7/' -e 's/versionName = "0.5.0"/versionName = "0.6.0"/' app/build.gradle.kts
   sed -i 's/^v0.5.0$/v0.6.0/' release.version
   ```

6. **Committen und pushen**
   ```
   git add CHANGELOG.md app/build.gradle.kts release.version
   git commit -m "Cut release vX.Y.Z"
   git push origin main
   ```
   Der Push löst den Workflow „Release APK from version file“ aus. Er liest den Tag
   aus `release.version`, ruft `release.yml` auf, baut mit JDK 17, signiert, extrahiert
   den Changelog-Abschnitt als Release-Text, legt Tag und GitHub-Release an und hängt
   `app-release.apk` an.

7. **CI beobachten und Release verifizieren**
   ```
   gh run list --limit 3
   gh run watch <run-id> --exit-status
   gh release view vX.Y.Z --json tagName,url,assets
   ```
   Fertig ist das Release erst, wenn `gh release view` den Tag und ein Asset
   `app-release.apk` zeigt. Dem Nutzer die Release-URL nennen.

## Alternative Auslöser

- Tag pushen: `git tag vX.Y.Z && git push origin vX.Y.Z` startet `release.yml` direkt.
  Nur nutzen, wenn `release.version` aus irgendeinem Grund nicht geändert werden soll.
- Manuell: `gh workflow run release.yml -f tag=vX.Y.Z` (workflow_dispatch), z. B. um
  ein fehlgeschlagenes Release für einen bestehenden Tag neu zu bauen.

## Wenn CI fehlschlägt

- `gh run view <run-id> --log-failed` zeigt den Fehler.
- Signing-Fehler (`SIGNING_*` leer) → Secrets im Repo prüfen, nicht im Code fixen.
- „CHANGELOG.md has no section“ → Abschnitt für den Tag fehlt. Nachtragen, committen,
  pushen und den Workflow neu anstoßen: `gh workflow run release.yml -f tag=vX.Y.Z`
  (ein Push ohne Änderung an `release.version` startet ihn nicht erneut). Der Tag
  wurde noch nicht angelegt, die Version kann also bleiben.
- Compile-/Lint-Fehler → Fix als eigener Commit, dann **neuen** Patch-Release cutten.
  Ein bereits publizierter Tag wird nicht verschoben.

## Nach dem Release

Installierte Apps prüfen einmal täglich GitHub und bieten das Update selbst an;
Debug-Builds können die Release-APK wegen anderer Signatur nicht überinstallieren.
