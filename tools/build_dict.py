#!/usr/bin/env python3
"""Builds app/src/main/assets/ecdict.db from ECDICT (https://github.com/skywind3000/ECDICT, MIT).

Usage:
    curl -LO https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv
    curl -LO https://raw.githubusercontent.com/skywind3000/ECDICT/master/lemma.en.txt
    python3 tools/build_dict.py ecdict.csv lemma.en.txt app/src/main/assets/ecdict.db

Keeps lowercase English headwords that have frequency data, exam tags or Collins/Oxford marks
(~50k words), plus a form -> lemma table for inflections.
"""
import csv
import os
import re
import sqlite3
import sys

WORD_RE = re.compile(r"^[a-z][a-z'\-]*$")
SKIP_LEMMAS = {"it", "he", "she", "we", "they", "i", "you"}
# ECDICT's exchange data maps a few very common forms to the wrong lemma (does -> doe).
OVERRIDES = {
    "does": "do", "did": "do", "done": "do", "doing": "do",
    "has": "have", "had": "have", "having": "have",
    "is": "be", "am": "be", "are": "be", "was": "be", "were": "be", "been": "be", "being": "be",
    "cannot": "can",
}


def main(csv_path, lemma_path, out_path):
    csv.field_size_limit(10 ** 9)
    rows = {}
    exchange_lemma = {}
    with open(csv_path, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            w = r["word"]
            if not WORD_RE.match(w):
                continue
            frq = int(r["frq"] or 0)
            bnc = int(r["bnc"] or 0)
            for part in (r["exchange"] or "").split("/"):
                if part.startswith("0:") and len(part) > 2 and WORD_RE.match(part[2:]):
                    exchange_lemma[w] = part[2:]
            if not (frq > 0 or bnc > 0 or r["tag"] or r["collins"] or r["oxford"]):
                continue
            rank = frq if frq > 0 else bnc
            translation = r["translation"].replace("\\r\\n", "\n").replace("\\n", "\n").strip()
            if not translation:
                continue
            rows[w] = (
                w,
                r["phonetic"].strip(),
                translation,
                r["tag"].strip(),
                rank,
                int(r["collins"] or 0),
                int(r["oxford"] or 0),
                r["exchange"].strip(),
            )

    lemmas = {}
    for form, lemma in exchange_lemma.items():
        if lemma in rows and lemma != form:
            lemmas[form] = lemma
    with open(lemma_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith(";") or "->" not in line:
                continue
            head, forms = line.split("->", 1)
            lemma = head.split("/")[0].strip().lower()
            if lemma in SKIP_LEMMAS or lemma not in rows:
                continue
            for form in forms.split(","):
                form = form.strip().lower()
                if not form or form == lemma or not WORD_RE.match(form):
                    continue
                if form in lemmas or form in rows:
                    continue  # exchange data wins; real headwords keep their own entry
                lemmas[form] = lemma

    for form, lemma in OVERRIDES.items():
        if lemma in rows:
            lemmas[form] = lemma

    if os.path.exists(out_path):
        os.remove(out_path)
    db = sqlite3.connect(out_path)
    db.executescript(
        """
        PRAGMA page_size = 4096;
        CREATE TABLE dict (
            word TEXT PRIMARY KEY NOT NULL,
            phonetic TEXT NOT NULL,
            translation TEXT NOT NULL,
            tag TEXT NOT NULL,
            rank INTEGER NOT NULL,
            collins INTEGER NOT NULL,
            oxford INTEGER NOT NULL,
            exchange TEXT NOT NULL
        ) WITHOUT ROWID;
        CREATE TABLE lemma (form TEXT PRIMARY KEY NOT NULL, lemma TEXT NOT NULL) WITHOUT ROWID;
        CREATE INDEX dict_rank ON dict(rank);
        CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
        """
    )
    db.executemany("INSERT INTO dict VALUES (?,?,?,?,?,?,?,?)", rows.values())
    db.executemany("INSERT INTO lemma VALUES (?,?)", lemmas.items())
    db.execute("INSERT INTO meta VALUES ('version', '1')")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"dict: {len(rows)} words, lemma: {len(lemmas)} forms -> {out_path} ({os.path.getsize(out_path) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main(*sys.argv[1:4])
