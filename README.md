# Family7 voor iPhone en iPad 📱✝️

Een native iOS-app voor [Family7](https://www.family7.nl/) in **SwiftUI**, met
**AirPlay** en **Google Cast**. De iOS-tegenhanger van de Android TV- en
telefoonapp ([Family7-Android-TV](https://github.com/jwhengeveld/Family7-Android-TV)):
dezelfde aanmelding, catalogus, Mijn lijst en live- en on-demandstreams, en
dezelfde manier van slim laden.

## ✨ Functionaliteiten

- **Start, Live, Zoeken en Mijn lijst** als tabbladen; Kids, A-Z en de
  "Alles"-pagina van elke rij vanaf het startscherm. Alles komt rechtstreeks
  van family7.nl; er staat geen catalogus in de app.
- **Kijken op de telefoon** met de speler van iOS zelf (AVPlayerViewController):
  beeld-in-beeld (ook automatisch bij het verlaten van de app), doorspelen met
  het scherm op slot, titel en omslag op het vergrendelscherm en in het
  bedieningspaneel, en ±10/30 seconden via de mediaknoppen.
- **AirPlay** naar een Apple TV of AirPlay-tv: via de knop in de werkbalk, in de
  speler of in het bedieningspaneel. AVPlayer stuurt de video zelf door.
- **Google Cast** naar Chromecast en Google TV:
  - Een lopende aflevering gaat over naar de tv op dezelfde plek, en stoppen
    met casten zet hem gepauzeerd terug op de telefoon.
  - Tijdens het casten blijft er onderin een mini-balk staan terwijl u verder
    bladert, en het spelerscherm wordt een afstandsbediening (tijdbalk,
    ±10/30 s, afspelen/pauzeren). De volumeknoppen van de telefoon regelen de tv.
  - Live tv gaat als live-stream naar de tv, zonder tijdbalk met eindpunt.
  - Gebruikt de *Default Media Receiver* van Google. De streams van
    Streampartner sturen `Access-Control-Allow-Origin: *` en vragen geen
    cookies of Referer (getest voor live en on demand), dus dat werkt.
  - Een eigen ontvanger kan via de build-instelling `FAMILY7_CAST_RECEIVER_ID`
    in `project.yml`; die gaat ook vanzelf in `NSBonjourServices`.
  - iOS vraagt pas om toegang tot het lokale netwerk bij de eerste tik op de
    Cast-knop, niet bij het openen van de app.
- **Deep links**: `family7://live`, `family7://programma/<slug>`,
  `family7://programma/<slug>/afspelen` en `family7://kids`.

### Slim en robuust laden

- **Koude start zonder laadscherm**: met een bewaarde sessie opent de app
  meteen het startscherm, gevuld met de catalogus van de vorige keer (op
  schijf, buiten de back-up), en laat Family7 de sessie op de achtergrond
  bevestigen. Uitloggen wist de snapshot.
- **Stil verversen** zolang het startscherm zichtbaar is (elke 10 minuten),
  zonder spinner; alleen vegen om te verversen toont een indicator.
- **Inhoud blijft staan bij een fout**, met een rustige melding; een foutscherm
  alleen als er niets te tonen is.
- **Netwerk terug = vanzelf opnieuw** (NWPathMonitor), voor schermen én speler.
- **Sneller eerste beeld**: startpagina en "Nieuw toegevoegd" tegelijk, zoeken
  filtert de A-Z-lijst op het toestel, het stream-adres van de eerste
  aflevering wordt al opgezocht als de programmapagina opent, en omslagen
  hebben een eigen cache in het geheugen en op schijf (ook offline).
- **Afspelen herstelt zichzelf**: een mislukte stream krijgt een vers adres
  (het token verloopt) en gaat verder op dezelfde plek; een Chromecast die
  blijft weigeren levert een eerlijke melding met "op telefoon kijken".
- **HTTP met herkansing**: twee extra pogingen bij een netwerkfout, en een
  bovengrens per verzoek.

### Veiligheid

Het wachtwoord wordt nergens bewaard. De aanmeldsessie (de cookies van
family7.nl) leeft in het geheugen en staat in de **Keychain**, alleen op dit
toestel en pas leesbaar na de eerste ontgrendeling (de tegenhanger van de
AndroidKeyStore-aanpak in de Android-apps). Alle verkeer gaat over HTTPS.

## 🛠️ Bouwen

Vereisten: Xcode 26 of nieuwer, en [XcodeGen](https://github.com/yonaskolb/XcodeGen)
als u `project.yml` wijzigt.

```bash
./scripts/fetch-cast-sdk.sh     # Google Cast SDK 4.8.6 (44 MB, buiten git, SHA-256 gecontroleerd)
open Family7.xcodeproj          # of: xcodegen generate (na wijzigingen in project.yml)

# vanaf de opdrachtregel
xcodebuild -project Family7.xcodeproj -scheme Family7 \
  -destination 'platform=iOS Simulator,name=iPhone 17' test
```

De Cast SDK is alleen als xcframework te krijgen (CocoaPods of handmatig, niet
via Swift Package Manager). Hij wordt statisch gelinkt met `-ObjC -lc++`; zijn
resourcebundels gaan apart mee in de app, en de afhankelijkheid
`GTMSessionFetcher` komt via SPM.

Om op een echte iPhone te draaien: zet in Xcode bij *Signing & Capabilities* uw
team. Voor Cast op een toestel adviseert Google daarnaast de capability
*Access WiFi Information*.

Alleen in debug-builds kan een deep link als opstartargument meegegeven
worden, handig voor een simulator zonder scherm:

```bash
xcrun simctl launch booted nl.family7.ios -family7DeepLink family7://live
```

## 🧱 Opbouw

| Map | Inhoud |
|---|---|
| `Family7/Data/` | Datalaag, geport van `core/` van de Android-apps: HTTP met Keychain-cookies, repositories (SwiftSoup), Streampartner-uitpakker, caches en snapshots, netwerkmonitor |
| `Family7/Playback/` | `PlaybackManager`: AVPlayer, AirPlay, Google Cast, herstel, vergrendelscherm |
| `Family7/Cast/` | Cast-instellingen en de Cast- en AirPlay-knoppen |
| `Family7/App/` | App-ingang, `AppModel`, `Loader`, afbeeldingscache |
| `Family7/UI/` | SwiftUI-schermen en bouwstenen |
| `Family7Tests/` | Unit-tests: de uitpakker (dezelfde gevallen als op Android), caches, snapshots, MIME-type voor Cast |

Family7 is een merk van Family7. Deze app is een onafhankelijk project en niet
door Family7 uitgebracht.
