# app/agents/soap_agent/prompts.py

def build_prompt(transcript: str) -> str:
    return f"""
# Instruktion:

Teile den mitgelieferten medizinischen Freitext in eine strukturierte Notiz für eine Patientenakte nach medizinischen Empfehlungen im angegebenen Format auf.

# Format:

ANAMNESE: Enthält eine ausführliche Beschreibung der Patientenanamnese. Falls sinnvoll, werden Subanamnesen wie Sozialanamnese, Substanzabusus oder Arbeitsanamnese strukturiert aufgeführt.
Die Inhalte basieren auf den üblichen Empfehlungen zur Anamneseerhebung aus der Literatur.

UNTERSUCHUNG und BEFUNDE: Fasst Laboruntersuchungen, Bildgebungen und körperliche Untersuchungen zusammen.
Die Ergebnisse werden strukturiert und präzise dargestellt, mit Unterkategorien wie Labor, Körperliche Untersuchung, Röntgen und Ultraschall, entsprechend den Empfehlungen aus der medizinischen Fachliteratur.

BEURTEILUNG: Synthetisiert Ansätze und Zusammenhänge, erläutert die diagnostische und klinische Strategie und liefert die rationale Begründung für Entscheidungen.

PROZEDERE und THERAPIE: Listet diagnostische und therapeutische Schritte klar gegliedert auf, z. B. in Diagnostik, Therapie und Aufgaben an den Patienten.

AKTUELLE DIAGNOSEN: Fasst aktuelle Diagnosen und Teildiagnosen zusammen. Es wird keine Angabe von ICD-Codes vorgenommen. Diagnosen und Symptome werden im Freitext beschrieben.

# Hinweise:

Verwende medizinische Fachterminologie. Überprüfe die Struktur mit aktuellen Empfehlungen aus der Fachliteratur. Die Darstellung ist absolut klar und übersichtlich. Es werden keine Sternchen,
Fettschriften oder andere Markierungen verwendet, weder in den Überschriften noch im Text. Überschriften oder Aufzählungen sind nur durch Klartext oder Absätze strukturiert,
ohne Verwendung von Sonderzeichen wie Sternchen oder Asterisken. Benutze NIE NIE NIE Asteriske bzw. Sternchen!

# Beispiel:

## Input (medizinischer Freitext):

Patientin stellt sich vor zur laborchemischen Verlaufskontrolle.Vor allem metabolische Probleme mit Hyperlipidämie und Diabetes mellitus Typ 2. Alles hervorragend eingestellt. LDL 0,8 bei Rosita Rosuvastatin 20 mg.
Patientin hatte bis jetzt keine Herzinfarkt oder ähnliches, deswegen ist der Werten etwas Niedrig und ich halbieren das Rosuvastatin auf 10 mg.
Des Weiteren haben wir das Vitamin B12 was sie seit 2022 nimmt mal abgesetzt. Wir machen eine Verlaufskontrolle in 6 Monaten, Messung klar ob sie das braucht.
Ansonsten hat die Patientin aktuell keine klinischen Beschwerden. Habe die Patientin angewiesen das nächste Mal den Impfpass und das Blutdrucktagebuch mitzubringen.

## Output (strukturierte Notiz):

ANAMNESE:
Die Patientin stellt sich zur laborchemischen Verlaufskontrolle vor. Im Vordergrund stehen bekannte metabolische Erkrankungen:
Hyperlipidämie und Diabetes mellitus Typ 2. Die Patientin berichtet über keine aktuellen Beschwerden. Kein Hinweis auf kardiovaskuläre Ereignisse in der Anamnese (z. B. Myokardinfarkt, Schlaganfall).
Allgemeinzustand stabil.

UNTERSUCHUNG und BEFUNDE:
Labor:
- LDL-Cholesterin bei 0,8 mmol/l unter laufender Therapie mit Rosuvastatin 20 mg.
- Weitere metabolische Parameter, einschließlich Blutzuckerwerte, im Zielbereich

Medikation:
- Rosuvastatin 20 mg bisher, neu: Dosisreduktion auf 10 mg aufgrund sehr niedriger LDL-Werte und fehlender kardiovaskulärer Vorerkrankung.
- Vitamin B12-Substitution seit 2022, aktuell pausiert zur Überprüfung des Substitutionsbedarfs.

BEURTEILUNG:
Die Patientin zeigt eine sehr gute metabolische Kontrolle unter der laufenden Therapie. Der LDL-Wert ist unter dem derzeitigen Zielwert, wobei keine kardiovaskuläre Vorerkrankung vorliegt.
Eine Dosisanpassung des Rosuvastatins erscheint gerechtfertigt. Vitamin B12 wird vorübergehend abgesetzt, um den Substitutionsbedarf im Verlauf zu evaluieren. Keine aktuellen klinischen Beschwerden.

PROZEDERE und THERAPIE:
Therapieanpassung:
- Rosuvastatin-Dosisreduktion von 20 mg auf 10 mg täglich.
- Pausieren der Vitamin-B12-Substitution, erneute Kontrolle in 6 Monaten.

Weiteres Vorgehen:
- Verlaufskontrolle in 6 Monaten inklusive erneuter Laborwerte (insbesondere Lipidprofil und Vitamin B12).
- Patientin angewiesen, zum nächsten Termin den Impfpass und das Blutdrucktagebuch mitzubringen.

AKTUELLE DIAGNOSEN:
- Hyperlipidämie bei sehr guter Kontrolle unter Rosuvastatin.
- Diabetes mellitus Typ 2 bei stabiler Stoffwechsellage.
- Vitamin-B12-Substitution in Evaluation.

# Jetzt analysiere diesen Freitext:

{transcript}

Gib die strukturierte Notiz exakt in diesem Format zurück.
"""
