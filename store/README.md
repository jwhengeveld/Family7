# Google Play — Family7 (testeditie)

Alles voor de Play-vermelding en de gesloten test van de testeditie
`nl.xiappdesign.family7`. Dat is één app in Play: de telefoonbuild in de gewone
tracks, de tv-build in de Android TV-track. De officiële ID's `nl.family7.*`
blijven vrij voor Family7.

```
store/
├── metadata/android/nl-NL/   titel (≤30), korte (≤80) en volledige beschrijving (≤4000)
├── whatsnew/                 releasenotes per taal (fastlane-indeling)
├── assets/
│   ├── icon-512.png          app-icoon 512×512
│   ├── feature-1024x500.png  feature graphic
│   ├── tv-banner-1280x720.png banner voor Android TV
│   ├── phone-1..4.png        telefoon, 1080×1920
│   └── tv-1..3.png           Android TV, 1920×1080
├── tools/build_store_graphics.py  maakt icoon, feature graphic en tv-banner opnieuw
└── GESLOTEN-TEST.md          de gesloten test met de Google-groep, stap voor stap
```

De schermafbeeldingen komen uit de echte app (testeditie 1.4.3) met het
testaccount. Het privacybeleid staat in [`docs/PRIVACY.md`](../docs/PRIVACY.md):
https://github.com/jwhengeveld/Family7/blob/main/docs/PRIVACY.md
