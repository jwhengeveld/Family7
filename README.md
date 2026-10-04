# Family7 Android TV App 📺✝️

[![Android CI](https://github.com/jwhengeveld/Family7-Android-TV/actions/workflows/android-build.yml/badge.svg)](https://github.com/jwhengeveld/Family7-Android-TV/actions/workflows/android-build.yml)
[![Release](https://img.shields.io/github/v/release/jwhengeveld/Family7-Android-TV?color=orange&label=Latest%20APK)](https://github.com/jwhengeveld/Family7-Android-TV/releases/latest)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-purple.svg)](https://kotlinlang.org)
[![Compose TV](https://img.shields.io/badge/Jetpack%20Compose-TV%201.0.0-blue.svg)](https://developer.android.com/jetpack/compose/tv)
[![Media3](https://img.shields.io/badge/AndroidX-Media3%20ExoPlayer-green.svg)](https://developer.android.com/media/media3)

Een moderne, native **Android TV / Google TV** applicatie voor [Family7](https://www.family7.nl/), ontwikkeld in **Kotlin** met **Jetpack Compose for TV**, **Material 3**, en **AndroidX Media3 (ExoPlayer)**.

De app biedt volledige ondersteuning voor zowel **Live TV** (Family7 Plus livestream) als de complete **On Demand** videotheek met alle programma's, seizoenen, afleveringen en zoekfunctie.

---

## 📸 Schermafbeeldingen (Screenshots)

| Inlogscherm (Drupal Auth) | On Demand Startscherm |
|---|---|
| ![Inlogscherm](docs/screenshots/01_login.png) | ![On Demand Startscherm](docs/screenshots/02_home.png) |

| Programmadetails & Afleveringen | On Demand Video Playback (HD HLS) |
|---|---|
| ![Programmadetails](docs/screenshots/03_detail.png) | ![Video Player](docs/screenshots/04_ondemand_player.png) |

| Live TV (Family7 Plus Uitzending) | A-Z Videotheek & Zoeken |
|---|---|
| ![Live TV Uitzending](docs/screenshots/05_livetv_player.png) | ![A-Z Videotheek](docs/screenshots/06_search_az.png) |

---

## ✨ Functionaliteiten

- 🔴 **Live TV Uitzending (Family7 Plus)**
  - Directe HLS-streamweergave via de Streampartner streaming backend.
  - Elektronische Programmagids (EPG) overlay met huidige en volgende programma's.
  - Automatische kwaliteitsselectie (tot 1080p Full HD) met minimale latentie.
- 🎬 **On Demand Videotheek**
  - Featured Hero Banner met uitgelichte programma's.
  - Dynamische rijen ("Aanbevolen", "Mijn lijst", "Originals", "Documentaires", "Bijbelstudie", etc.).
  - Programmadetailpagina's met synopsis, seizoenen, afleveringsoverzichten en speelduur.
- 🧒 **Kids-sectie**
  - Eigen ingang naar de Family7+ specialpagina met kinderprogramma's.
  - Het adres van die pagina wordt in het menu van Family7+ opgezocht, dus een
    hernoeming of verhuizing aan de kant van Family7 gaat vanzelf mee.
- 🔖 **Mijn lijst (dezelfde lijst als op de site)**
  - Gekoppeld aan Family7 zelf: de app leest /plus/mijnlijst en gebruikt hetzelfde
    eindpunt als de knop op de website, dus wat u hier bewaart staat ook op
    family7.nl en op uw andere apparaten.
  - Verschijnt als eerste rij op het startscherm en als eigen overzicht in de zijbalk.
- 🔍 **A-Z Catalogus & Zoeken**
  - Snel alfabetisch filteren op alle beschikbare programma's, inclusief
    vervolgpagina's, zodat nieuwe titels er automatisch bij komen.
  - Real-time zoekfunctie op titel en thema.
- 📡 **Volledig dynamisch, niets vastgezet in de code**
  - Rijen, programma's, afleveringen en specials komen rechtstreeks van
    family7.nl; er staat geen catalogus in de app.
  - De live speler-URL wordt van de livepagina zelf gelezen. De laatst werkende
    speler- en stream-URL worden onthouden als noodgreep, in plaats van een
    vaste URL die veroudert zodra Streampartner van host wisselt.
- 🎮 **Geoptimaliseerd voor TV Afstandsbediening (D-Pad)**
  - Vloeiende focus-indicatoren met schaalvergroting (1.06x) en Family7-rode accenten uit het logo.
  - Leanback launcher compatibel voor Android TV, Google TV, en smart home portals (zoals Meta Portal Go).
- ▶️ **Standaard Android TV mediabediening**
  - De officiele Media3 `PlayerView`-bediening: een druk op OK of een tik brengt de
    bediening in beeld, OK speelt/pauzeert, links/rechts spoelt 10 s terug / 30 s vooruit,
    en na 5 seconden verdwijnt de bediening weer.
  - Een `MediaSession` registreert de weergave bij het systeem, zodat "Now playing",
    de mediabalk en de mediatoetsen van de afstandsbediening de app aansturen, met de
    juiste titel, programmanaam en omslagafbeelding.
  - Het scherm blijft aan tijdens het afspelen en de weergave pauzeert bij het
    verlaten van de voorgrond.
- 🎨 **Merkidentiteit**
  - Het Family7 logo is als schaalbare vector opgenomen (`art/family7_logo.svg` en
    `art/family7_mark.svg`, plus de VectorDrawables in `res/drawable/`) en wordt gebruikt
    voor het startscherm, het inlogscherm, de zijbalk, de speler, het app-icoon
    (adaptief, inclusief monochrome variant) en de Android TV banner.
- ⏳ **Laadschermen met plaatshouders**
  - Startscherm, programmapagina, zoeken, kids en mijn lijst tonen tijdens het
    laden de uiteindelijke indeling in plaats van een leeg vlak.
- ⚡ **Slim cachen — geen laadscherm bij elke stap**
  - De catalogus wordt in het geheugen bewaard, dus terugkeren naar een scherm
    toont meteen de vorige inhoud in plaats van opnieuw een laadscherm.
  - Stale-while-revalidate: bestaande inhoud direct tonen en stil op de
    achtergrond verversen.
  - Het startscherm ververst zichzelf elke 10 minuten zolang het zichtbaar is,
    en meteen bij terugkeer op de voorgrond, zodat nieuwe programma's vanzelf
    verschijnen zonder dat u iets merkt.
  - Een OkHttp disk-cache maakt een koude start en het verversen sneller en
    overleeft procesdood.
- 🔐 **Veilige Authenticatie**
  - Drupal authenticatie met sessie- en cookiebeheer.
  - Het wachtwoord wordt nergens bewaard. De aanmeldsessie (de cookie van
    family7.nl) gaat versleuteld met AES-256-GCM naar schijf, met een sleutel
    uit de AndroidKeyStore die het toestel niet verlaat, en blijft buiten
    back-ups en toestelmigratie.
  - Nog geen account? Het inlogscherm toont een QR-code naar het inschrijfformulier
    van Family7 Plus (€ 3 per maand, eerste 10 dagen gratis) - handiger dan een
    webadres overtypen met de afstandsbediening.

---

## 📱 Telefoonapp met Chromecast (`mobile/`)

Naast de TV-app bevat dit project een app voor Android-telefoons en -tablets
(`nl.family7.mobile`). Die deelt de complete datalaag met de TV-app (zie
*Projectindeling*), dus inloggen, de catalogus, Mijn lijst en de live- en
on-demandstreams werken precies hetzelfde.

- **Navigatie voor aanraakschermen**: onderbalk met Start, Live, Zoeken en Mijn
  lijst; Kids, A-Z en de "Alles"-pagina van elke rij vanaf het startscherm.
- **Kijken op de telefoon**: Media3 met de standaardbediening, schermvullend en
  liggend, beeld-in-beeld bij het verlaten van de app, pauze als de oortjes
  eruit gaan, en mediabediening via een `MediaSession`.
- **Casten naar Chromecast en Google TV**: de Cast-knop verschijnt vanzelf zodra
  er een ontvanger in het netwerk is. Een lopende aflevering gaat over naar de
  tv op dezelfde plek; stoppen met casten zet hem gepauzeerd terug op de
  telefoon. Tijdens het casten blijft een mini-balk onderin staan terwijl u
  verder bladert, en de Cast-melding en het vergrendelscherm bedienen de tv.
  Live tv wordt als live-stream aangemeld (geen tijdbalk met eindpunt).
  - Gebruikt de *Default Media Receiver* van Google. Getest: de HLS-streams van
    Streampartner (live én on demand) sturen `Access-Control-Allow-Origin: *`
    en vragen geen cookies of Referer, dus de standaardontvanger kan ze spelen.
  - Verandert dat ooit, dan kan een eigen ontvanger uit de Cast Developer
    Console worden ingesteld zonder codewijziging:
    `./gradlew :mobile:assembleRelease -Pfamily7.castReceiverId=ABCD1234`.
  - Zonder Google Play-services (bijvoorbeeld Huawei) werkt de app gewoon,
    alleen zonder Cast-knop.

### Slim en robuust laden (beide apps)

- **Koude start zonder laadscherm**: de laatst geladen catalogus (startpagina,
  A-Z, kids) staat op schijf in de no-backup-map en vult het scherm meteen; het
  ophalen bij Family7 gebeurt daarna stil. Uitloggen wist die snapshot.
- **Stil verversen**: nieuwe inhoud verschijnt zonder spinner. Alleen een
  handmatige veeg (telefoon) toont een indicator.
- **Inhoud blijft staan bij een fout**: mislukt verversen, dan blijft de vorige
  inhoud zichtbaar met een rustige melding; een foutscherm alleen als er
  niets te tonen is.
- **Netwerk terug = vanzelf opnieuw**: de telefoonapp luistert naar de
  verbinding en probeert een mislukte lading of weergave opnieuw zodra er
  weer internet is, zonder dat u op "opnieuw" hoeft te drukken.
- **Sneller eerste beeld**: de startpagina en "Nieuw toegevoegd" worden
  tegelijk opgehaald, zoeken filtert de A-Z-lijst op het toestel, en het
  stream-adres van de eerste aflevering wordt al opgezocht als de
  programmapagina opent.
- **Afspelen herstelt zichzelf**: mislukt een stream, dan haalt de app een vers
  adres op (het token van Streampartner verloopt) en gaat verder op dezelfde
  plek, met oplopende pauzes; live tv die achterop raakt springt terug naar
  de live-rand, en segmenten krijgen meer herkansingen op mobiel internet.
- **Snel openen**: met een bewaarde sessie opent de telefoonapp direct het
  startscherm en laat Family7 de sessie op de achtergrond bevestigen.

### Projectindeling

| Module | Inhoud |
|---|---|
| `core/` | Gedeelde datalaag (`nl.family7.core.data`): HTTP-client, sessie, catalogus, Mijn lijst, live, Streampartner-uitpakker, caches, netwerkmonitor. Met unit-tests. |
| `app/` | De Android TV-app (`nl.family7.tv`), Compose for TV. |
| `mobile/` | De telefoonapp (`nl.family7.mobile`), Compose Material 3 + Google Cast. |

---

## 🔑 Release bouwen

De release-build wordt ondertekend met een eigen sleutel; de gegevens staan in
`keystore.properties` in de projectmap. Dat bestand en de keystore staan in
`.gitignore` en horen niet in de repo:

```properties
storeFile=keystore/family7-release.jks
storePassword=...
keyAlias=family7
keyPassword=...
```

Ontbreekt `keystore.properties`, dan bouwt `./gradlew assembleRelease` gewoon
door, maar levert het een niet-ondertekende APK op.

### Uitbrengen via GitHub

De sleutel staat ook als repository secrets in GitHub
(`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`), zodat een release niet van een lokale keystore afhangt.
Een tag uitbrengen is genoeg:

```bash
git tag v1.2.0 && git push origin v1.2.0
```

De workflow bouwt dan de ondertekende releases van beide apps (TV en telefoon),
controleert de handtekeningen, faalt als een APK debuggable blijkt, en zet de
APK's en bundels bij de release (`family7-androidtv-v…` en `family7-mobile-v…`). Pull requests
van forks krijgen geen secrets en bouwen alleen debug.

> **Bewaar de keystore zelf ergens buiten deze machine.** GitHub Actions secrets
> zijn write-only: er kan mee gebouwd worden, maar de sleutel is er niet meer
> uit te halen. Raakt de lokale keystore kwijt, dan kan geen enkele update meer
> over een bestaande installatie heen.

De release-build gebruikt R8 met resource shrinking (TV 2,7 MB, telefoon 4,6 MB).
De bewaarregels in `core/consumer-rules.pro` beschermen Jsoup, dat de HTML van
family7.nl leest, en gaan vanzelf mee naar beide apps.

## 🛠️ Architectuur & Tech Stack

| Component | Technologie |
|---|---|
| **Taal** | Kotlin 2.1.0 |
| **UI Framework** | Jetpack Compose for TV (TV) / Compose Material 3 (telefoon) |
| **Casten** | Google Cast SDK 21.5 + Media3 `CastPlayer`, MediaRouter-knop |
| **Video Playback** | AndroidX Media3 ExoPlayer 1.5.1 (HLS, Adaptive Streaming) |
| **Networking & HTTP** | OkHttp 4.12.0 met persistente CookieJar |
| **HTML / Scraping** | Jsoup 1.18.3 & Streampartner Recursive Unpacker |
| **Afbeeldingen** | Coil 2.7.0 (Compose AsyncImage met disk caching) |
| **Beveiliging** | AES-256-GCM met een sleutel uit de AndroidKeyStore; cookies volgens domein, pad en secure-vlag |
| **Minimaal Android OS** | Android 7.0 (API Level 24+) / TV Android 10+ |
| **Tests** | JUnit 4 op de uitpakker, de cookieregels en de cache |

---

## 🚀 Installatie & Sideloading (APK)

1. Download de nieuwste APK uit de [Releases](https://github.com/jwhengeveld/Family7-Android-TV/releases) sectie (`family7-androidtv-v1.0.0.apk`).
2. Installeer op uw Android TV of aangesloten apparaat via ADB:
   ```bash
   adb connect <IP_VAN_UW_TV>:5555
   adb install -r family7-androidtv-v1.0.0.apk
   ```
3. Start de app op via het Android TV startscherm of direct via:
   ```bash
   adb shell am start -n nl.family7.tv/.MainActivity
   ```
4. De telefoonapp gaat net zo, met `family7-mobile-v….apk` en
   `adb shell am start -n nl.family7.mobile/.MainActivity`.

---

## 💻 Zelf Bouwen (Build from Source)

Vereisten: **Android Studio Meerkat / Ladybug** of **JDK 17+** en Android SDK 35.

```bash
git clone https://github.com/jwhengeveld/Family7-Android-TV.git
cd Family7-Android-TV

# Compileer de debug APK
./gradlew assembleDebug

# De APK's zijn te vinden in:
# app/build/outputs/apk/debug/app-debug.apk        (TV)
# mobile/build/outputs/apk/debug/mobile-debug.apk  (telefoon)

# Draai de tests
./gradlew testDebugUnitTest

# Een bundel voor de winkel (in plaats van een APK)
./gradlew bundleRelease
# app/build/outputs/bundle/release/app-release.aab
```

> Op macOS wordt de build-map omgeleid naar `~/.builds/Family7AndroidTV`,
> omdat externe exFAT-schijven de tussenbestanden van Gradle beschadigen.
> Zoek de APK en de bundel daar als het project op zo'n schijf staat.

---

## 📄 Status, merk en licentie

Dit is een **onafhankelijk gemaakte app, geen officiële uitgave van Family7**.
De naam Family7, het logo en alle programma's zijn eigendom van
[Family7](https://www.family7.nl/); ze worden hier gebruikt om de eigen dienst
van Family7 op de televisie te ontsluiten, niet om er iets eigens mee te
suggereren. De app toont uitsluitend content waar de ingelogde gebruiker via
zijn eigen Family7-account al recht op heeft.

De app staat daarom niet in de Play Store: publiceren daar vraagt om
toestemming van Family7 voor het gebruik van naam en logo. Zie
[docs/VOORLEGGING-FAMILY7.md](docs/VOORLEGGING-FAMILY7.md) voor de stand van
zaken en wat er nodig is om dat wel te kunnen doen, en
[docs/PRIVACY.md](docs/PRIVACY.md) voor wat de app met gegevens doet.

Verzoeken van Family7 over het gebruik van hun merk of content worden
gehonoreerd; neem daarvoor contact op via de GitHub-repository.
