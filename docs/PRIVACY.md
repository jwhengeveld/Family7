# Privacyverklaring — Family7-apps

_Laatst bijgewerkt: 12 oktober 2026_

Deze verklaring geldt voor de Family7-apps voor telefoon en tablet (Android en
iOS) en voor Android TV. Het zijn onafhankelijk gemaakte apps voor de dienst
van Family7, geen officiële uitgave van Family7. Deze verklaring beschrijft wat
de apps met uw gegevens doen.

## Kort samengevat

De app verzamelt niets voor zichzelf. Er is geen server van de maker, geen
statistiekendienst, geen advertentienetwerk en geen crashrapportage. Alles wat
de app verstuurt gaat rechtstreeks naar Family7 en de streamingdienst die
Family7 gebruikt, precies zoals wanneer u de website in een browser opent.

## Welke gegevens de app verwerkt

| Gegeven | Waarvoor | Waar het blijft |
|---|---|---|
| E-mailadres of gebruikersnaam | Aanmelden bij uw eigen Family7-account | Naar family7.nl; lokaal versleuteld bewaard om het aanmeldveld voor te vullen |
| Wachtwoord | Aanmelden bij uw eigen Family7-account | Alleen doorgestuurd naar family7.nl; **nergens bewaard**, ook niet versleuteld |
| Aanmeldsessie (cookie) | Ingelogd blijven tussen twee keer kijken | Versleuteld op het toestel; gaat alleen terug naar family7.nl |
| Uw gebruikersnummer bij Family7 | Bevestigen dat de sessie nog geldig is | Versleuteld op het toestel |
| "Mijn lijst" | Uw eigen lijst tonen en bijwerken | Staat bij Family7; de app leest en schrijft dezelfde lijst als de website |
| Opgevraagde pagina's en beelden | Sneller laden | Tijdelijke cache op het toestel, wordt door Android zelf opgeruimd |
| Programma's en programmagids | Meteen iets tonen, ook zonder netwerk | Lokaal op het toestel; wordt ververst van family7.nl |
| Laatste zoekopdrachten | Snel opnieuw zoeken | Alleen lokaal op het toestel; te wissen in het zoekscherm |
| Wat er speelt bij casten | Een programma op uw Chromecast of Google TV zetten | Gaat via Google Cast naar uw eigen tv |

De tv-app vraagt twee rechten: internettoegang en het uitlezen van de
netwerkstatus. De telefoonapp vraagt daarnaast twee technische rechten om te
kunnen blijven afspelen: het toestel wakker houden tijdens het kijken, en een
voorgronddienst voor de bediening van wat er speelt (ook tijdens het casten).
Geen locatie, geen contacten, geen microfoon, geen camera, geen opslag, geen
apparaat-identificatie.

## Hoe de aanmeldsessie beveiligd is

- De sessie wordt versleuteld met AES-256-GCM. De sleutel wordt gemaakt in de
  AndroidKeyStore van het toestel en is voor de app zelf niet uitleesbaar.
- De sessie blijft buiten Google Drive-back-ups en buiten het overzetten naar
  een nieuw toestel (`data_extraction_rules.xml`, `backup_rules.xml`).
- Cookies gaan alleen terug naar het domein, het pad en het beveiligingsniveau
  waarvoor Family7 ze heeft afgegeven. De app staat geen onversleuteld verkeer
  toe (`usesCleartextTraffic="false"`).
- Uitloggen wist de sessie en alle opgeslagen gegevens van het toestel.

## Met wie gegevens worden gedeeld

Alleen met de partijen waar u sowieso al mee te maken heeft als u Family7
gebruikt:

- **Family7** (`www.family7.nl`) — aanmelden, catalogus, uw lijst.
- **Streampartner** (`*.streampartner.nl`) — de videostream zelf.
- **Google Cast**, alleen als u zelf naar een Chromecast of Google TV cast — het
  adres van de stream en de titel gaan dan naar uw eigen tv.

De maker van de app ontvangt geen enkel gegeven. Er is geen tussenliggende
server.

## Uw gegevens bij Family7

Uw account, kijkgeschiedenis en lijst zijn van Family7. Wilt u die inzien,
wijzigen of laten verwijderen, dan loopt dat via Family7 zelf; deze app heeft
er geen aparte kopie van. Het verwijderen van de app wist alles wat er lokaal
stond.

## Account en gegevens verwijderen

De Family7-app (Xi Appdesign) maakt zelf geen accounts aan: je logt in met je
bestaande Family7+-account.

- **Gegevens in de app verwijderen:** tik op "Uitloggen" (startscherm, menu ⋮).
  Dat wist meteen je sessie, je bewaarde lijsten, de programmagids en je
  zoekgeschiedenis van het toestel. De app verwijderen wist ook alles.
- **Je Family7+-account verwijderen:** dat account is van Family7. Vraag de
  verwijdering aan via https://www.family7.nl/service/contact. Family7 verwijdert
  dan je account, je lijst en je kijkgeschiedenis volgens zijn eigen beleid.
- De maker van de app bewaart geen gegevens van je, dus daar valt niets te
  verwijderen. Vragen: info@xiwebdesign.nl.

## Kinderen

De app bevat een kinderrubriek met de programma's die Family7 daarvoor
aanbiedt. Er wordt geen profiel opgebouwd en geen gedrag gevolgd.

## Contact

Vragen over deze app: via de GitHub-repository van het project. Vragen over uw
account of over de programma's: rechtstreeks bij Family7.
