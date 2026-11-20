#!/usr/bin/env python3
"""Builds app/src/main/assets/ecdict.db.gzip from ECDICT (https://github.com/skywind3000/ECDICT, MIT).

Usage:
    curl -LO https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv
    curl -LO https://raw.githubusercontent.com/skywind3000/ECDICT/master/lemma.en.txt
    python3 tools/build_dict.py ecdict.csv lemma.en.txt app/src/main/assets/ecdict.db  # writes ecdict.db.gzip

Keeps lowercase English headwords that have frequency data, exam tags or Collins/Oxford marks
(~50k words), plus a form -> lemma table for inflections, plus multi-word phrases (catch up with,
market capitalization) made of known words: general phrases, and terms from economics and law.
Medical, computing, chemistry and engineering jargon is left out to keep the app small.
"""
import csv
import os
import re
import sqlite3
import sys

WORD_RE = re.compile(r"^[a-z][a-z'\-]*$")
PHRASE_WORD_RE = re.compile(r"^[a-z]+(?:'[a-z]+)?$")
TERM_DOMAINS = {"经", "法"}
# Placeholders in phrases, matched against the reader's text by the app.
SLOTS = {"one's": "#poss", "oneself": "#refl", "sb": "#sb", "somebody": "#sb", "sth": "#sth", "something": "#sth"}
PHRASE_KIND_GENERAL = 1
PHRASE_KIND_TERM = 2
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
    phrase_rows = []
    with open(csv_path, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            w = r["word"]
            if " " in w:
                phrase_rows.append((w, r["translation"]))
                continue
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

    phrases = build_phrases(phrase_rows, rows, lemmas)

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
        CREATE TABLE phrase (
            phrase TEXT PRIMARY KEY NOT NULL,
            key TEXT NOT NULL,
            kind INTEGER NOT NULL,
            translation TEXT NOT NULL
        ) WITHOUT ROWID;
        """
    )
    db.executemany("INSERT INTO phrase VALUES (?,?,?,?)", phrases)
    db.execute("CREATE INDEX phrase_key ON phrase(key)")
    db.executemany("INSERT INTO dict VALUES (?,?,?,?,?,?,?,?)", rows.values())
    db.executemany("INSERT INTO lemma VALUES (?,?)", lemmas.items())
    db.execute("INSERT INTO meta VALUES ('version', '2')")
    db.commit()
    db.execute("VACUUM")
    db.close()
    # The app ships the database gzipped (ecdict.db.gzip; a .gz asset would be unpacked by the Android build) and unpacks it on first start.
    import gzip, shutil
    with open(out_path, "rb") as src, gzip.open(out_path + ".gzip", "wb", compresslevel=9) as dst:
        shutil.copyfileobj(src, dst)
    os.remove(out_path)
    out_path += ".gzip"
    print(f"dict: {len(rows)} words, lemma: {len(lemmas)} forms, phrase: {len(phrases)} -> {out_path} ({os.path.getsize(out_path) / 1e6:.1f} MB)")


def lemma_of(word, rows, lemmas):
    """As the app's Lemmatizer: a form much more common than its lemma keeps its own entry (according, not accord)."""
    lemma = lemmas.get(word)
    if lemma is None:
        return word
    own = rows[word][4] if word in rows else 0
    base = rows[lemma][4] if lemma in rows else 0
    return word if own > 0 and base > 0 and own * 2 < base else lemma


def build_phrases(phrase_rows, rows, lemmas):
    """(phrase, key, kind, translation): key is the phrase's words as lemmas, placeholders as #slots."""
    out = {}
    for w, raw in phrase_rows:
        words = w.split(" ")
        if not 2 <= len(words) <= 5 or words[0] in ("a", "an", "the"):
            continue
        if not all(PHRASE_WORD_RE.match(x) for x in words):
            continue
        if not all(x in rows or x in lemmas or x in SLOTS for x in words):
            continue
        if all(x in SLOTS for x in words[1:]) or words[0] in SLOTS:
            continue
        lines = [l.strip() for l in raw.replace("\\r\\n", "\n").replace("\\n", "\n").split("\n") if l.strip()]
        general = [l for l in lines if not l.startswith("[")]
        domains = set(re.findall(r"\[([^\]]+)\]", raw))
        if general:
            kind = PHRASE_KIND_GENERAL
            kept = general[:3]
        elif domains & TERM_DOMAINS:
            kind = PHRASE_KIND_TERM
            kept = [l for l in lines if any(l.startswith(f"[{d}]") for d in TERM_DOMAINS)][:2]
        else:
            continue
        translation = "\n".join(kept)[:240]
        key = " ".join(SLOTS.get(x) or lemma_of(x, rows, lemmas) for x in words)
        out[w] = (w, key, kind, translation)
    return list(out.values())


if __name__ == "__main__":
    main(*sys.argv[1:4])
