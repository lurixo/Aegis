#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only
#

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
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_dictionary_pack as bp

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
    def _pack(self, work: Path) -> Path:
        staging = work / "staging"
        staging.mkdir()
        notice = staging / bp.NOTICE_NAME
        notice.write_bytes(bp.attribution_text(REPO, "v16.0.1", "wanxiang", COMMIT).encode("utf-8"))
        entries = [(bp.NOTICE_NAME, notice)]
        for zip_entry, _runtime, _key in bp.OUTPUTS:
            p = staging / zip_entry
            p.write_bytes(zip_entry.encode("utf-8") * 7)
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
                self.assertEqual(bp.INTERMEDIATE_ENTRIES, names)
                info = zf.getinfo(bp.NOTICE_NAME)
                self.assertEqual((1980, 1, 1, 0, 0, 0), info.date_time, "deterministic 1980 timestamp")
                body = zf.read(bp.NOTICE_NAME).decode("utf-8")
                self.assertIn("CC BY 4.0", body)
                self.assertIn("amzxyz", body)


class DownloadableComponentProtocolTest(unittest.TestCase):
    def test_new_entry_names_cannot_trigger_beta31_substring_routing(self):
        bp.require_safe_new_entry_names()
        for name in [bp.LM_ENTRY, *[item[0] for item in bp.PREFIX_OUTPUTS]]:
            for dangerous in bp.DANGEROUS_NEW_ENTRY_SUBSTRINGS:
                self.assertNotIn(dangerous, name.lower())

    def test_a_future_unsafe_entry_name_fails_closed(self):
        unsafe = [("aegis_dict.prefix-index", "runtime", "source", "letter")]
        with mock.patch.object(bp, "PREFIX_OUTPUTS", unsafe):
            with self.assertRaisesRegex(ValueError, "unsafe downloadable component"):
                bp.require_safe_new_entry_names()

    def test_intermediate_and_final_orders_are_exact_and_distinct(self):
        self.assertEqual(
            ["NOTICE.txt", "aegis_dict_full.bin", "aegis_t9_full.bin", "aegis_jianpin_full.bin", "aegis_lm.bin"],
            bp.INTERMEDIATE_ENTRIES,
        )
        self.assertEqual(
            bp.INTERMEDIATE_ENTRIES
            + ["aegis_pfx_letter.idx", "aegis_pfx_digit.idx", "aegis_pfx_initials.idx"],
            bp.FINAL_ENTRIES,
        )


class PrefixIndexProtocolTest(unittest.TestCase):
    def index(self, dictionary: bytes, prefix: bytes, word: bytes, frequency: int) -> bytes:
        return b"".join(
            [
                b"AEGP",
                struct.pack("<i", 4),
                hashlib.sha256(dictionary).digest(),
                bytes.fromhex(bp.sampled_sha256_file(self.dictionary_path)),
                struct.pack("<q", len(dictionary)),
                struct.pack("<i", 1),
                b"\x01",
                struct.pack("<i", len(prefix)),
                prefix,
                struct.pack("<i", 1),
                struct.pack("<i", len(word)),
                word,
                struct.pack("<i", frequency),
            ]
        )

    def test_rejects_non_ascii_prefix_non_utf8_word_and_nonpositive_frequency(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dictionary = b"AEGD" + bytes(range(96))
            self.dictionary_path = root / "dictionary.bin"
            self.dictionary_path.write_bytes(dictionary)
            index_path = root / "index.bin"
            index_path.write_bytes(
                self.index(dictionary, b"a", "安".encode("utf-8"), 1)
            )
            self.assertEqual(
                1,
                bp.parse_prefix_index_v4(index_path, self.dictionary_path)["record_count"],
            )
            cases = {
                "not ASCII": (b"\xff", "安".encode("utf-8"), 1),
                "not UTF-8": (b"a", b"\xff", 1),
                "frequency": (b"a", "安".encode("utf-8"), 0),
            }
            for message, (prefix, word, frequency) in cases.items():
                with self.subTest(message=message):
                    index_path.write_bytes(self.index(dictionary, prefix, word, frequency))
                    with self.assertRaisesRegex(ValueError, message):
                        bp.parse_prefix_index_v4(index_path, self.dictionary_path)

    def test_rejects_an_empty_index(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dictionary = root / "dictionary.bin"
            dictionary.write_bytes(b"AEGD" + bytes(range(96)))
            index_path = root / "index.bin"
            index_path.write_bytes(
                b"AEGP"
                + struct.pack("<i", 4)
                + bytes.fromhex(bp.sha256_file(dictionary))
                + bytes.fromhex(bp.sampled_sha256_file(dictionary))
                + struct.pack("<q", dictionary.stat().st_size)
                + struct.pack("<i", 0)
            )
            with self.assertRaisesRegex(ValueError, "record count"):
                bp.parse_prefix_index_v4(index_path, dictionary)


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


class Beta31T2sMaterializationTest(unittest.TestCase):
    def test_reads_fixed_git_blobs_instead_of_dirty_worktree_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / "repo"
            work = root / "work"
            repo.mkdir()
            work.mkdir()
            subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
            builder = repo / "tools/src/main/kotlin/com/aegis/tools/LmBuilder.kt"
            builder.parent.mkdir(parents=True)
            builder.write_text("historical builder\n", encoding="utf-8")
            t2s = repo / "tools/t2s-data"
            t2s.mkdir()
            names = ["one.txt", "two.txt", "three.tsv", "four.tsv"]
            for name in names:
                (t2s / name).write_text(f"historical {name}\n", encoding="utf-8")
            env = {
                **os.environ,
                "GIT_AUTHOR_NAME": "test",
                "GIT_AUTHOR_EMAIL": "test@example.com",
                "GIT_COMMITTER_NAME": "test",
                "GIT_COMMITTER_EMAIL": "test@example.com",
            }
            subprocess.run(["git", "add", "."], cwd=repo, check=True, env=env)
            subprocess.run(
                ["git", "commit", "-q", "-m", "fixture"],
                cwd=repo,
                check=True,
                env=env,
            )
            commit = subprocess.check_output(
                ["git", "rev-parse", "HEAD"], cwd=repo, text=True
            ).strip()
            builder_blob = subprocess.check_output(
                ["git", "rev-parse", "HEAD:tools/src/main/kotlin/com/aegis/tools/LmBuilder.kt"],
                cwd=repo,
                text=True,
            ).strip()
            blobs = {
                name: subprocess.check_output(
                    ["git", "rev-parse", f"HEAD:tools/t2s-data/{name}"],
                    cwd=repo,
                    text=True,
                ).strip()
                for name in names
            }
            for name in names:
                (t2s / name).write_text(f"dirty overlay {name}\n", encoding="utf-8")

            with mock.patch.multiple(
                bp,
                LM_COMPATIBILITY_COMMIT=commit,
                LM_COMPATIBILITY_BUILDER_BLOB=builder_blob,
                LM_BETA31_T2S_BLOBS=blobs,
            ):
                fixture, actual_blobs = bp.materialize_beta31_t2s(repo, work)

            self.assertEqual(blobs, actual_blobs)
            for name in names:
                self.assertEqual(
                    f"historical {name}\n",
                    (fixture / name).read_text(encoding="utf-8"),
                )


class Beta31LmReproductionGateTest(unittest.TestCase):
    def create_source(self, root: Path):
        source = root / "source"
        source.mkdir()
        subprocess.run(["git", "init", "-q"], cwd=source, check=True)
        table = source / "dicts/zi.dict.yaml"
        table.parent.mkdir()
        table.write_text("---\n...\n一\tyi\t1\n", encoding="utf-8")
        environment = {
            **os.environ,
            "GIT_AUTHOR_NAME": "test",
            "GIT_AUTHOR_EMAIL": "test@example.com",
            "GIT_COMMITTER_NAME": "test",
            "GIT_COMMITTER_EMAIL": "test@example.com",
        }
        subprocess.run(["git", "add", "."], cwd=source, check=True, env=environment)
        subprocess.run(
            ["git", "commit", "-q", "-m", "fixture"],
            cwd=source,
            check=True,
            env=environment,
        )
        commit = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=source, text=True
        ).strip()
        blob = subprocess.check_output(
            ["git", "rev-parse", "HEAD:dicts/zi.dict.yaml"], cwd=source, text=True
        ).strip()
        return source, commit, blob

    def test_executes_the_complete_gate_and_records_byte_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / "repo"
            work = root / "work"
            output_dir = root / "out"
            repo.mkdir()
            work.mkdir()
            output_dir.mkdir()
            source, commit, blob = self.create_source(root)
            model = minimal_language_model()
            t2s = work / "aegis-beta31-lm-t2s"
            t2s.mkdir()
            args = SimpleNamespace(lm_reproduction_source_dir=str(source))
            java_identity = {"java_home": "/fixed", "version": ["21.0.11"]}

            def build_model(command, cwd, env=None):
                Path(command[command.index("--out") + 1]).write_bytes(model)

            with mock.patch.multiple(
                bp,
                TABLES=["zi"],
                LM_BETA31_UPSTREAM_COMMIT=commit,
                LM_BETA31_INPUT_BLOBS={"dicts/zi.dict.yaml": blob},
                LM_BETA31_EXPECTED_SHA256=hashlib.sha256(model).hexdigest(),
                LM_BETA31_EXPECTED_SIZE=len(model),
            ), mock.patch.object(
                bp,
                "materialize_beta31_t2s",
                return_value=(t2s, {"fixture": "blob"}),
            ), mock.patch.object(
                bp,
                "fixed_java_identity",
                return_value=java_identity,
            ), mock.patch.object(
                bp,
                "run",
                side_effect=build_model,
            ):
                result = bp.verify_beta31_lm_reproduction(
                    args,
                    repo,
                    work,
                    output_dir,
                    repo / bp.TOOL_EXECUTABLE_RELATIVE,
                )

            self.assertEqual("pass", result["status"])
            self.assertNotIn("checked_in_asset", result)
            self.assertEqual(java_identity, result["java_runtime"])
            self.assertEqual(1, result["output"]["char_count"])
            self.assertEqual(
                "aegis-lm-beta31-reproduction.bin", result["output"]["path"]
            )
            serialized = json.dumps(result)
            for redacted in (repo, work, output_dir, source):
                self.assertNotIn(str(redacted), serialized)
            self.assertEqual(
                result,
                json.loads(
                    (output_dir / "aegis-lm-beta31-reproduction.json").read_text(
                        encoding="utf-8"
                    )
                ),
            )

    def test_every_frozen_identity_stays_pinned(self):
        self.assertEqual(14, len(bp.LM_BETA31_INPUT_BLOBS))
        self.assertEqual(4, len(bp.LM_BETA31_T2S_BLOBS))
        self.assertEqual(
            [f"dicts/{table}.dict.yaml" for table in bp.TABLES],
            list(bp.LM_BETA31_INPUT_BLOBS),
        )
        self.assertEqual(16_069_924, bp.LM_BETA31_EXPECTED_SIZE)
        self.assertEqual(
            "c3fc0a2891cfdeabf0a8fe92e6109da83209dc5852be24f0aedc7f598824790a",
            bp.LM_BETA31_EXPECTED_SHA256,
        )
        self.assertEqual("beta31-9484292", bp.LM_COMPATIBILITY_PROFILE)
        self.assertEqual(
            "9484292651903e245b88868a6171acf694763f69", bp.LM_COMPATIBILITY_COMMIT
        )
        self.assertEqual(
            "570fc5085c0a63bdf9b8629a5e410b5a24616ff4", bp.LM_COMPATIBILITY_BUILDER_BLOB
        )
        self.assertEqual(
            "351fd048b104c403e80e10a569f9a740d10753e1", bp.LM_BETA31_UPSTREAM_COMMIT
        )
        self.assertEqual(1, bp.LM_MIN_BIGRAM)

    def test_java_version_drift_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory) / "jdk"
            executable = home / "bin/java"
            executable.parent.mkdir(parents=True)
            executable.write_bytes(b"java")
            with mock.patch.object(bp, "FIXED_JAVA_HOME", home), mock.patch.object(
                subprocess,
                "check_output",
                return_value='openjdk version "21.0.12"\n',
            ):
                with self.assertRaisesRegex(SystemExit, "runtime drifted"):
                    bp.fixed_java_identity(bp.fixed_tool_environment())


class FinalizePackTest(unittest.TestCase):
    def tooling_identity(self):
        return {"schema_version": 1, "fixture": "fixed-tooling"}

    def run_prefix_tool(self, command, cwd, env=None):
        dictionary = Path(command[command.index("--dict") + 1])
        output = Path(command[command.index("--out") + 1])
        mode = command[command.index("--mode") + 1]
        prefix = b"2" if mode == "digit" else b"a"
        word = "安".encode("utf-8")
        output.write_bytes(
            b"AEGP"
            + struct.pack("<i", 4)
            + bytes.fromhex(bp.sha256_file(dictionary))
            + bytes.fromhex(bp.sampled_sha256_file(dictionary))
            + struct.pack("<q", dictionary.stat().st_size)
            + struct.pack("<i", 1)
            + b"\x01"
            + struct.pack("<i", len(prefix))
            + prefix
            + struct.pack("<i", 1)
            + struct.pack("<i", len(word))
            + word
            + struct.pack("<i", 1)
        )

    def invoke(self, args, executor=None, tooling=None):
        with mock.patch.object(
            bp,
            "current_tooling_identity",
            return_value=self.tooling_identity() if tooling is None else tooling,
        ), mock.patch.object(
            bp,
            "run",
            side_effect=self.run_prefix_tool if executor is None else executor,
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
        bp.write_zip(pack, [(name, files[name]) for name in bp.INTERMEDIATE_ENTRIES])

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
                        "zip_packaging": {"file_order": bp.INTERMEDIATE_ENTRIES},
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

    def test_finalization_produces_seven_bound_components_and_final_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pack, build_info_path, update_path = self.finalize(root / "one")
            with zipfile.ZipFile(pack) as archive:
                self.assertEqual(bp.FINAL_ENTRIES, archive.namelist())
                entries = {name: archive.read(name) for name in archive.namelist()}
            build_info = json.loads(build_info_path.read_text(encoding="utf-8"))
            resource = build_info["resources"][0]
            self.assertEqual("final", resource["build"]["pack_state"])
            self.assertEqual(bp.FINAL_ENTRIES, resource["build"]["zip_packaging"]["file_order"])
            components = resource["build"]["output_bins"]
            self.assertEqual(bp.FINAL_ENTRIES[1:], [item["zip_entry"] for item in components])
            for item in components:
                self.assertEqual(hashlib.sha256(entries[item["zip_entry"]]).hexdigest(), item["sha256"])
                self.assertEqual(len(entries[item["zip_entry"]]), item["size_bytes"])
            self.assertTrue(all(item["format"] == "AEGP v4" for item in components[-3:]))
            self.assertTrue(all(item["record_count"] > 0 for item in components[-3:]))
            self.assertTrue(all(item["word_count"] > 0 for item in components[-3:]))
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

    def test_recovers_when_interrupted_after_the_final_pack_replace(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pack, build_info, update = self.write_intermediate(root / "one")
            args = self.finalize_args(pack, build_info, update)
            with mock.patch.object(
                bp,
                "write_json_atomic",
                side_effect=RuntimeError("simulated interruption before metadata"),
            ):
                with self.assertRaisesRegex(RuntimeError, "simulated interruption"):
                    self.invoke(args)
            self.assertEqual(
                bp.FINAL_ENTRIES,
                zipfile.ZipFile(pack).namelist(),
            )
            self.assertEqual(
                "intermediate",
                json.loads(build_info.read_text(encoding="utf-8"))["resources"][0]["build"]["pack_state"],
            )
            final_pack = pack.read_bytes()
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, self.invoke(args))
            self.assertEqual(final_pack, pack.read_bytes())
            self.assertEqual(
                "final",
                json.loads(build_info.read_text(encoding="utf-8"))["resources"][0]["build"]["pack_state"],
            )
            self.assertEqual(
                bp.sha256_file(pack),
                json.loads(update.read_text(encoding="utf-8"))["asset"]["sha256"],
            )

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
            self.assertNotEqual(
                bp.sha256_file(pack),
                json.loads(update.read_text(encoding="utf-8"))["asset"]["sha256"],
            )
            final_pack = pack.read_bytes()
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, self.invoke(args))
            self.assertEqual(final_pack, pack.read_bytes())
            self.assertEqual(
                bp.sha256_file(pack),
                json.loads(update.read_text(encoding="utf-8"))["asset"]["sha256"],
            )

    def test_a_complete_finalization_is_idempotent_and_does_not_rebuild_indexes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pack, build_info, update = self.finalize(root / "one")
            before = (pack.read_bytes(), build_info.read_bytes(), update.read_bytes())
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(
                    0,
                    self.invoke(
                        self.finalize_args(pack, build_info, update),
                        executor=AssertionError("a final pack must not rebuild prefix indexes"),
                    ),
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

    def test_finalization_rejects_a_pack_already_marked_final(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pack, build_info_path, update_path = self.write_intermediate(root / "one")
            document = json.loads(build_info_path.read_text(encoding="utf-8"))
            document["resources"][0]["build"]["pack_state"] = "final"
            build_info_path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "only accepts an intermediate"):
                self.invoke(
                    [
                        "--pack",
                        str(pack),
                        "--build-info",
                        str(build_info_path),
                        "--update-json",
                        str(update_path),
                    ]
                )

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


if __name__ == "__main__":
    unittest.main(verbosity=2)
