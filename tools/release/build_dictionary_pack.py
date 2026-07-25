#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path


TABLES = [
    "zi",
    "jichu",
    "lianxiang",
    "cuoyin",
    "duoyin",
    "shici",
    "diming",
    "yixue",
    "huaxue",
    "yaopin",
    "mingren",
    "yiren",
    "wuzhong",
    "renming",
]

OUTPUTS = [
    ("aegis_dict_full.bin", "aegis_dict.bin", "letter"),
    ("aegis_t9_full.bin", "aegis_t9.bin", "digit"),
    ("aegis_jianpin_full.bin", "aegis_jianpin.bin", "initials"),
]

NOTICE_NAME = "NOTICE.txt"
GRAMMAR_NAME = "wanxiang-lts-zh-hans.gram"
GRAMMAR_RELEASE_API = "https://api.github.com/repos/amzxyz/RIME-LMDG/releases/tags/LTS"
DICT_RELEASE_TAG_PATTERN = re.compile(r"dict-(v\d+\.\d+\.\d+)-r([1-9]\d*)")


def attribution_text(repo_https, source_tag, source_branch, source_commit):
    """The pack's third-party attribution, deterministic (no timestamps — only the pinned source
    coordinates vary), so the ZIP stays byte-reproducible. ASCII-only for maximum unzip compatibility.
    Wording mirrors the repository README's acknowledgments."""
    ref = f"tag {source_tag}" if source_tag else f"branch {source_branch}"
    tables = " ".join(TABLES)
    return (
        "Aegis downloadable dictionary pack - third-party attribution\n"
        "===========================================================\n"
        "\n"
        "This pack contains dictionary data DERIVED FROM the rime-wanxiang project.\n"
        "\n"
        "  Project:   rime-wanxiang\n"
        "  Copyright: (C) amzxyz and the rime-wanxiang contributors\n"
        "  License:   Creative Commons Attribution 4.0 International (CC BY 4.0)\n"
        "             https://creativecommons.org/licenses/by/4.0/\n"
        f"  Upstream:  {repo_https}\n"
        f"             {ref}, commit {source_commit}\n"
        "\n"
        f"  Source tables (14): {tables}\n"
        "\n"
        "Modifications by Aegis:\n"
        "  - tones stripped (the u-umlaut is written as v) and syllables concatenated\n"
        "    into toneless keys;\n"
        "  - repacked from the source .dict.yaml tables into Aegis's own binary format\n"
        "    (aegis_dict.bin / aegis_t9.bin / aegis_jianpin.bin);\n"
        "  - this full pack keeps every entry (min-freq 1); the seed bundled in the APK\n"
        "    is frequency-filtered for size.\n"
        "\n"
        "CC BY 4.0 requires this attribution to accompany the material. Aegis's own code\n"
        "is licensed GPL-3.0-only; this notice concerns the bundled third-party dictionary\n"
        "data only. The repository's THIRD_PARTY_LICENSES.md carries the full license texts.\n"
    )


def run(cmd, cwd):
    print("+", " ".join(str(part) for part in cmd), flush=True)
    subprocess.run(cmd, cwd=cwd, check=True)


def output(cmd, cwd):
    return subprocess.check_output(cmd, cwd=cwd, text=True).strip()


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def dictionary_release_source_tag(release_tag):
    match = DICT_RELEASE_TAG_PATTERN.fullmatch(release_tag)
    if match is None:
        raise ValueError("release tag must match dict-vX.Y.Z-rN with N starting at 1")
    return match.group(1)


def default_asset_name(release_tag):
    dictionary_release_source_tag(release_tag)
    return f"aegis_dict_pack_{release_tag}.zip"


def grammar_reference(release):
    assets = [item for item in release.get("assets", []) if item.get("name") == GRAMMAR_NAME]
    if len(assets) != 1:
        raise ValueError(f"expected exactly one {GRAMMAR_NAME} asset")
    asset = assets[0]
    digest = asset.get("digest")
    if not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", digest):
        raise ValueError(f"{GRAMMAR_NAME} has no trustworthy SHA-256 digest")
    size = asset.get("size")
    if not isinstance(size, int) or size <= 0:
        raise ValueError(f"{GRAMMAR_NAME} has no valid size")
    return {
        "kind": "grammar_model",
        "physical_asset": {
            "name": GRAMMAR_NAME,
            "url": asset["browser_download_url"],
            "release_tag": release["tag_name"],
            "release_url": release["html_url"],
            "prerelease": bool(release.get("prerelease")),
            "published_at": asset.get("updated_at") or release.get("published_at"),
            "sha256": digest.removeprefix("sha256:").lower(),
            "size_bytes": size,
            "github_asset_id": asset.get("id"),
        },
        "source": {
            "repo": "https://github.com/amzxyz/RIME-LMDG",
            "branch": None,
            "commit": None,
        },
        "attestation": {
            "status": "external_resource_not_attested_by_aegis",
        },
    }


def load_grammar_reference(args):
    if args.grammar_release_json:
        release = json.loads(Path(args.grammar_release_json).read_text())
    else:
        request = urllib.request.Request(
            args.grammar_release_api,
            headers={
                "Accept": "application/vnd.github+json",
                "User-Agent": "Aegis-resource-builder",
            },
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            release = json.load(response)
    return grammar_reference(release)


def ensure_source_checkout(args, work_dir):
    if args.source_dir:
        source = Path(args.source_dir).resolve()
        if not source.exists():
            raise SystemExit(f"source dir does not exist: {source}")
        return source

    source = work_dir / "rime-wanxiang"
    if source.exists():
        shutil.rmtree(source)
    clone_ref = args.source_tag or args.source_branch
    run(
        [
            "git",
            "clone",
            "--depth",
            "1",
            "--branch",
            clone_ref,
            args.source_repo,
            str(source),
        ],
        cwd=work_dir,
    )
    return source


def write_zip(zip_path, entries):
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for entry_name, file_path in entries:
            info = zipfile.ZipInfo(entry_name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            zf.writestr(info, file_path.read_bytes())


def build_info(args, repo_root, source_commit, asset_name, zip_path, bin_infos, source_infos, grammar_info):
    release_url = f"https://github.com/lurixo/Aegis/releases/tag/{args.release_tag}"
    asset_url = f"https://github.com/lurixo/Aegis/releases/download/{args.release_tag}/{asset_name}"
    builder_commit = output(["git", "rev-parse", "HEAD"], cwd=repo_root)
    dirty = output(["git", "status", "--short"], cwd=repo_root)
    generated_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    return {
        "schema_version": 1,
        "schema_name": "aegis.resource-build-info",
        "generated_at": generated_at,
        "app": {
            "project": "Aegis",
            "repository": "https://github.com/lurixo/Aegis",
            "release_tag": args.release_tag,
        },
        "resources": [
            {
                "kind": "dictionary",
                "physical_asset": {
                    "name": asset_name,
                    "url": asset_url,
                    "release_tag": args.release_tag,
                    "release_url": release_url,
                    "prerelease": False,
                    "published_at": None,
                    "sha256": sha256_file(zip_path),
                    "size_bytes": zip_path.stat().st_size,
                },
                "source": {
                    "repo": args.source_repo_https,
                    "ref_type": "tag" if args.source_tag else "branch",
                    "tag": args.source_tag,
                    "branch": None if args.source_tag else args.source_branch,
                    "commit": source_commit,
                    "license": "CC-BY-4.0",
                    "attribution_file_in_pack": NOTICE_NAME,
                    "tables": TABLES,
                    "input_yaml_sha256": source_infos,
                },
                "build": {
                    "builder_path": "tools/src/main/kotlin/com/aegis/tools/DictBuilder.kt",
                    "builder_commit": builder_commit,
                    "builder_tree_dirty": bool(dirty),
                    "full_pack_parameters": {
                        "min_freq": 1,
                        "max_per_key": None,
                        "commands": [
                            "--out aegis_dict_full.bin --min-freq 1 --keytype letter --t2s-data tools/t2s-data",
                            "--out aegis_t9_full.bin --min-freq 1 --keytype digit --t2s-data tools/t2s-data",
                            "--out aegis_jianpin_full.bin --min-freq 1 --keytype initials --t2s-data tools/t2s-data",
                        ],
                    },
                    "seed_parameters": {
                        "min_freq": 400,
                        "max_per_key": None,
                        "keep_syllable_singles": 3,
                        "keep_syllable_singles_keytypes": ["letter", "digit"],
                        "commands": [
                            "--out aegis_dict.bin --min-freq 400 --keep-syllable-singles 3 --keytype letter --t2s-data tools/t2s-data",
                            "--out aegis_t9.bin --min-freq 400 --keep-syllable-singles 3 --keytype digit --t2s-data tools/t2s-data",
                            "--out aegis_jianpin.bin --min-freq 400 --keytype initials --t2s-data tools/t2s-data",
                        ],
                    },
                    "t2s_data": {
                        "path": "tools/t2s-data",
                        "provenance": "tools/t2s-data/PROVENANCE.md",
                        "license": "Apache-2.0 for the OpenCC tables (tools/t2s-data/LICENSE-OpenCC)",
                        "effect": "traditional and variant forms merge into their simplified image with frequency merging",
                    },
                    "output_bins": bin_infos,
                    "zip_packaging": {
                        "file_order": [NOTICE_NAME] + [item[0] for item in OUTPUTS],
                        "timestamp_utc": "1980-01-01T00:00:00Z",
                        "unix_mode": "0644",
                        "compression": "zip_deflated_level_9",
                    },
                },
                "attestation": {
                    "status": "not_attested",
                    "reproducibility_status": "build_inputs_recorded_but_unsigned",
                    "missing": [
                        "signature or attestation",
                        "independent external rebuild verification",
                    ],
                },
            }
        ],
        "external_resource_references": [grammar_info],
    }


def update_payload(build_info_json):
    dictionary = build_info_json["resources"][0]
    return {
        "schema_version": 1,
        "kind": "dictionary_update",
        "asset": dictionary["physical_asset"],
        "source": {
            "repo": dictionary["source"]["repo"],
            "ref_type": dictionary["source"]["ref_type"],
            "tag": dictionary["source"]["tag"],
            "branch": dictionary["source"]["branch"],
            "commit": dictionary["source"]["commit"],
        },
    }


def main(argv):
    parser = argparse.ArgumentParser(description="Build an immutable versioned Aegis dictionary release pack.")
    parser.add_argument("--release-tag", required=True, help="Versioned dictionary tag in dict-vX.Y.Z-rN form.")
    parser.add_argument("--output-dir", default="build/release-dictionary", help="Directory for generated artifacts.")
    parser.add_argument("--source-dir", help="Existing rime-wanxiang checkout to use instead of cloning.")
    parser.add_argument("--source-repo", default="https://github.com/amzxyz/rime-wanxiang.git")
    parser.add_argument("--source-repo-https", default="https://github.com/amzxyz/rime-wanxiang")
    parser.add_argument("--source-branch", default="wanxiang")
    parser.add_argument("--source-tag", required=True, help="Upstream stable vX.Y.Z tag embedded in the dictionary release tag.")
    parser.add_argument("--grammar-release-api", default=GRAMMAR_RELEASE_API)
    parser.add_argument("--grammar-release-json")
    args = parser.parse_args(argv)
    try:
        expected_source_tag = dictionary_release_source_tag(args.release_tag)
    except ValueError as error:
        parser.error(str(error))
    if args.source_tag != expected_source_tag:
        parser.error("--source-tag must exactly match the upstream version embedded in --release-tag")

    repo_root = Path(__file__).resolve().parents[2]
    output_dir = (repo_root / args.output_dir).resolve()
    work_dir = output_dir / "work"
    staging_dir = output_dir / "staging"
    work_dir.mkdir(parents=True, exist_ok=True)
    if staging_dir.exists():
        shutil.rmtree(staging_dir)
    staging_dir.mkdir(parents=True)

    source = ensure_source_checkout(args, work_dir)
    input_paths = [source / "dicts" / f"{table}.dict.yaml" for table in TABLES]
    missing = [str(path) for path in input_paths if not path.exists()]
    if missing:
        raise SystemExit("missing source tables:\n" + "\n".join(missing))

    run([str(repo_root / "gradlew"), ":tools:installDist"], cwd=repo_root)
    tool_bin = repo_root / "tools" / "build" / "install" / "tools" / "bin" / "tools"

    t2s_dir = repo_root / "tools" / "t2s-data"
    if not t2s_dir.exists():
        raise SystemExit(f"t2s data dir missing: {t2s_dir}")

    bin_infos = []
    for zip_entry, runtime_name, key_type in OUTPUTS:
        out_path = staging_dir / zip_entry
        run(
            [
                str(tool_bin),
                "--out",
                str(out_path),
                "--min-freq",
                "1",
                "--keytype",
                key_type,
                "--t2s-data",
                str(t2s_dir),
                *[str(path) for path in input_paths],
            ],
            cwd=repo_root,
        )
        bin_infos.append(
            {
                "zip_entry": zip_entry,
                "runtime_name": runtime_name,
                "sha256": sha256_file(out_path),
                "size_bytes": out_path.stat().st_size,
            }
        )

    asset_name = default_asset_name(args.release_tag)
    zip_path = output_dir / asset_name
    source_commit = output(["git", "rev-parse", "HEAD"], cwd=source)
    notice_path = staging_dir / NOTICE_NAME
    notice_path.write_bytes(
        attribution_text(args.source_repo_https, args.source_tag, args.source_branch, source_commit).encode("utf-8")
    )
    zip_entries = [(NOTICE_NAME, notice_path)] + [(zip_entry, staging_dir / zip_entry) for zip_entry, _, _ in OUTPUTS]
    write_zip(zip_path, zip_entries)

    source_infos = [
        {
            "table": table,
            "path": f"dicts/{table}.dict.yaml",
            "sha256": sha256_file(path),
            "size_bytes": path.stat().st_size,
        }
        for table, path in zip(TABLES, input_paths)
    ]
    info = build_info(
        args,
        repo_root,
        source_commit,
        asset_name,
        zip_path,
        bin_infos,
        source_infos,
        load_grammar_reference(args),
    )
    (output_dir / "aegis-build-info.json").write_text(json.dumps(info, ensure_ascii=True, indent=2) + "\n")
    (output_dir / "aegis-dictionary-update.json").write_text(json.dumps(update_payload(info), ensure_ascii=True, indent=2) + "\n")

    print("\nArtifacts:")
    print(zip_path)
    print(output_dir / "aegis-build-info.json")
    print(output_dir / "aegis-dictionary-update.json")
    print(f"\nPublish these files together on the immutable {args.release_tag} GitHub release; this script does not upload or tag.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
