#!/usr/bin/env python3
"""Verify (hanzi,pinyin) eval pairs against per-character readings from zi.dict.yaml.
Keeps a pair only if its toneless pinyin segments into len(hanzi) syllables, each a
valid reading of the corresponding character. Output: pinyin<TAB>hanzi (verified)."""
import json, sys

TONE = {}
for base, marks in {
    'a':'āáǎà', 'e':'ēéěèê', 'i':'īíǐì', 'o':'ōóǒò', 'u':'ūúǔù',
    'v':'üǖǘǚǜ', 'n':'ńňǹ', 'm':'ḿ',
}.items():
    for ch in marks:
        TONE[ch] = base

def strip(s):
    return ''.join(TONE.get(c, c.lower()) for c in s)

def load_readings(path):
    r = {}
    indata = False
    for line in open(path, encoding='utf-8'):
        if not indata:
            if line.strip() == '...': indata = True
            continue
        line = line.rstrip('\n')
        if not line or line.startswith('#'): continue
        cols = line.split('\t')
        if len(cols) < 2: continue
        ch, py = cols[0], strip(cols[1]).replace(' ', '')
        if len(ch) != 1 or not py.isalpha(): continue
        r.setdefault(ch, set()).add(py)
    return r

def consistent(hanzi, pinyin, readings):
    chars = list(hanzi)
    if any(c not in readings for c in chars): return False
    # DP: can pinyin split into len(chars) syllables, each a reading of that char?
    reach = {0}  # pinyin positions reachable after matching i chars
    for c in chars:
        nxt = set()
        for pos in reach:
            for r in readings[c]:
                if pinyin.startswith(r, pos):
                    nxt.add(pos + len(r))
        if not nxt: return False
        reach = nxt
    return len(pinyin) in reach

def main():
    raw, out, zi = sys.argv[1], sys.argv[2], sys.argv[3]
    readings = load_readings(zi)
    data = json.load(open(raw, encoding='utf-8'))
    if isinstance(data, dict) and isinstance(data.get('result'), dict):
        pairs = data['result'].get('pairs', [])
    elif isinstance(data, dict):
        pairs = data.get('pairs', [])
    else:
        pairs = data
    kept, dropped = [], 0
    seen = set()
    for p in pairs:
        hanzi = (p.get('hanzi') or '').strip()
        pinyin = strip((p.get('pinyin') or '').strip().lower()).replace(' ', '')
        if not hanzi or not pinyin or hanzi in seen:
            continue
        if consistent(hanzi, pinyin, readings):
            kept.append((pinyin, hanzi)); seen.add(hanzi)
        else:
            dropped += 1
    with open(out, 'w', encoding='utf-8') as f:
        for py, hz in kept:
            f.write(f"{py}\t{hz}\n")
    print(f"verified {len(kept)} kept, {dropped} dropped -> {out}")

if __name__ == '__main__':
    main()
