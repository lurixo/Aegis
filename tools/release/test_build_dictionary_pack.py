#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only
#

import base64
import contextlib
import hashlib
import io
import json
import os
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
import fetch_test_dict as ftd

REPO = "https://github.com/amzxyz/rime-wanxiang"
COMMIT = "7db7c588fd5ea90c13e4bf1814d7dd7fa8a2effc"
BUILDER_COMMIT = subprocess.check_output(
    ["git", "rev-parse", "HEAD"],
    cwd=Path(__file__).resolve().parents[2],
    text=True,
).strip()


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


class CurrentLanguageModelBuildTest(unittest.TestCase):
    def test_builds_the_model_from_the_same_current_tables_and_converter_as_dictionaries(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo_root = root / "repo"
            (repo_root / "tools/t2s-data").mkdir(parents=True)
            source = root / "source"
            (source / "dicts").mkdir(parents=True)
            for table in bp.TABLES:
                (source / "dicts" / f"{table}.dict.yaml").write_text(f"{table} fixture\n")
            output_dir = root / "output"
            calls = []
            environment = {"fixture": "controlled"}
            tooling = {"schema_version": 1, "fixture": "frozen"}

            def build(command, cwd, env=None):
                calls.append(command)
                self.assertIs(env, environment)
                if "--out" in command:
                    target = Path(command[command.index("--out") + 1])
                    target.write_bytes(minimal_language_model() if "lm" in command else b"AEGD fixture")

            with mock.patch.object(bp, "__file__", str(repo_root / "tools/release/build_dictionary_pack.py")), mock.patch.object(
                bp, "ensure_source_checkout", return_value=source
            ), mock.patch.object(
                bp, "fixed_tool_environment", return_value=environment
            ), mock.patch.object(bp, "verify_toolchain", return_value=tooling) as verify, mock.patch.object(
                bp, "current_tooling_identity", return_value=tooling
            ), mock.patch.object(bp, "run", side_effect=build), mock.patch.object(
                bp, "output", return_value=COMMIT
            ), mock.patch.object(bp, "tree_dirt", return_value=[]), mock.patch.object(
                bp, "load_grammar_reference", return_value={"fixture": "grammar"}
            ), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, bp.main(["--release-tag", "dict-latest", "--output-dir", str(output_dir)]))
            verify.assert_called_once()
            self.assertEqual(5, len(calls))
            expected_inputs = [str(source / "dicts" / f"{table}.dict.yaml") for table in bp.TABLES]
            for command in calls[1:]:
                self.assertEqual(expected_inputs, command[-len(bp.TABLES):])
                self.assertEqual(
                    str(repo_root / "tools/t2s-data"),
                    command[command.index("--t2s-data") + 1],
                )
            self.assertEqual(
                [str(repo_root / bp.TOOL_EXECUTABLE_RELATIVE),
                 "lm", "--out", str(output_dir / "staging" / bp.LM_ENTRY), "--min-bigram", "1",
                 "--t2s-data", str(repo_root / "tools/t2s-data"), *expected_inputs],
                calls[-1],
            )
            info = json.loads((output_dir / "aegis-build-info.json").read_text())
            resource = info["resources"][0]
            self.assertEqual({"format": "AEGL v1", "min_bigram": 1}, resource["build"]["language_model"])
            self.assertEqual(tooling, resource["build"]["tooling"])
            for table in resource["source"]["input_yaml_sha256"]:
                self.assertEqual(bp.sha256_file(source / table["path"]), table["sha256"])
            lm = resource["build"]["output_bins"][-1]
            self.assertEqual("AEGL v1", lm["format"])
            self.assertEqual(1, lm["min_bigram"])
            self.assertEqual(hashlib.sha256(minimal_language_model()).hexdigest(), lm["sha256"])

    def test_packager_includes_the_shared_runtime_identity_and_tool_distribution(self):
        repo = Path("/fixture/repo")
        environment = {"fixture": "environment"}
        identity = {"schema_version": 1, "python": {"version": "3.14.7"}, "java": {"runtime_version": "25.0.5"}}
        distribution = {"fixture": "distribution"}
        with mock.patch.object(bp, "verify_toolchain", return_value=identity) as verify, mock.patch.object(
            bp, "tool_distribution_identity", return_value=distribution
        ):
            self.assertEqual({**identity, "distribution": distribution}, bp.current_tooling_identity(repo, environment))
        verify.assert_called_once_with(repo, environment)


class FinalizePackTest(unittest.TestCase):
    def tooling_identity(self):
        return {"schema_version": 1, "fixture": "fixed-tooling"}

    def invoke(self, args, tooling=None):
        with mock.patch.object(
            bp,
            "current_tooling_identity",
            return_value=self.tooling_identity() if tooling is None else tooling,
        ), mock.patch.object(
            bp,
            "run",
            side_effect=AssertionError("finalization must not run an external tool"),
        ):
            return bp.finalize_main(args)

    def write_intermediate(self, root: Path):
        root.mkdir(parents=True)
        staging = root / "staging"
        staging.mkdir()
        files = {}
        notice = staging / bp.NOTICE_NAME
        notice.write_text(bp.attribution_text(REPO, "v17.0.3", "wanxiang", COMMIT), encoding="utf-8")
        files[bp.NOTICE_NAME] = notice
        for index, (zip_entry, _runtime, _key_type) in enumerate(bp.OUTPUTS, start=1):
            path = staging / zip_entry
            path.write_bytes(b"AEGD" + index.to_bytes(4, "little") + bytes(range(64)))
            files[zip_entry] = path
        lm = staging / bp.LM_ENTRY
        lm.write_bytes(minimal_language_model())
        files[bp.LM_ENTRY] = lm
        pack = root / "aegis_dict_pack_dict-latest.zip"
        bp.write_zip(pack, [(name, files[name]) for name in bp.PACK_ENTRIES])

        components = []
        for zip_entry, runtime_name, key_type in bp.OUTPUTS:
            components.append(
                bp.component_info(
                    zip_entry,
                    runtime_name,
                    "dictionary",
                    files[zip_entry],
                    key_type=key_type,
                )
            )
        components.append(
            bp.component_info(
                bp.LM_ENTRY,
                bp.LM_RUNTIME_NAME,
                "language_model",
                lm,
                format="AEGL v1",
            )
        )
        asset = {
            "name": pack.name,
            "sha256": bp.sha256_file(pack),
            "size_bytes": pack.stat().st_size,
        }
        repo_root = Path(bp.__file__).resolve().parents[2]
        builder_tree_dirt = bp.tree_dirt(repo_root)
        build_info = {
            "schema_name": "aegis.resource-build-info",
            "resources": [
                {
                    "kind": "dictionary",
                    "physical_asset": asset,
                    "source": {
                        "repo": REPO,
                        "ref_type": "tag",
                        "tag": "v17.0.3",
                        "branch": None,
                        "commit": COMMIT,
                    },
                    "build": {
                        "pack_state": "intermediate",
                        "builder_commit": BUILDER_COMMIT,
                        "builder_tree_dirty": bool(builder_tree_dirt),
                        "builder_tree_dirt": builder_tree_dirt,
                        "tooling": self.tooling_identity(),
                        "output_bins": components,
                        "zip_packaging": {"file_order": bp.PACK_ENTRIES},
                    },
                }
            ],
        }
        update = bp.update_payload(build_info)
        build_info_path = root / "aegis-build-info.json"
        update_path = root / "aegis-dictionary-update.json"
        build_info_path.write_text(json.dumps(build_info), encoding="utf-8")
        update_path.write_text(json.dumps(update), encoding="utf-8")
        return pack, build_info_path, update_path

    def finalize(self, root: Path):
        pack, build_info, update = self.write_intermediate(root)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(
                0,
                self.invoke(
                    [
                        "--pack",
                        str(pack),
                        "--build-info",
                        str(build_info),
                        "--update-json",
                        str(update),
                    ]
                ),
            )
        return pack, build_info, update

    def finalize_args(self, pack: Path, build_info: Path, update: Path):
        return [
            "--pack",
            str(pack),
            "--build-info",
            str(build_info),
            "--update-json",
            str(update),
        ]

    def test_finalization_produces_four_bound_components_and_final_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pack, build_info_path, update_path = self.finalize(root / "one")
            with zipfile.ZipFile(pack) as archive:
                self.assertEqual(bp.PACK_ENTRIES, archive.namelist())
                entries = {name: archive.read(name) for name in archive.namelist()}
            build_info = json.loads(build_info_path.read_text(encoding="utf-8"))
            resource = build_info["resources"][0]
            self.assertEqual("final", resource["build"]["pack_state"])
            self.assertEqual(bp.PACK_ENTRIES, resource["build"]["zip_packaging"]["file_order"])
            components = resource["build"]["output_bins"]
            self.assertEqual(bp.PACK_ENTRIES[1:], [item["zip_entry"] for item in components])
            for item in components:
                self.assertEqual(hashlib.sha256(entries[item["zip_entry"]]).hexdigest(), item["sha256"])
                self.assertEqual(len(entries[item["zip_entry"]]), item["size_bytes"])
            self.assertEqual(
                ["dictionary", "dictionary", "dictionary", "language_model"],
                [item["kind"] for item in components],
            )
            self.assertNotIn(
                "prefix_index_format", resource["build"]["finalization"]
            )
            self.assertEqual(
                bp.tooling_identity_sha256(self.tooling_identity()),
                resource["build"]["finalization"]["tooling_identity_sha256"],
            )
            update = json.loads(update_path.read_text(encoding="utf-8"))
            self.assertEqual(bp.sha256_file(pack), update["asset"]["sha256"])
            self.assertEqual(pack.stat().st_size, update["asset"]["size_bytes"])

    def test_two_independent_finalizations_are_byte_reproducible(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first, _, _ = self.finalize(root / "one")
            second, _, _ = self.finalize(root / "two")
            self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_finalization_leaves_the_published_bytes_untouched(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pack, build_info, update = self.write_intermediate(root / "one")
            before = pack.read_bytes()
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, self.invoke(self.finalize_args(pack, build_info, update)))
            self.assertEqual(before, pack.read_bytes())
            self.assertEqual(bp.PACK_ENTRIES, zipfile.ZipFile(pack).namelist())

    def test_recovers_when_interrupted_after_only_build_info_is_final(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pack, build_info, update = self.write_intermediate(root / "one")
            args = self.finalize_args(pack, build_info, update)
            real_write = bp.write_json_atomic
            writes = 0

            def interrupt_second_write(path, payload):
                nonlocal writes
                writes += 1
                if writes == 2:
                    raise RuntimeError("simulated interruption before update metadata")
                real_write(path, payload)

            with mock.patch.object(bp, "write_json_atomic", side_effect=interrupt_second_write):
                with self.assertRaisesRegex(RuntimeError, "simulated interruption"):
                    self.invoke(args)
            self.assertEqual(
                "final",
                json.loads(build_info.read_text(encoding="utf-8"))["resources"][0]["build"]["pack_state"],
            )
            self.assertEqual(
                bp.sha256_file(pack),
                json.loads(update.read_text(encoding="utf-8"))["asset"]["sha256"],
            )
            before = (pack.read_bytes(), build_info.read_bytes(), update.read_bytes())
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, self.invoke(args))
            self.assertEqual(
                before,
                (pack.read_bytes(), build_info.read_bytes(), update.read_bytes()),
            )

    def test_a_complete_finalization_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pack, build_info, update = self.finalize(root / "one")
            before = (pack.read_bytes(), build_info.read_bytes(), update.read_bytes())
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(
                    0,
                    self.invoke(self.finalize_args(pack, build_info, update)),
                )
            self.assertEqual(
                before,
                (pack.read_bytes(), build_info.read_bytes(), update.read_bytes()),
            )

    def test_a_final_pack_with_unknown_update_drift_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pack, build_info, update = self.finalize(root / "one")
            document = json.loads(update.read_text(encoding="utf-8"))
            document["source"]["commit"] = "0" * 40
            update.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "update-json metadata mismatch"):
                self.invoke(self.finalize_args(pack, build_info, update))

    def test_finalization_rejects_a_pack_flagged_final_it_never_finalized(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pack, build_info_path, update_path = self.write_intermediate(root / "one")
            document = json.loads(build_info_path.read_text(encoding="utf-8"))
            document["resources"][0]["build"]["pack_state"] = "final"
            build_info_path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "finalization metadata mismatch"):
                self.invoke(self.finalize_args(pack, build_info_path, update_path))

    def test_finalization_rejects_an_unknown_pack_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pack, build_info_path, update_path = self.write_intermediate(root / "one")
            document = json.loads(build_info_path.read_text(encoding="utf-8"))
            document["resources"][0]["build"]["pack_state"] = "published"
            build_info_path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "invalid build-info pack state"):
                self.invoke(self.finalize_args(pack, build_info_path, update_path))

    def test_finalization_rejects_a_builder_head_different_from_frozen_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pack, build_info_path, update_path = self.write_intermediate(root / "one")
            document = json.loads(build_info_path.read_text(encoding="utf-8"))
            document["resources"][0]["build"]["builder_commit"] = "0" * 40
            build_info_path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "differs from the frozen"):
                self.invoke(
                    self.finalize_args(pack, build_info_path, update_path)
                )

    def test_finalization_rejects_builder_tree_drift_after_the_intermediate_pack(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pack, build_info_path, update_path = self.write_intermediate(root / "one")
            with mock.patch.object(
                bp,
                "tree_dirt",
                return_value=[{"status": " M", "path": "unexpected"}],
            ):
                with self.assertRaisesRegex(ValueError, "tree differs"):
                    self.invoke(
                        self.finalize_args(pack, build_info_path, update_path)
                    )

    def test_finalization_rejects_tooling_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pack, build_info, update = self.write_intermediate(root / "one")
            with self.assertRaisesRegex(ValueError, "tooling differs"):
                self.invoke(
                    self.finalize_args(pack, build_info, update),
                    tooling={"schema_version": 1, "fixture": "different"},
                )

    def test_cli_rejects_an_arbitrary_tool_override(self):
        with self.assertRaises(SystemExit):
            bp.finalize_main(
                [
                    "--pack",
                    "/nonexistent/pack",
                    "--build-info",
                    "/nonexistent/build-info",
                    "--update-json",
                    "/nonexistent/update",
                    "--tool-bin",
                    "/tmp/arbitrary-tool",
                ]
            )


class GrammarReferenceTest(unittest.TestCase):
    def release(self, tag="LTS", url=None, release_url=None):
        return {
            "tag_name": tag,
            "html_url": release_url
            if release_url is not None
            else f"{bp.GRAMMAR_REPO_HTTPS}/releases/tag/{tag}",
            "prerelease": False,
            "published_at": "2026-07-23T13:20:00Z",
            "assets": [
                {
                    "id": 487206811,
                    "name": bp.GRAMMAR_NAME,
                    "browser_download_url": url
                    if url is not None
                    else f"{bp.GRAMMAR_REPO_HTTPS}/releases/download/{tag}/{bp.GRAMMAR_NAME}",
                    "updated_at": "2026-07-23T13:19:40Z",
                    "digest": "sha256:" + "a" * 64,
                    "size": 420012076,
                }
            ],
        }

    def test_records_the_exact_mutable_lts_asset_snapshot(self):
        ref = bp.grammar_reference(self.release())
        asset = ref["physical_asset"]
        self.assertEqual("a" * 64, asset["sha256"])
        self.assertEqual(420012076, asset["size_bytes"])
        self.assertEqual(487206811, asset["github_asset_id"])
        self.assertEqual("2026-07-23T13:19:40Z", asset["published_at"])
        self.assertEqual(f"{bp.GRAMMAR_REPO_HTTPS}/releases/download/LTS/{bp.GRAMMAR_NAME}", asset["url"])
        self.assertEqual("LTS", asset["release_tag"])
        self.assertEqual(f"{bp.GRAMMAR_REPO_HTTPS}/releases/tag/LTS", asset["release_url"])

    def test_rejects_a_snapshot_without_a_digest(self):
        release = self.release()
        del release["assets"][0]["digest"]
        with self.assertRaises(ValueError):
            bp.grammar_reference(release)

    def test_rejects_an_asset_served_by_another_host(self):
        for url in [
            f"https://example.test/releases/download/LTS/{bp.GRAMMAR_NAME}",
            f"https://github.com.example.test/amzxyz/RIME-LMDG/releases/download/LTS/{bp.GRAMMAR_NAME}",
            f"https://github.com/attacker/RIME-LMDG/releases/download/LTS/{bp.GRAMMAR_NAME}",
        ]:
            with self.assertRaises(ValueError):
                bp.grammar_reference(self.release(url=url))

    def test_rejects_an_asset_url_that_is_not_https(self):
        for scheme in ["http", "ftp"]:
            url = f"{scheme}://github.com/amzxyz/RIME-LMDG/releases/download/LTS/{bp.GRAMMAR_NAME}"
            with self.assertRaises(ValueError):
                bp.grammar_reference(self.release(url=url))

    def test_rejects_an_asset_url_outside_the_release_download_form(self):
        for url in [
            f"{bp.GRAMMAR_REPO_HTTPS}/releases/download/{bp.GRAMMAR_NAME}",
            f"{bp.GRAMMAR_REPO_HTTPS}/raw/LTS/{bp.GRAMMAR_NAME}",
            f"{bp.GRAMMAR_REPO_HTTPS}/releases/download/LTS/somethingelse.gram",
            f"{bp.GRAMMAR_REPO_HTTPS}/releases/download/LTS/{bp.GRAMMAR_NAME}?host=example.test",
        ]:
            with self.assertRaises(ValueError):
                bp.grammar_reference(self.release(url=url))

    def test_rejects_a_release_page_served_by_another_host(self):
        for release_url in [
            "https://example.test/amzxyz/RIME-LMDG/releases/tag/LTS",
            "https://github.com.example.test/amzxyz/RIME-LMDG/releases/tag/LTS",
            "https://github.com/attacker/RIME-LMDG/releases/tag/LTS",
            "http://github.com/amzxyz/RIME-LMDG/releases/tag/LTS",
        ]:
            with self.assertRaises(ValueError):
                bp.grammar_reference(self.release(release_url=release_url))

    def test_rejects_a_release_page_outside_the_release_tag_form(self):
        for release_url in [
            f"{bp.GRAMMAR_REPO_HTTPS}/releases/tag",
            f"{bp.GRAMMAR_REPO_HTTPS}/releases/tag/OTHER",
            f"{bp.GRAMMAR_REPO_HTTPS}/tree/LTS",
            f"{bp.GRAMMAR_REPO_HTTPS}/releases/tag/LTS?host=example.test",
        ]:
            with self.assertRaises(ValueError):
                bp.grammar_reference(self.release(release_url=release_url))

    def test_rejects_a_release_tag_that_walks_out_of_the_repository(self):
        for tag in ["../../attacker/evil", ".."]:
            with self.assertRaises(ValueError):
                bp.grammar_reference(self.release(tag=tag))


class DefaultAssetNameTest(unittest.TestCase):
    def test_rolling_tag_keeps_the_name_the_installed_app_asks_for(self):
        self.assertEqual("aegis_dict_pack_dict-latest.zip", bp.default_asset_name("dict-latest"))

    def test_debug_tag_keeps_the_short_numbered_name(self):
        self.assertEqual("aegis_dict_pack_debug13.zip", bp.default_asset_name("v0.1.0-debug.13"))

    def test_a_dotted_tag_keeps_its_dots_so_two_versions_cannot_share_a_name(self):
        self.assertEqual("aegis_dict_pack_dict-v16.2.3.zip", bp.default_asset_name("dict-v16.2.3"))
        self.assertEqual("aegis_dict_pack_dict-v1.6.23.zip", bp.default_asset_name("dict-v1.6.23"))


class ManifestReleaseTypeTest(unittest.TestCase):
    def manifest(self, root):
        repo = root / "builder"
        repo.mkdir()
        for command in (
            ["init", "-q"],
            ["config", "user.name", "Test User"],
            ["config", "user.email", "test@example.com"],
            ["commit", "-qm", "Create builder", "--allow-empty"],
        ):
            subprocess.run(["git", *command], cwd=repo, check=True, capture_output=True, text=True)
        pack = root / "pack.zip"
        pack.write_bytes(b"pack")
        args = SimpleNamespace(
            release_tag="dict-latest",
            source_repo_https=REPO,
            source_tag="v16.3.0",
            source_branch="wanxiang",
        )
        return bp.build_info(
            args,
            repo,
            COMMIT,
            "aegis_dict_pack_dict-latest.zip",
            pack,
            [],
            [],
            {},
            tooling={"schema_version": 1, "fixture": "fixed-tooling"},
        )

    def test_the_dictionary_asset_is_never_published_as_a_prerelease(self):
        with tempfile.TemporaryDirectory() as directory:
            info = self.manifest(Path(directory))

            self.assertIs(False, info["resources"][0]["physical_asset"]["prerelease"])
            self.assertIs(False, bp.update_payload(info)["asset"]["prerelease"])

    def test_no_command_line_flag_can_request_a_prerelease_manifest(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as raised:
                bp.main(["--release-tag", "dict-latest", "--prerelease"])

        self.assertEqual(2, raised.exception.code)


class BuilderTreeDirtTest(unittest.TestCase):
    def git(self, repo, *args):
        subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True, text=True)

    def builder(self, root):
        repo = root / "builder"
        repo.mkdir()
        self.git(repo, "init", "-q")
        self.git(repo, "config", "user.name", "Test User")
        self.git(repo, "config", "user.email", "test@example.com")
        for name in ("kept.txt", "changed.txt", "moved.txt", "removed.txt"):
            (repo / name).write_text(f"{name} original\n")
        self.git(repo, "add", "-A")
        self.git(repo, "commit", "-qm", "Create builder")
        return repo

    def build(self, repo, root):
        pack = root / "pack.zip"
        pack.write_bytes(b"pack")
        args = SimpleNamespace(
            release_tag="dict-latest",
            source_repo_https=REPO,
            source_tag="v16.3.0",
            source_branch="wanxiang",
        )
        info = bp.build_info(
            args,
            repo,
            COMMIT,
            "aegis_dict_pack_dict-latest.zip",
            pack,
            [],
            [],
            {},
            tooling={"schema_version": 1, "fixture": "fixed-tooling"},
        )
        return info["resources"][0]["build"]

    def test_a_clean_builder_tree_reports_no_dirt_at_all(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build = self.build(self.builder(root), root)

            self.assertIs(False, build["builder_tree_dirty"])
            self.assertEqual([], build["builder_tree_dirt"], "a clean tree must not be described as dirty")

    def test_every_dirty_path_is_listed_with_its_working_tree_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = self.builder(root)
            (repo / "changed.txt").write_text("changed.txt overlay\n")
            self.git(repo, "mv", "moved.txt", "renamed.txt")
            (repo / "removed.txt").unlink()
            (repo / "untracked").mkdir()
            (repo / "untracked" / "added.txt").write_text("added\n")

            build = self.build(repo, root)
            rows = {row["path"]: row for row in build["builder_tree_dirt"]}

            self.assertIs(True, build["builder_tree_dirty"])
            self.assertEqual(
                {"changed.txt", "renamed.txt", "removed.txt", "untracked/added.txt"},
                set(rows),
                "every dirty path must be described, and no clean path may be",
            )
            self.assertEqual("moved.txt", rows["renamed.txt"]["renamed_from"])
            self.assertIsNone(rows["removed.txt"]["sha256"], "a deleted path has no working-tree content")
            self.assertIsNone(rows["removed.txt"]["size_bytes"])
            for path in ("changed.txt", "renamed.txt", "untracked/added.txt"):
                content = (repo / path).read_bytes()
                self.assertEqual(hashlib.sha256(content).hexdigest(), rows[path]["sha256"])
                self.assertEqual(len(content), rows[path]["size_bytes"])
            self.assertNotIn("kept.txt", rows)

    def test_a_working_tree_rename_is_described_as_one_row(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = self.builder(root)
            (repo / "moved.txt").rename(repo / "renamed.txt")
            self.git(repo, "add", "-N", "renamed.txt")

            rows = self.build(repo, root)["builder_tree_dirt"]

            self.assertEqual([" R"], [row["status"] for row in rows])
            self.assertEqual(
                ["renamed.txt"],
                [row["path"] for row in rows],
                "the from-path of a working-tree rename must not become a row of its own",
            )
            self.assertEqual("moved.txt", rows[0]["renamed_from"])
            content = (repo / "renamed.txt").read_bytes()
            self.assertEqual(hashlib.sha256(content).hexdigest(), rows[0]["sha256"])
            self.assertEqual(len(content), rows[0]["size_bytes"])

    def test_a_working_tree_copy_is_described_as_one_row(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = self.builder(root)
            self.git(repo, "config", "status.renames", "copies")
            (repo / "copied.txt").write_bytes((repo / "changed.txt").read_bytes())
            (repo / "changed.txt").write_text("changed.txt overlay\n")
            self.git(repo, "add", "-N", "copied.txt")

            rows = self.build(repo, root)["builder_tree_dirt"]

            self.assertEqual([" M", " C"], [row["status"] for row in rows])
            self.assertEqual(
                ["changed.txt", "copied.txt"],
                [row["path"] for row in rows],
                "the from-path of a working-tree copy must not become a row of its own",
            )
            self.assertEqual("changed.txt", rows[1]["renamed_from"])

    def test_a_path_outside_the_repository_is_refused_instead_of_hashed(self):
        repo = Path("/nonexistent/builder")

        self.assertEqual(repo / "app" / "kept.txt", bp.path_in_repo(repo, "app/kept.txt"))
        for outside in ("/etc/hostname", "app/../../etc/hostname"):
            with self.assertRaises(ValueError):
                bp.path_in_repo(repo, outside)


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

class GitHubApiAuthenticationBoundaryTest(unittest.TestCase):
    def request_for(self, url, token="test-token"):
        with mock.patch.dict(os.environ, {"GITHUB_TOKEN": token}, clear=False), mock.patch.object(
            ftd.urllib.request,
            "urlopen",
            return_value=io.BytesIO(b"ok"),
        ) as get:
            ftd.http_get(url, 17)
        request = get.call_args.args[0]
        self.assertEqual(17, get.call_args.kwargs["timeout"])
        return request

    def test_token_is_sent_to_the_exact_https_api_host(self):
        request = self.request_for("https://api.github.com/repos/example/project/releases")
        self.assertEqual("Bearer test-token", request.get_header("Authorization"))

    def test_token_is_not_sent_to_downloads_or_lookalike_hosts(self):
        for url in [
            "https://github.com/example/project/releases/download/LTS/model.gram",
            "http://api.github.com/repos/example/project/releases",
            "https://api.github.com.example.invalid/repos/example/project/releases",
        ]:
            with self.subTest(url=url):
                self.assertIsNone(self.request_for(url).get_header("Authorization"))

    def test_token_is_not_reused_after_any_redirect(self):
        original = self.request_for("https://api.github.com/repos/example/project/releases")
        statuses = [
            (301, "Moved Permanently"),
            (302, "Found"),
            (303, "See Other"),
            (307, "Temporary Redirect"),
            (308, "Permanent Redirect"),
        ]
        urls = [
            "https://api.github.com/repositories/1/releases",
            "https://github.com/example/project/releases/download/LTS/model.gram",
            "http://api.github.com/repos/example/project/releases",
        ]
        for code, message in statuses:
            for url in urls:
                with self.subTest(code=code, url=url):
                    redirected = ftd.urllib.request.HTTPRedirectHandler().redirect_request(
                        original,
                        None,
                        code,
                        message,
                        {},
                        url,
                    )
                    self.assertIsNone(redirected.get_header("Authorization"))

    def test_missing_token_keeps_the_request_anonymous(self):
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch.object(
            ftd.urllib.request,
            "urlopen",
            return_value=io.BytesIO(b"ok"),
        ) as get:
            ftd.http_get("https://api.github.com/repos/example/project/releases", 19)
        self.assertIsNone(get.call_args.args[0].get_header("Authorization"))

if __name__ == "__main__":
    unittest.main(verbosity=2)
