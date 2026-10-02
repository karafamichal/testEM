"""Checks for cis.py's date handling: python3 test_cis.py"""
from datetime import date

import cis

assert cis.easter(2026) == date(2026, 4, 5) and cis.easter(2027) == date(2027, 3, 28)
assert {date(2026, 4, 3), date(2026, 4, 6), date(2026, 9, 15)} <= cis.holidays(2026) and date(2026, 9, 1) not in cis.holidays(2026)
assert cis.explained_days("nejde od 29.10.26 do 30.10.26") == {date(2026, 10, 29), date(2026, 10, 30)}
assert cis.explained_days("nejde 24.12.26, 31.12.2026") == {date(2026, 12, 24), date(2026, 12, 31)}
assert [cis.explanation_kind(t) for t in ("nejde od 1.7.26 do 2.7.26", "ide aj 2.1.27", "ide od 1.7.26 do 31.8.26",
                                          "ide v dňoch školského vyučovania")] == ["not", "also", "only", None]
assert [d for d, _ in cis.line_versions(["L060101_261213_1.xlsx", "L060101_260901_1.xlsx"])["L060101"]] == \
    [date(2026, 9, 1), date(2026, 12, 13)]
assert cis.folder_name(cis.carrier_short("S.A.D. Zvolen a.s., Balkán 53, 960 95 Zvolen")) == "sad-zvolen-as"
assert cis.plain_name("Banská Bystrica,,Nám.Slobody") == "Banská Bystrica, Nám.Slobody"
print("ok")
