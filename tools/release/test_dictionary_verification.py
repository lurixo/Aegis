#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("dictionary_verification", Path(__file__).with_name("dictionary_verification.py"))
verification = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verification)


class CoverageInputTest(unittest.TestCase):
    def baseline(self, root):
        rows = [f"{index}\t26\tfree\tshi\t-\t1\t{'1' * 64}" for index in range(1001)]
        header = ["# aegis-coverage-identity-v2"]
        header += [f"# asset-sha256 {name} {'0' * 64}" for name in verification.FETCH.RUNTIME_BINS]
        header += ["# canonical " + verification.CANONICAL, "# probes 1001", verification.COLUMNS]
        path = Path(root) / "coverage.tsv"
        path.write_text("\n".join(header + rows) + "\n")
        return path

    def test_existing_head_multiplicity_is_preserved(self):
        with tempfile.TemporaryDirectory() as root:
            path = self.baseline(root)
            self.assertEqual(set(verification.FETCH.RUNTIME_BINS), set(verification.coverage_metadata(path)))

    def test_malformed_headers_counts_ordinals_and_hashes_fail(self):
        for mutation in ("schema", "missing_asset", "duplicate_asset", "canonical", "count", "ordinal", "hash"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as root:
                path = self.baseline(root); lines = path.read_text().splitlines()
                if mutation == "schema": lines[0] = "# old-schema"
                elif mutation == "missing_asset": lines.pop(1)
                elif mutation == "duplicate_asset": lines.insert(1, lines[1])
                elif mutation == "canonical": lines[5] = "# canonical different"
                elif mutation == "count": lines[6] = "# probes 2"
                elif mutation == "ordinal": lines[9] = lines[8]
                else: lines[8] = lines[8].rsplit("\t", 1)[0] + "\tbad"
                path.write_text("\n".join(lines) + "\n")
                with self.assertRaises(SystemExit): verification.coverage_metadata(path)

    def test_baseline_component_mismatch_fails_before_materialization(self):
        with tempfile.TemporaryDirectory() as root:
            path = self.baseline(root)
            components = {name: {"sha256": "f" * 64} for name in verification.FETCH.RUNTIME_BINS}
            with mock.patch.object(verification.FETCH, "fixed_input_metadata", return_value=({}, components, ())), \
                 mock.patch.object(verification.FETCH, "main") as materialize:
                with self.assertRaisesRegex(SystemExit, "asset identities disagree"):
                    verification.prepare("pack", "manifest", "info", path)
            materialize.assert_not_called()

if __name__ == "__main__":
    unittest.main(verbosity=2)
