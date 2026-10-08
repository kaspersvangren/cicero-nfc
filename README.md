# Cicero NFC

Android-app der gør telefonens NFC til RFID-læser for Cicero Mobile.

App'en viser Cicero Mobile og kører samtidig en lille server på
`localhost:1667`, der svarer som Deichmans RFID-program
[go-feig](https://github.com/digibib/go-feig). Cicero bruger det, når man
vælger **Deichman** som RFID-scanner.

## Hent

Seneste version: [CiceroNFC.apk](https://github.com/kaspersvangren/cicero-nfc/releases/latest/download/CiceroNFC.apk)

## Opsætning i Cicero Mobile (inde i app'en)

Enhedsindstillinger → RFID scanner:
- Scanner: **Deichman**
- Hostname: **localhost**
- Port: **1667**

## Brug

Hold bogen mod bagsiden af telefonen, til den summer kort (læst). Ved
udlån og aflevering summer den langt, når alarmen er skiftet – først da
må bogen fjernes.

## Hvad den kan

| Cicero kalder | App'en gør |
|---|---|
| `/.status` | svarer med status (test forbindelse) |
| `/events/` | sender `addTag`/`removeTag`, når en bog holdes mod / fjernes fra telefonen |
| `/scan` | returnerer bogen ved telefonen |
| `/alarmOn`, `/alarmOff` | sætter AFI til 0x07 (sikret) / 0xC2 (udlånt) |
| `/write?barcode=` | programmerer tagget efter den danske datamodel |

Knappen **Log** viser alt hvad Cicero beder om, og **Del** sender loggen videre.

Protokollen er genskabt ud fra go-feig (MIT-licens, Deichman bibliotek, Oslo).
