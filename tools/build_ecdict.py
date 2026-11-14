#!/usr/bin/env python3
"""
从 ECDICT (https://github.com/skywind3000/ECDICT, MIT License) 生成 App 内置的离线英汉词库。

用法:
    git clone --depth 1 https://github.com/skywind3000/ECDICT /path/to/ECDICT
    python3 tools/build_ecdict.py /path/to/ECDICT/ecdict.csv

输出: app/src/main/assets/ecdict.db.gz (SQLite, gzip 压缩)

收录范围（在体积和覆盖率之间取舍）:
  - 单词: 有考试标签 / 柯林斯星级 / 牛津 3000，或在 BNC / 当代语料库词频中出现过
  - 短语: 1~3 个词、每个词都在上面的单词表里（如 give up / look forward to）
  - 词形索引: 由单词的 exchange 字段生成 booked → book（过去式）等，便于查变形词
"""
import csv
import gzip
import os
import re
import sqlite3
import sys
import tempfile

csv.field_size_limit(10 ** 9)

WORD_RE = re.compile(r"^[A-Za-z][A-Za-z'\-]*( [A-Za-z][A-Za-z'\-]*){0,2}$")
EXCHANGE_KINDS = {"p", "d", "i", "3", "r", "t", "s"}

SCHEMA_VERSION = 1


def as_int(v):
    try:
        return int(v or 0)
    except ValueError:
        return 0


def is_single_word_keep(r):
    return (
        bool(r["tag"].strip())
        or bool(r["collins"].strip())
        or bool(r["oxford"].strip())
        or as_int(r["frq"]) > 0
        or as_int(r["bnc"]) > 0
    )


def rank(r):
    """同一个词（忽略大小写）有多条时，优先小写、词频高的"""
    frq = as_int(r["frq"]) or 10 ** 9
    return (0 if r["word"].islower() else 1, frq)


def main(src):
    rows = list(csv.DictReader(open(src, encoding="utf-8")))
    singles = {}
    for r in rows:
        w = r["word"]
        if " " in w or not WORD_RE.match(w) or not r["translation"].strip():
            continue
        if not is_single_word_keep(r):
            continue
        k = w.lower()
        if k not in singles or rank(r) < rank(singles[k]):
            singles[k] = r

    phrases = {}
    for r in rows:
        w = r["word"]
        if " " not in w or not WORD_RE.match(w) or not r["translation"].strip():
            continue
        if not all(p.lower() in singles for p in w.split()):
            continue
        k = w.lower()
        if k not in phrases or rank(r) < rank(phrases[k]):
            phrases[k] = r

    out_dir = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")
    os.makedirs(out_dir, exist_ok=True)
    tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False).name
    db = sqlite3.connect(tmp)
    db.executescript(
        f"""
        PRAGMA user_version = {SCHEMA_VERSION};
        CREATE TABLE dict (
            word TEXT PRIMARY KEY COLLATE NOCASE,
            phonetic TEXT,
            translation TEXT,
            definition TEXT,
            tag TEXT,
            exchange TEXT,
            collins INTEGER,
            oxford INTEGER
        ) WITHOUT ROWID;
        CREATE TABLE forms (
            form TEXT PRIMARY KEY COLLATE NOCASE,
            base TEXT,
            kind TEXT
        ) WITHOUT ROWID;
        CREATE TABLE android_metadata (locale TEXT);
        INSERT INTO android_metadata VALUES ('zh_CN');
        """
    )

    def fix(s):
        return (s or "").replace("\\n", "\n").strip()

    def insert(r, with_definition):
        db.execute(
            "INSERT OR IGNORE INTO dict VALUES (?,?,?,?,?,?,?,?)",
            (
                r["word"],
                fix(r["phonetic"]),
                fix(r["translation"]),
                fix(r["definition"]) if with_definition else "",
                r["tag"].strip(),
                r["exchange"].strip(),
                as_int(r["collins"]),
                1 if r["oxford"].strip() else 0,
            ),
        )

    for r in singles.values():
        insert(r, with_definition=True)
    for r in phrases.values():
        insert(r, with_definition=False)

    # 词形索引：只记录本身不是独立词条的变形，避免覆盖已有词条
    forms = 0
    for r in singles.values():
        for part in r["exchange"].split("/"):
            if ":" not in part:
                continue
            kind, form = part.split(":", 1)
            form = form.strip()
            if kind not in EXCHANGE_KINDS or not form or form.lower() in singles:
                continue
            cur = db.execute(
                "INSERT OR IGNORE INTO forms VALUES (?,?,?)", (form, r["word"], kind)
            )
            forms += cur.rowcount

    db.commit()
    db.execute("VACUUM")
    db.close()

    out = os.path.join(out_dir, "ecdict.db.gz")
    with open(tmp, "rb") as fi, gzip.open(out, "wb", compresslevel=9) as fo:
        fo.write(fi.read())
    print(
        f"words={len(singles)} phrases={len(phrases)} forms={forms} "
        f"db={os.path.getsize(tmp)/1e6:.1f}MB gz={os.path.getsize(out)/1e6:.1f}MB -> {out}"
    )
    os.remove(tmp)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
