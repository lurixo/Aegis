#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only
#

import base64
import hashlib
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_dictionary_pack as bp

REPO = "https://github.com/amzxyz/rime-wanxiang"
COMMIT = "7db7c588fd5ea90c13e4bf1814d7dd7fa8a2effc"

def minimal_language_model() -> bytes:
    return (
        b"AEGL"
        + struct.pack("<i", 1)
        + struct.pack("<i", 1)
        + struct.pack("<q", 1)
        + struct.pack("<i", 0x4E00)
        + struct.pack("<q", 1)
        + struct.pack("<q", 0)
        + struct.pack("<ii", 0, 0)
        + struct.pack("<i", 0)
    )


def deflate_size(data: bytes, level: int) -> int:
    compressor = zlib.compressobj(level, zlib.DEFLATED, -15)
    return len(compressor.compress(data) + compressor.flush())


def deflate_witness() -> bytes:
    lines = []
    for index in range(8000):
        digest = hashlib.sha256(str(index).encode("ascii")).digest()[:9]
        lines.append(f"{base64.b64encode(digest).decode('ascii')} key{index % 977}\n")
    return "".join(lines).encode("ascii")


class AttributionTextTest(unittest.TestCase):
    def notice(self, tag="v16.0.1", branch="wanxiang", commit=COMMIT):
        return bp.attribution_text(REPO, tag, branch, commit)

    def test_carries_the_cc_by_attribution_facts(self):
        text = self.notice()
        for needle in [
            "amzxyz",
            "rime-wanxiang",
            "CC BY 4.0",
            "https://creativecommons.org/licenses/by/4.0/",
            REPO,
            "tag v16.0.1",
            f"commit {COMMIT}",
            "Modifications by Aegis",
            "AEGL v1",
            "aegis_lm.bin",
            "GPL-3.0-only",
        ]:
            self.assertIn(needle, text, f"attribution must state: {needle}")
        for table in bp.TABLES:
            self.assertIn(table, text, f"attribution must list source table '{table}'")

    def test_is_deterministic_and_ascii(self):
        self.assertEqual(self.notice(), self.notice())
        self.notice().encode("ascii")

    def test_branch_mode_is_named_when_no_tag_is_pinned(self):
        self.assertIn("branch wanxiang", self.notice(tag=None))
        self.assertNotIn("tag ", self.notice(tag=None))

    def test_claims_no_dictionary_seed_inside_the_app(self):
        text = self.notice()
        self.assertIn("this full pack keeps every entry (min-freq 1).", text)
        self.assertNotIn("seed", text.lower(), "the app ships no dictionary, so the pack may not claim one")

    def test_notice_name_is_never_mistaken_for_a_runtime_bin(self):
        low = bp.NOTICE_NAME.lower()
        for keyword in ("dict", "t9", "jianpin"):
            self.assertNotIn(keyword, low, f"NOTICE name must not contain runtime keyword '{keyword}'")


class DeterministicPackWithNoticeTest(unittest.TestCase):
    def _pack(self, work: Path, bin_payload: bytes = b"") -> Path:
        staging = work / "staging"
        staging.mkdir()
        notice = staging / bp.NOTICE_NAME
        notice.write_bytes(bp.attribution_text(REPO, "v16.0.1", "wanxiang", COMMIT).encode("utf-8"))
        entries = [(bp.NOTICE_NAME, notice)]
        for zip_entry, _runtime, _key in bp.OUTPUTS:
            p = staging / zip_entry
            p.write_bytes(bin_payload or zip_entry.encode("utf-8") * 7)
            entries.append((zip_entry, p))
        lm = staging / bp.LM_ENTRY
        lm.write_bytes(minimal_language_model())
        entries.append((bp.LM_ENTRY, lm))
        out = work / "pack.zip"
        bp.write_zip(out, entries)
        return out

    def test_pack_is_byte_reproducible_with_the_notice_included(self):
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            self.assertEqual(
                self._pack(Path(a)).read_bytes(),
                self._pack(Path(b)).read_bytes(),
                "pack must be byte-identical across independent builds",
            )

    def test_notice_is_first_entry_with_fixed_1980_timestamp_and_correct_body(self):
        with tempfile.TemporaryDirectory() as a:
            z = self._pack(Path(a))
            with zipfile.ZipFile(z) as zf:
                names = zf.namelist()
                self.assertEqual(bp.NOTICE_NAME, names[0], "attribution must be the first entry")
                self.assertIn("aegis_dict_full.bin", names)
                self.assertIn("aegis_t9_full.bin", names)
                self.assertIn("aegis_jianpin_full.bin", names)
                self.assertEqual(bp.PACK_ENTRIES, names)
                info = zf.getinfo(bp.NOTICE_NAME)
                self.assertEqual((1980, 1, 1, 0, 0, 0), info.date_time, "deterministic 1980 timestamp")
                body = zf.read(bp.NOTICE_NAME).decode("utf-8")
                self.assertIn("CC BY 4.0", body)
                self.assertIn("amzxyz", body)

    def test_every_entry_is_deflated_at_the_level_the_build_info_declares(self):
        witness = deflate_witness()
        self.assertNotEqual(deflate_size(witness, 6), deflate_size(witness, 9))
        with tempfile.TemporaryDirectory() as a:
            with zipfile.ZipFile(self._pack(Path(a), witness)) as zf:
                for info in zf.infolist():
                    self.assertEqual(
                        deflate_size(zf.read(info), 9),
                        info.compress_size,
                        f"{info.filename} must carry the level 9 deflate stream",
                    )


class DownloadableComponentProtocolTest(unittest.TestCase):
    def test_new_entry_names_cannot_trigger_beta31_substring_routing(self):
        bp.require_safe_new_entry_names()
        for dangerous in bp.DANGEROUS_NEW_ENTRY_SUBSTRINGS:
            self.assertNotIn(dangerous, bp.LM_ENTRY.lower())

    def test_a_future_unsafe_entry_name_fails_closed(self):
        with mock.patch.object(bp, "LM_ENTRY", "aegis_dict.prefix-index"):
            with self.assertRaisesRegex(ValueError, "unsafe downloadable component"):
                bp.require_safe_new_entry_names()

    def test_the_published_order_is_exact_and_carries_no_prefix_index(self):
        self.assertEqual(
            ["NOTICE.txt", "aegis_dict_full.bin", "aegis_t9_full.bin", "aegis_jianpin_full.bin", "aegis_lm.bin"],
            bp.PACK_ENTRIES,
        )
        self.assertFalse([name for name in bp.PACK_ENTRIES if name.endswith(".idx")])


class LanguageModelProtocolTest(unittest.TestCase):
    def require(self, root: Path, data: bytes):
        path = root / "model.bin"
        path.write_bytes(data)
        return bp.require_aegl_v1(path)

    def model_with_bigrams(self) -> bytes:
        return b"".join(
            [
                b"AEGL",
                struct.pack("<iiq", 1, 2, 3),
                struct.pack("<ii", 0x4E00, 0x4E01),
                struct.pack("<qq", 1, 2),
                struct.pack("<qq", 5, 0),
                struct.pack("<iii", 0, 2, 2),
                struct.pack("<i", 2),
                struct.pack("<ii", 0, 1),
                struct.pack("<qq", 2, 3),
            ]
        )

    def test_accepts_and_reports_the_complete_aegl_shape(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(
                {
                    "format": "AEGL v1",
                    "char_count": 2,
                    "bigram_count": 2,
                    "total_unigram_count": 3,
                },
                self.require(Path(directory), self.model_with_bigrams()),
            )

    def test_rejects_malformed_counts_boundaries_and_extent(self):
        valid = bytearray(minimal_language_model())
        cases = {}
        wrong_total = bytearray(valid)
        struct.pack_into("<q", wrong_total, 12, 2)
        cases["unigram total mismatch"] = wrong_total
        invalid_boundary = bytearray(valid)
        struct.pack_into("<i", invalid_boundary, 44, 1)
        cases["row boundary"] = invalid_boundary
        cases["file extent"] = valid + b"trailing"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for message, data in cases.items():
                with self.subTest(message=message):
                    with self.assertRaisesRegex(ValueError, message):
                        self.require(root, bytes(data))

    def test_rejects_unsorted_bigram_targets_zero_counts_and_bad_denominators(self):
        valid = self.model_with_bigrams()
        cases = {}
        duplicate_target = bytearray(valid)
        struct.pack_into("<ii", duplicate_target, 76, 1, 1)
        cases["bigram index"] = duplicate_target
        zero_count = bytearray(valid)
        struct.pack_into("<q", zero_count, 84, 0)
        cases["bigram count"] = zero_count
        bad_denominator = bytearray(valid)
        struct.pack_into("<q", bad_denominator, 44, 4)
        cases["row denominator"] = bad_denominator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for message, data in cases.items():
                with self.subTest(message=message):
                    with self.assertRaisesRegex(ValueError, message):
                        self.require(root, bytes(data))

class SourceCheckoutValidationTest(unittest.TestCase):
    def git(self, repo, *args):
        subprocess.run(
            ["git", *args],
            cwd=repo,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )

    def repository(self, root):
        repo = root / "source"
        repo.mkdir()
        self.git(repo, "init", "-q")
        self.git(repo, "config", "user.name", "Test User")
        self.git(repo, "config", "user.email", "test@example.com")
        table = repo / "table.dict.yaml"
        table.write_text("first\n")
        self.git(repo, "add", "table.dict.yaml")
        self.git(repo, "commit", "-qm", "Create source")
        self.git(repo, "tag", "v16.2.3")
        return repo, table

    def args(self, repo, source_tag="v16.2.3"):
        return SimpleNamespace(source_dir=str(repo), source_tag=source_tag)

    def test_accepts_clean_source_dir_at_the_source_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, _ = self.repository(root)

            self.assertEqual(
                repo.resolve(),
                bp.ensure_source_checkout(self.args(repo), root / "work"),
            )

    def test_accepts_clean_source_dir_with_no_pinned_source_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, _ = self.repository(root)

            self.assertEqual(
                repo.resolve(),
                bp.ensure_source_checkout(self.args(repo, None), root / "work"),
            )

    def test_rejects_source_dir_whose_head_does_not_match_the_source_tag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, table = self.repository(root)
            table.write_text("second\n")
            self.git(repo, "add", "table.dict.yaml")
            self.git(repo, "commit", "-qm", "Change source")

            with self.assertRaisesRegex(SystemExit, "HEAD does not match"):
                bp.ensure_source_checkout(self.args(repo), root / "work")

    def test_rejects_dirty_source_dir(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, table = self.repository(root)
            table.write_text("dirty\n")

            with self.assertRaisesRegex(SystemExit, "must be clean"):
                bp.ensure_source_checkout(self.args(repo), root / "work")

if __name__ == "__main__":
    unittest.main(verbosity=2)
