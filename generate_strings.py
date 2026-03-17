import xml.etree.ElementTree as ET
import json
import os

tree_en = ET.parse(r'c:\Users\micha\Downloads\EMtest\testEM\app\src\main\res\values\strings.xml')
tree_sk = ET.parse(r'c:\Users\micha\Downloads\EMtest\testEM\app\src\main\res\values-sk\strings.xml')

root_en = tree_en.getroot()
root_sk = tree_sk.getroot()

en_dict = {child.attrib['name']: child.text for child in root_en if child.tag == 'string'}
sk_dict = {child.attrib['name']: child.text for child in root_sk if child.tag == 'string'}

strings_obj = {}

for name, en_str in en_dict.items():
    if not en_str: continue
    if name in sk_dict and sk_dict[name]:
        sk_str = sk_dict[name]
        
        # Handle string formatting
        en_str = en_str.replace("\\'", "'").replace("\\n", "\n").replace("\\\"", "\"")
        sk_str = sk_str.replace("\\'", "'").replace("\\n", "\n").replace("\\\"", "\"")
        
        strings_obj[en_str] = {
            "extractionState": "manual",
            "localizations": {
                "sk": {
                    "stringUnit": {
                        "state": "translated",
                        "value": sk_str
                    }
                }
            }
        }

# Additional iOS specific strings found in code
ios_specific = {
    "Are you sure you want to logout?": "Naozaj sa chcete odhlásiť?",
    "Biometrics": "Biometria",
    "Enter PIN or use biometrics": "Zadajte PIN alebo použite biometriu",
    "Real-time QR Token Generator": "Generátor QR Tokenov v reálnom čase",
    "Waiting for token...": "Čaká sa na token...",
    "Unlock app first.": "Najprv odomknite aplikáciu.",
    "Unlock testEM": "Odomknúť testEM",
    "Unlock with PIN": "Odomknite pomocou PINu",
    "Use Face ID / Touch ID": "Použite Face ID / Touch ID",
    "Yes, Logout": "Áno, odhlásiť sa",
    "Protect your app with a PIN": "Chráňte svoju aplikáciu pomocou PINu",
    "Set App PIN": "Nastaviť PIN aplikácie",
    "No history found.": "Nenašla sa žiadna história.",
    "B64: \\(viewModel.tokenBase64)": "B64: \\(viewModel.tokenBase64)",
    "HEX: \\(viewModel.tokenHex)": "HEX: \\(viewModel.tokenHex)",
    "UID: \\(viewModel.nfcUid)": "UID: \\(viewModel.nfcUid)",
    "Enter current PIN.": "Zadajte aktuálny PIN.",
    "PINs do not match.": "PINy sa nezhodujú.",
    "PIN must be 4-8 digits.": "PIN musí mať 4 až 8 číslic.",
    "New PIN is too short.": "Nový PIN je príliš krátky.",
    "Current PIN is incorrect.": "Aktuálny PIN je nesprávny.",
    "PIN confirmation does not match.": "Potvrdenie PINu sa nezhoduje."
}

for en_str, sk_str in ios_specific.items():
    strings_obj[en_str] = {
        "extractionState": "manual",
        "localizations": {
            "sk": {
                "stringUnit": {
                    "state": "translated",
                    "value": sk_str
                }
            }
        }
    }

xcstrings = {
    "sourceLanguage": "en",
    "strings": strings_obj,
    "version": "1.0"
}

output_path = r'c:\Users\micha\Downloads\EMtest\testEM\iosApp\iosApp\Localizable.xcstrings'
with open(output_path, 'w', encoding='utf-8') as f:
    json.dump(xcstrings, f, ensure_ascii=False, indent=2)

print(f"Generated {output_path} with {len(strings_obj)} translations")
