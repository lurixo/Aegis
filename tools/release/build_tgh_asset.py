#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import argparse
import hashlib
import struct
import sys
from pathlib import Path

MAGIC = b"AEGT"
VERSION = 1
HEADER_LEN = 12
MAX_VARINT_SHIFT = 14
ENTRY_COUNT = 8105
LEVEL_COUNTS = (3500, 3000, 1605)
LEVEL_BOUNDS = (3500, 6500, 8105)


def read_authority(path):
    rows = []
    for lineno, raw in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        if not raw or raw.startswith("#"):
            continue
        fields = raw.split("\t")
        if len(fields) != 4:
            raise ValueError("line %d: expected 4 columns, got %d" % (lineno, len(fields)))
        seq, char, codepoint, level = fields
        rows.append((int(seq), char, codepoint, int(level)))
    return rows


def check(rows):
    errors = []
    if len(rows) != ENTRY_COUNT:
        errors.append("entry count %d != %d" % (len(rows), ENTRY_COUNT))
    seen = {}
    for index, (seq, char, codepoint, level) in enumerate(rows):
        if seq != index + 1:
            errors.append("sequence %d at row %d breaks 1..%d" % (seq, index + 1, ENTRY_COUNT))
        if not codepoint.startswith("U+"):
            errors.append("seq %d (%s): codepoint %r is not U+XXXX" % (seq, char, codepoint))
            continue
        cp = int(codepoint[2:], 16)
        if chr(cp) != char:
            errors.append("seq %d: codepoint %s does not spell %s" % (seq, codepoint, char))
        if len(char) != 1:
            errors.append("seq %d: %r is not a single code point" % (seq, char))
        if cp in seen:
            errors.append("seq %d (%s): code point %s repeats seq %d" % (seq, char, codepoint, seen[cp]))
        seen[cp] = seq
        band = 1 if seq <= LEVEL_BOUNDS[0] else 2 if seq <= LEVEL_BOUNDS[1] else 3
        if level != band:
            errors.append("seq %d (%s): level %d contradicts band %d" % (seq, char, level, band))
    counts = [0, 0, 0]
    for _, _, _, level in rows:
        if level in (1, 2, 3):
            counts[level - 1] += 1
    if tuple(counts) != LEVEL_COUNTS:
        errors.append("level counts %d/%d/%d != %d/%d/%d" % (tuple(counts) + LEVEL_COUNTS))
    return errors


def encode(rows):
    ordered = sorted((int(codepoint[2:], 16), level) for _, _, codepoint, level in rows)
    out = bytearray()
    out += MAGIC
    out += struct.pack("<ii", VERSION, len(ordered))
    previous = 0
    for cp, _ in ordered:
        delta = cp - previous
        previous = cp
        while True:
            byte = delta & 0x7F
            delta >>= 7
            if delta:
                out.append(byte | 0x80)
            else:
                out.append(byte)
                break
    packed = bytearray((len(ordered) + 3) // 4)
    for index, (_, level) in enumerate(ordered):
        packed[index >> 2] |= (level - 1) << (2 * (index & 3))
    out += packed
    return bytes(out)


def decode(blob):
    if len(blob) <= HEADER_LEN or blob[:4] != MAGIC:
        raise ValueError("bad magic")
    version, count = struct.unpack_from("<ii", blob, 4)
    if version != VERSION:
        raise ValueError("unsupported version %d" % version)
    if count != ENTRY_COUNT:
        raise ValueError("holds %d entries, expected %d" % (count, ENTRY_COUNT))
    pos = HEADER_LEN
    previous = 0
    code_points = []
    for i in range(count):
        shift = 0
        delta = 0
        while True:
            if pos >= len(blob):
                raise ValueError("code points run past the end at entry %d" % i)
            if shift > MAX_VARINT_SHIFT:
                raise ValueError("over-wide delta at entry %d" % i)
            byte = blob[pos]
            pos += 1
            delta |= (byte & 0x7F) << shift
            if not byte & 0x80:
                break
            shift += 7
        if delta <= 0:
            raise ValueError("code points are not ascending at entry %d" % i)
        previous += delta
        if previous > 0x10FFFF or 0xD800 <= previous <= 0xDFFF:
            raise ValueError("invalid code point at entry %d" % i)
        code_points.append(previous)
    packed = (count + 3) // 4
    if pos + packed != len(blob):
        raise ValueError("extent is %d, file is %d" % (pos + packed, len(blob)))
    levels = []
    for i in range(count):
        level = ((blob[pos + (i >> 2)] >> (2 * (i & 3))) & 0x3) + 1
        if level not in (1, 2, 3):
            raise ValueError("level %d at entry %d" % (level, i))
        levels.append(level)
    counts = [levels.count(1), levels.count(2), levels.count(3)]
    if tuple(counts) != LEVEL_COUNTS:
        raise ValueError("level counts %d/%d/%d != %d/%d/%d" % (tuple(counts) + LEVEL_COUNTS))
    return dict(zip(code_points, levels))


def build(authority, out):
    rows = read_authority(authority)
    errors = check(rows)
    if errors:
        for message in errors:
            print("FAIL: %s" % message, file=sys.stderr)
        raise SystemExit(1)
    blob = encode(rows)
    expected = {int(codepoint[2:], 16): level for _, _, codepoint, level in rows}
    if decode(blob) != expected:
        raise SystemExit("FAIL: round trip does not reproduce the authority levels")
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    Path(out).write_bytes(blob)
    written = Path(out).read_bytes()
    if written != blob:
        raise SystemExit("FAIL: %s does not hold the bytes that were generated" % out)
    try:
        landed = decode(written)
    except ValueError as bad:
        raise SystemExit("FAIL: %s does not parse back: %s" % (out, bad))
    if landed != expected:
        raise SystemExit("FAIL: %s does not decode back to the authority levels" % out)
    print("authority sha256 %s" % hashlib.sha256(Path(authority).read_bytes()).hexdigest())
    print("asset     sha256 %s" % hashlib.sha256(blob).hexdigest())
    print("asset      bytes %d" % len(blob))
    print("levels 1/2/3     %d/%d/%d" % LEVEL_COUNTS)
    return blob


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--authority", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    build(args.authority, args.out)


if __name__ == "__main__":
    main()
