#!/usr/bin/env python3
"""Builds the bundled emoji dataset (TSV) from Unicode emoji-test.txt + gemoji aliases.

Output columns (tab separated):
  character \t name \t category \t subgroup \t keywords(| separated) \t has_tones(0/1)
"""
import json
import re
import sys
import unicodedata
from pathlib import Path

HERE = Path(__file__).parent
OUT = HERE.parent / "src" / "main" / "resources" / "emoji" / "emojis.tsv"

SKIN_TONES = set("\U0001F3FB\U0001F3FC\U0001F3FD\U0001F3FE\U0001F3FF")

# Curated colloquial search terms that neither CLDR names nor gemoji aliases cover.
SYNONYMS = {
    "\U0001F602": {"laugh", "crying", "lol", "tears"},           # 😂
    "\U0001F923": {"laugh", "rolling", "lmao", "rofl"},          # 🤣
    "\U0001F603": {"smile", "happy", "laugh"},                   # 😃
    "\U0001F604": {"laugh", "smile", "happy", "joy"},            # 😄
    "\U0001F601": {"laugh", "smile", "happy", "beaming"},        # 😁
    "\U0001F605": {"sweat", "nervous", "laugh"},                 # 😅
    "\U0001F92F": {"joy", "laugh", "happy"},                     # 🥹
    "\u2764\uFE0F": {"love", "heart", "like"},                   # ❤️
    "\U0001F494": {"broken", "breakup"},                         # 💔
    "\U0001F525": {"flame", "lit", "fire", "cool"},              # 🔥
    "\U0001F44D": {"yes", "ok", "like", "approve", "thumbs"},    # 👍
    "\U0001F44E": {"no", "dislike", "thumbs"},                   # 👎
    "\u2705": {"check", "done", "yes", "correct"},               # ✅
    "\u274C": {"cross", "no", "wrong", "delete"},                # ❌
    "\U0001F4AF": {"hundred", "perfect", "score"},               # 💯
    "\U0001F512": {"private"},                                   # 🔒
    "\U0001F513": {"unlocked"},                                  # 🔓
}

GROUP_MAP = {
    "Smileys & Emotion": "Smileys & Emotion",
    "People & Body": "People & Body",
    "Animals & Nature": "Animals & Nature",
    "Food & Drink": "Food & Drink",
    "Activities": "Activities",
    "Travel & Places": "Travel & Places",
    "Objects": "Objects",
    "Symbols": "Symbols",
    "Flags": "Flags",
    "Component": "Symbols",
}


def parse_emoji_test(path):
    entries = []
    group = sub = None
    line_re = re.compile(
        r"^\s*([0-9A-F]+(?:\s+[0-9A-F]+)*)\s*;\s*(\S+)\s+#\s+(\S+)"
        r"(?:\s+\[\d+\])?\s+E[\d.]+\s+(.+?)\s*$"
    )
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("# group: "):
            group = line[len("# group: "):]
            continue
        if line.startswith("# subgroup: "):
            sub = line[len("# subgroup: "):]
            continue
        if not line or line.startswith("#"):
            continue
        m = line_re.match(line)
        if not m:
            continue
        cps, qual, _emoji_char, name = m.groups()
        if qual != "fully-qualified":
            continue
        chars = "".join(chr(int(c, 16)) for c in cps.split())
        entries.append((chars, name, GROUP_MAP.get(group, "Symbols"), sub or group))
    return entries


def load_gemoji(path):
    alias = {}
    for item in json.loads(path.read_text(encoding="utf-8")):
        e = item.get("emoji")
        if not e:
            continue
        names = [item.get("name", "")] + item.get("aliases", [])
        words = set()
        for n in names:
            for w in re.split(r"[^a-z0-9']+", n.lower()):
                if len(w) > 2:
                    words.add(w)
        alias.setdefault(e, set()).update(words)
    return alias


def main():
    entries = parse_emoji_test(HERE / "emoji-test.txt")
    gemoji = load_gemoji(HERE / "gemoji-emoji.json")

    # base entries (no skin tone modifiers)
    base = [e for e in entries if not (set(e[0]) & SKIN_TONES)]
    toned = [e for e in entries if set(e[0]) & SKIN_TONES]

    # strip trailing "skin tone ..." suffix from toned names to link them back
    base_names = {}
    for chars, name, cat, sub in base:
        base_names.setdefault(name.lower(), []).append(chars)

    # find which base emojis have toned variants
    tone_parents = set()
    for chars, name, cat, sub in toned:
        stem = re.sub(r"\s+(?:light|medium-light|medium|medium-dark|dark)\s+skin\s*tone.*$", "", name, flags=re.I).strip().lower()
        if stem in base_names:
            tone_parents.update(base_names[stem])

    out_lines = []
    seen = set()
    for chars, name, cat, sub in base:
        if chars in seen:
            continue
        seen.add(chars)
        kw = set()
        for w in re.split(r"[^a-z0-9']+", name.lower()):
            if len(w) > 2:
                kw.add(w)
        kw |= gemoji.get(chars, set())
        kw |= SYNONYMS.get(chars, set())
        kw.discard("")
        kws = "|".join(sorted(kw))
        has_tones = "1" if chars in tone_parents else "0"
        sub = sub.replace("face", "face").strip()
        out_lines.append("\t".join([chars, name, cat, sub, kws, has_tones]))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(out_lines) + "\n", encoding="utf-8")
    print(f"wrote {len(out_lines)} emojis -> {OUT}")


if __name__ == "__main__":
    main()
