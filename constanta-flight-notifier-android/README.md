# Constanța Radar CND — Aplicație Nativă Android

Aplicație nativă Android (Kotlin, Android 8.0+ / API 26–35) care monitorizează **direct de pe telefon, 24/7 în fundal** (fără să ai nevoie de un calculator/server pornit!), toate aterizările și decolările de pe **Aeroportul Internațional „Mihail Kogălniceanu” Constanța (CND / LRCK)**.

## Funcționalități

- **Monitorizare reală în fundal (Android Foreground Service)**: rulează continuu chiar și când ecranul este stins, cu o notificare silențioasă de stare și pornire automată opțională după repornirea telefonului (`BootReceiver`).
- **Date ADS-B live gratuite, fără cheie API**: failover automat `airplanes.live` → `adsb.lol` → `adsb.fi` → `OpenSky Network` + completare rută/aeronavă din `adsbdb.com`.
- **Notificări Android native de prioritate mare** (canale separate pentru **Aterizări**, **Decolări** și **Apropiere finală**), cu buton direct **„Vezi pe hartă”** (ADS-B Exchange).
- **Radar nativ LRCK (Pista 18/36)** desenat direct pe `Canvas`, cu țărmul Mării Negre, Constanța, Mamaia, Năvodari, Medgidia și mod **Simulare aterizare / decolare** pentru testare imediată.

## Cum obții fișierul `.apk` pentru instalare pe telefon

### Varianta 1 — Fără să instalezi Android Studio (prin GitHub Actions, ~2 minute)
1. Creează un repository (poate fi și **Privat**) pe GitHub și încarcă în el conținutul acestui folder (`constanta-flight-notifier-android`).
2. Intră în tab-ul **Actions** al repository-ului → workflow-ul **Build Android APK** pornește automat (sau apasă **Run workflow**).
3. După ~1–2 minute, descarcă arhiva **`ConstantaRadarCND-debug-apk`** din secțiunea **Artifacts**, copiază `app-debug.apk` pe telefon și instalează-l (activează *„Instalare din surse necunoscute”* pentru browser/managerul de fișiere).

### Varianta 2 — Local în Android Studio
1. Deschide folderul `constanta-flight-notifier-android` în **Android Studio** (Ladybug / Jellyfish sau mai nou, JDK 17).
2. Așteaptă sincronizarea Gradle.
3. Din meniul de sus: **Build → Build Bundle(s) / APK(s) → Build APK(s)** (sau din terminal: `gradle assembleDebug`).
4. Fișierul rezultat este `app/build/outputs/apk/debug/app-debug.apk`.

## Sfat pentru notificări neîntrerupte pe Android (Samsung / Xiaomi / Huawei)

Pentru ca Android să nu pună aplicația în hibernare pe timpul nopții, după instalare intră în **Setări → Aplicații → Constanța Radar CND → Baterie** și selectează **Fără restricții (Unrestricted)**.
