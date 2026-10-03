#!/usr/bin/env python3
"""Builds the spell checker's word list from the Hunspell en_US dictionary (SCOWL) that ships with LibreOffice.

Every stem in en_US.dic is expanded with the PFX/SFX rules in en_US.aff the way Hunspell applies them (strip,
add, condition, prefix+suffix cross products), then lowercased, de-duplicated and sorted by UTF-16 code unit
(the order Kotlin's String.compareTo uses). Words with digits (ordinals like "1st") are skipped; words with
apostrophes ("don't") are kept.

Writes (relative to the repo root):
  server/src/main/resources/dict/en_US.txt     one word per line, UTF-8, '\\n' line endings, no trailing blank line
  server/src/main/resources/dict/README.txt    provenance + the SCOWL copyright/permission notice
  core/src/commonMain/kotlin/com/nodenote/spell/NoSuggest.kt
                                               hashes of the words Hunspell flags NOSUGGEST (slurs/profanity):
                                               accepted when typed, never suggested

Python 3 standard library only:
  python tools/build_dictionary.py [--source "C:/Program Files/LibreOffice/share/extensions/dict-en"]
"""

import argparse
import gzip
import itertools
import re
import sys
import textwrap
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_SOURCE = Path("C:/Program Files/LibreOffice/share/extensions/dict-en")
DICT_DIR = REPO / "server" / "src" / "main" / "resources" / "dict"
NOSUGGEST_KT = REPO / "core" / "src" / "commonMain" / "kotlin" / "com" / "nodenote" / "spell" / "NoSuggest.kt"

# .aff tables written as "KEY count" followed by count lines; none of them affect which words exist.
SKIPPED_TABLES = {"REP", "MAP", "PHONE", "BREAK", "COMPOUNDRULE", "OCONV", "AM", "CHECKCOMPOUNDPATTERN"}


class Affix:
    """One PFX or SFX flag: its rules as (strip, add, condition regex or None for ".")."""

    def __init__(self, suffix, cross):
        self.suffix = suffix
        self.cross = cross
        self.rules = []

    def apply(self, word):
        """Every form this affix makes from [word]."""
        out = []
        for strip, add, cond in self.rules:
            if len(word) <= len(strip):  # Hunspell never strips a whole word (no FULLSTRIP in en_US)
                continue
            if self.suffix:
                if word.endswith(strip) and (cond is None or cond.search(word)):
                    out.append(word[:len(word) - len(strip)] + add)
            elif word.startswith(strip) and (cond is None or cond.match(word)):
                out.append(add + word[len(strip):])
        return out


def compile_condition(cond, suffix):
    """A Hunspell condition ("." / literal / [abc] / [^abc] per position) as a regex anchored at the word's end
    (SFX) or start (PFX); None when it matches anything."""
    if cond == ".":
        return None
    parts, i = [], 0
    while i < len(cond):
        if cond[i] == "[":
            j = cond.index("]", i)
            body = cond[i + 1:j]
            negate = body.startswith("^")
            if negate:
                body = body[1:]
            parts.append("[" + ("^" if negate else "") + "".join("\\" + c if c in "\\]^-[" else c for c in body) + "]")
            i = j + 1
        else:
            parts.append("." if cond[i] == "." else re.escape(cond[i]))
            i += 1
    pattern = "".join(parts)
    return re.compile(pattern + "$" if suffix else "^" + pattern)


def parse_aff(path):
    """Returns (affixes by flag, single-value options such as NOSUGGEST, ICONV pairs)."""
    lines = path.read_text(encoding="utf-8-sig").splitlines()
    affixes, options, iconv = {}, {}, []
    i = 0
    while i < len(lines):
        fields = lines[i].split()
        i += 1
        if not fields or fields[0].startswith("#"):
            continue
        key = fields[0]
        if key in ("PFX", "SFX"):
            flag, cross, count = fields[1], fields[2] == "Y", int(fields[3])
            affix = Affix(key == "SFX", cross)
            for _ in range(count):
                rule = lines[i].split()
                i += 1
                if rule[:2] != [key, flag]:
                    sys.exit(f"unexpected affix rule for {key} {flag}: {lines[i - 1]!r}")
                strip = "" if rule[2] == "0" else rule[2]
                add = "" if rule[3] == "0" else rule[3]
                if "/" in add:
                    sys.exit(f"affix continuation classes aren't supported: {lines[i - 1]!r}")
                cond = rule[4] if len(rule) > 4 else "."
                affix.rules.append((strip, add, compile_condition(cond, key == "SFX")))
            affixes[flag] = affix
        elif key == "ICONV":
            for _ in range(int(fields[1])):
                rule = lines[i].split()
                i += 1
                iconv.append((rule[1], rule[2]))
        elif key in ("FLAG", "AF", "COMPLEXPREFIXES", "CIRCUMFIX", "FULLSTRIP"):
            sys.exit(f"{key} isn't supported by this script (en_US doesn't use it)")
        elif key in SKIPPED_TABLES:
            if len(fields) == 2 and fields[1].isdigit():
                i += int(fields[1])
        else:
            options[key] = fields[1] if len(fields) > 1 else ""
    return affixes, options, iconv


def parse_dic(path):
    """(word, flags) per entry; the first line is only the entry count."""
    entries = []
    for line in path.read_text(encoding="utf-8-sig").splitlines()[1:]:
        fields = line.split()
        if not fields:
            continue
        # "word/flags"; "\/" escapes a slash inside the word; morphological fields (if any) follow whitespace.
        m = re.match(r"((?:[^/\\]|\\.)*)(?:/(.*))?$", fields[0])
        entries.append((m.group(1).replace("\\/", "/"), m.group(2) or ""))
    return entries


def expand(word, flags, affixes, no_root_flags):
    """The stem plus every affixed form its flags allow, including prefix+suffix cross products."""
    forms = set()
    if not any(f in flags for f in no_root_flags):
        forms.add(word)
    cross_suffixed = []
    for flag in flags:
        affix = affixes.get(flag)
        if affix and affix.suffix:
            made = affix.apply(word)
            forms.update(made)
            if affix.cross:
                cross_suffixed.extend(made)
    for flag in flags:
        affix = affixes.get(flag)
        if affix and not affix.suffix:
            forms.update(affix.apply(word))
            if affix.cross:
                # Like Hunspell, the prefix condition is tested on the suffixed word.
                for suffixed in cross_suffixed:
                    forms.update(affix.apply(suffixed))
    return forms


def utf16_key(word):
    return word.encode("utf-16-be")  # big-endian bytes compare like UTF-16 code units


def fnv1a(word):
    """32-bit FNV-1a over UTF-16 code units, as a signed Int (must match fnv1a() in SpellChecker.kt)."""
    h = 0x811C9DC5
    data = word.encode("utf-16-be")
    for k in range(0, len(data), 2):
        h ^= (data[k] << 8) | data[k + 1]
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h - (1 << 32) if h >= (1 << 31) else h


def readme_text(source, word_count):
    """README.txt: what en_US.txt is, where it came from, and SCOWL's notice copied verbatim."""
    readme = (source / "README_en_US.txt").read_text(encoding="utf-8-sig")
    title, version, date, url = (line.strip() for line in readme.splitlines()[:4])
    notice = readme[readme.index("COPYRIGHT, SOURCES, and CREDITS:"):].rstrip("\n")
    aff = (source / "en_US.aff").read_text(encoding="utf-8-sig").splitlines()
    header = itertools.takewhile(lambda line: line.startswith("#"), aff)  # LibreOffice's patch note
    aff_note = " ".join(line.lstrip("#- ").strip() for line in header).strip()
    desc = source / "description.xml"
    m = re.search(r'<version\s+value="([^"]+)"', desc.read_text(encoding="utf-8-sig")) if desc.exists() else None
    bundle = f"LibreOffice's dict-en extension {m.group(1)}" if m else "LibreOffice's dict-en extension"

    def para(label, text):
        return textwrap.fill(text, width=100, initial_indent=f"{label:<8}", subsequent_indent=" " * 8)

    return "\n\n".join([
        "en_US.txt - English (US) word list for NodeNote's offline spell checker\n" + "=" * 71,
        para("What:", f"{word_count} words, one per line: lowercase, de-duplicated, sorted by UTF-16 code unit "
                      "(Kotlin's String.compareTo order), UTF-8, '\\n' line endings, no trailing blank line. "
                      "Loaded by com.nodenote.spell.SpellChecker."),
        para("Source:", f"{title}, {version} ({date}), derived from SCOWL ({url}): en_US.dic, en_US.aff and "
                        f"README_en_US.txt as bundled with {bundle}." +
                        (f' Its en_US.aff notes: "{aff_note}"' if aff_note else "")),
        para("How:", "Generated by tools/build_dictionary.py; re-run it rather than editing by hand. This is a "
                     "modified (expanded) form of that dictionary: every en_US.dic stem expanded with the en_US.aff "
                     "prefix/suffix rules, lowercased and de-duplicated; words containing digits (ordinals like "
                     "\"1st\") dropped; words with apostrophes (\"don't\") kept. Words the dictionary flags "
                     "NOSUGGEST (slurs and profanity) are kept so they are accepted, but SpellChecker never "
                     "suggests them (core: com/nodenote/spell/NoSuggest.kt)."),
        "The SCOWL copyright and permission notice from README_en_US.txt follows, verbatim.\n\n" + "-" * 71,
        notice,
    ]) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", type=Path, default=DEFAULT_SOURCE, help="folder with en_US.dic/.aff/README_en_US.txt")
    args = ap.parse_args()
    source = args.source

    affixes, options, iconv = parse_aff(source / "en_US.aff")
    entries = parse_dic(source / "en_US.dic")
    no_root = [options[k] for k in ("ONLYINCOMPOUND", "NEEDAFFIX", "FORBIDDENWORD") if k in options]
    nosuggest_flag = options.get("NOSUGGEST")

    words, flagged, plain = set(), set(), set()
    with_digits = 0
    for stem, flags in entries:
        taboo = nosuggest_flag is not None and nosuggest_flag in flags
        for form in expand(stem, flags, affixes, no_root):
            for a, b in iconv:
                form = form.replace(a, b)
            form = form.lower()
            if any(c.isdigit() for c in form):
                with_digits += 1
                continue
            words.add(form)
            (flagged if taboo else plain).add(form)

    ordered = sorted(words, key=utf16_key)
    odd = [w for w in ordered if not all(c.isalpha() or c == "'" for c in w)]
    if odd:
        print(f"note: {len(odd)} words have characters other than letters and apostrophes, e.g. {odd[:10]}")

    DICT_DIR.mkdir(parents=True, exist_ok=True)
    data = "\n".join(ordered).encode("utf-8")
    (DICT_DIR / "en_US.txt").write_bytes(data)

    (DICT_DIR / "README.txt").write_bytes(readme_text(source, len(ordered)).encode("utf-8"))

    # Taboo words are never suggested; a form some other entry also makes (unflagged) still counts as taboo.
    hash_set = {fnv1a(w) for w in flagged}
    hashes = sorted(hash_set)
    collisions = sorted(w for w in words - flagged if fnv1a(w) in hash_set)
    if collisions:
        print(f"warning: these words share a hash with a NOSUGGEST word and won't be suggested: {collisions}")
    rows = ",\n".join("    " + ", ".join(str(h) for h in hashes[k:k + 6]) for k in range(0, len(hashes), 6))
    NOSUGGEST_KT.parent.mkdir(parents=True, exist_ok=True)
    NOSUGGEST_KT.write_bytes(f"""\
// Generated by tools/build_dictionary.py from en_US.dic - do not edit by hand.
package com.nodenote.spell

/**
 * FNV-1a hashes ([fnv1a]) of the {len(flagged)} words en_US.dic flags NOSUGGEST (slurs and profanity), sorted:
 * accepted when typed, never offered as a suggestion. Hashed so the source doesn't spell them out.
 */
internal val NoSuggestHashes = intArrayOf(
{rows},
)
""".encode("utf-8"))

    print(f"stems: {len(entries)}, words: {len(ordered)} ({sum(1 for w in ordered if "'" in w)} with apostrophes, "
          f"{with_digits} forms with digits skipped), NOSUGGEST forms: {len(flagged)} "
          f"({len(flagged & plain)} also made by an unflagged entry)")
    print(f"en_US.txt: {len(data):,} bytes raw, {len(gzip.compress(data, compresslevel=9)):,} bytes gzip -9")
    print(f"wrote {DICT_DIR / 'en_US.txt'}\n      {DICT_DIR / 'README.txt'}\n      {NOSUGGEST_KT}")


if __name__ == "__main__":
    main()
