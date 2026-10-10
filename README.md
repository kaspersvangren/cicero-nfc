# Cicero NFC

Android-app, der gør telefonens NFC til RFID-læser i Cicero Mobile.

App'en viser Cicero Mobile i Androids indbyggede webvisning (samme motor som Chrome)
og kører ved siden af en lille lokal RFID-server på `localhost:1667`. Serveren taler
samme protokol som Deichmans open source RFID-program
[go-feig](https://github.com/digibib/go-feig), som Cicero understøtter under
RFID-scanneren **Deichman**.

Designet og testet af Kasper Svangren, Gladsaxe Bibliotekerne. Kodet med AI-assistance (Claude).

## Hent

Seneste version: [CiceroNFC.apk](https://github.com/kaspersvangren/cicero-nfc/releases/latest/download/CiceroNFC.apk)

Kræver Android 8 eller nyere med NFC. Testet på Motorola Edge 50 Fusion (Android 16)
med NXP ICODE SLIX og SLIX2-tags.

## Opsætning i Cicero Mobile (inde i app'en)

Enhedsindstillinger → RFID scanner:
- Scanner: **Deichman**
- Hostname: **localhost**
- Port: **1667**
- Slå **"RFID-scanner til som standard"** til

## Brug

Hold bogen mod bagsiden af telefonen:

| Signal | Betyder | Vibration |
|---|---|---|
| "Hold stille" | bogen er læst, Cicero arbejder | kort tik |
| Grøn pille, overstreget klokke | alarm slået fra (udlån) | to korte |
| Orange pille, klokke | alarm slået til (aflevering) | én lang |
| Rød pille | fejl, fx bogen fjernet for tidligt | tre hurtige |
| Grå pille, flueben | læst på en side uden alarmskift | – |
| Rød pille "RFID-fejl" | Hostname og/eller Port i Ciceros RFID-opsætning er forkert (fx et mellemrum); tryk for vejledning | – |

## Værktøj til tags (menuen ⋮)

Mens værktøjet er åbent, får Cicero ikke besked om tags.

- Viser hvad der står på et tag: materialenummer, del x af y, alarm, bibliotek og chiptype.
- **Alarm til/fra** i hånden og **Nulstil tag** (helt tom som en ny chip; spørger først).
- **Programmér chip**: materialenummer tastes eller læses med kameraet (stregkode eller trykte tal).
  Sæt programmeres del for del, én chip ad gangen. Efter programmering er alarmen slået til.
- **Biblioteksnummer** indstilles i værktøjet og skrives på nye chips sammen med DK.
- App'en sender aldrig lås-kommandoer til et tag (de kan ikke fortrydes).

## Data og sikkerhed

- App'en gemmer kun det samme som en almindelig browser (login, mellemlager) samt om
  Cicero vises lyst eller mørkt. Den sender selv ingen data nogen steder hen; al trafik
  ud af telefonen er Cicero Mobiles egen, bortset fra opdateringstjekket nedenfor.
- Loggen (materialenumre og RFID-kommandoer) ligger kun i hukommelsen og forsvinder,
  når app'en lukkes. Den skrives ikke til telefonens systemlog.
- Intet følger med til skyen eller en ny telefon.
- App'en tjekker GitHub for nye versioner (højst hver 6. time) og tilbyder at opdatere sig selv.
  Der sendes ingen data; den spørger kun efter seneste version. Android installerer kun en
  opdatering med samme digitale segl som den installerede app.
- App'en låser sig efter 5 minutter uden brug og ved opstart. Den låses op med telefonens
  egen skærmlås (fingeraftryk, ansigt eller pinkode). Mens den er låst, er Cicero skjult,
  og bøger hverken læses eller ændres.
- Den lokale server svarer kun forespørgsler fra Cicero Mobile
  (`https://cicero.systematic.com`) rettet til localhost.
- Kameraet (scanning af lånerkort) gives kun til Ciceros egen side.
- Værktøjets kamera genkender tal og stregkoder på selve telefonen (Googles ML Kit via Play-tjenester).
  Billeder gemmes ikke og sendes ikke; Play-tjenester kan sende anonym brugsstatistik om genkendelsen til Google.
- Links, der åbner et nyt vindue, åbnes i telefonens browser.

## Protokol

| Cicero kalder | App'en gør |
|---|---|
| `/.status` | svarer med status (test forbindelse) |
| `/events/` | sender `addTag`/`removeTag`, når en bog holdes mod / fjernes fra telefonen |
| `/scan` | returnerer bogen ved telefonen |
| `/start`, `/stop` | Cicero begynder/holder op med at lytte |
| `/alarmOn`, `/alarmOff` | sætter AFI til 0x07 (sikret) / 0xC2 (udlånt) |
| `/write?barcode=` | programmerer tagget efter den danske RFID-datamodel |

Protokollen er genskabt ud fra go-feig (MIT-licens, Deichman bibliotek, Oslo).
Der er ikke kopieret kode fra go-feig.

## Ændringer

Se [CHANGELOG.md](CHANGELOG.md).

## Licens

Endnu ikke valgt.
