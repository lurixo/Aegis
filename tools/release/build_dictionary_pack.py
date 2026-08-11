#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
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
LM_ENTRY = "aegis_lm.bin"
LM_RUNTIME_NAME = "aegis_lm.bin"
LM_COMPATIBILITY_PROFILE = "beta31-9484292"
LM_COMPATIBILITY_COMMIT = "9484292651903e245b88868a6171acf694763f69"
LM_COMPATIBILITY_BUILDER_BLOB = "570fc5085c0a63bdf9b8629a5e410b5a24616ff4"
LM_MIN_BIGRAM = 1
LM_BETA31_UPSTREAM_COMMIT = "351fd048b104c403e80e10a569f9a740d10753e1"
LM_BETA31_EXPECTED_SHA256 = "c3fc0a2891cfdeabf0a8fe92e6109da83209dc5852be24f0aedc7f598824790a"
LM_BETA31_EXPECTED_SIZE = 16_069_924
FIXED_JAVA_HOME = Path("/usr/lib/jvm/java-25-openjdk-amd64")
FIXED_JAVA_VERSION = [
    'openjdk version "25.0.3" 2026-04-21',
    "OpenJDK Runtime Environment (build 25.0.3+9-2-26.04.2-Ubuntu)",
    "OpenJDK 64-Bit Server VM (build 25.0.3+9-2-26.04.2-Ubuntu, mixed mode, sharing)",
]
FIXED_GRADLE_VERSION = "9.7.0"
FIXED_GRADLE_REVISION = "3defbfc59d757b873d787b2261de5c7f8a00970a"
TOOL_DISTRIBUTION_RELATIVE = Path("tools/build/install/tools")
TOOL_EXECUTABLE_RELATIVE = TOOL_DISTRIBUTION_RELATIVE / "bin/tools"
TOOL_LIBRARY_RELATIVE = TOOL_DISTRIBUTION_RELATIVE / "lib/tools.jar"
LM_BETA31_INPUT_BLOBS = {
    "dicts/zi.dict.yaml": "d8d1a52dfb1a1958adeee4253bef097904159909",
    "dicts/jichu.dict.yaml": "548693c88eda801b3b2904467a6b055d674f2d3c",
    "dicts/lianxiang.dict.yaml": "745b8331ddfca2c9da328238d257b669f7a36394",
    "dicts/cuoyin.dict.yaml": "d31dffa9a05da46696eb913cc955ebae0cd0b728",
    "dicts/duoyin.dict.yaml": "d2585943b7b5b2118f768c5b44cc6be5f2228966",
    "dicts/shici.dict.yaml": "cb30e8c31c26c9ffddb1067ae4b9d04802d53b76",
    "dicts/diming.dict.yaml": "18c717e9c119e2e68005717aedaf323f771c3d12",
    "dicts/yixue.dict.yaml": "9ae2135732e1600f1be9397ad2bf056a45ab8035",
    "dicts/huaxue.dict.yaml": "ba1a5ff0d240285f2062647d3b6ab5e93002f397",
    "dicts/yaopin.dict.yaml": "08b48d29571a0b81d79f213b6e55c810f033e452",
    "dicts/mingren.dict.yaml": "29aeb2c60925f123d8794c08902c64eef203ce42",
    "dicts/yiren.dict.yaml": "a63fc6f2d4aefb4fe27f528a26fda086b4c84c6e",
    "dicts/wuzhong.dict.yaml": "d6f2b520727e27773d82ba7032e7143063c09d4d",
    "dicts/renming.dict.yaml": "f2c90712e0816f77af8508e9323e8aa5d0adcef7",
}
LM_BETA31_T2S_BLOBS = {
    "TSCharacters.txt": "a236514576eed080868c64b4899c46315063ce02",
    "TSPhrases.txt": "36e4dec4df8990772886aeea965ac2e7526fb2a6",
    "adjudications.tsv": "2cbe5fd7819c1bafc10cb752239709d7453e69c8",
    "variant_to_simplified.tsv": "4381f87d0bfa5a70915ddf24a5cd9b3e6c2dfc9d",
}
PACK_ENTRIES = [NOTICE_NAME] + [item[0] for item in OUTPUTS] + [LM_ENTRY]
DANGEROUS_NEW_ENTRY_SUBSTRINGS = ("dict", "t9", "jianpin")
GRAMMAR_NAME = "wanxiang-lts-zh-hans.gram"
GRAMMAR_REPO_HTTPS = "https://github.com/amzxyz/RIME-LMDG"
GRAMMAR_RELEASE_API = "https://api.github.com/repos/amzxyz/RIME-LMDG/releases/tags/LTS"


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
        "  - derived character unigram and bigram statistics into the AEGL v1\n"
        "    language model (aegis_lm.bin);\n"
        "  - this full pack keeps every entry (min-freq 1).\n"
        "\n"
        "CC BY 4.0 requires this attribution to accompany the material. Aegis's own code\n"
        "is licensed GPL-3.0-only; this notice concerns the bundled third-party dictionary\n"
        "data only. The repository's THIRD_PARTY_LICENSES.md carries the full license texts.\n"
    )


def run(cmd, cwd, env=None):
    print("+", " ".join(str(part) for part in cmd), flush=True)
    subprocess.run(cmd, cwd=cwd, env=env, check=True)


def output(cmd, cwd, env=None):
    return subprocess.check_output(cmd, cwd=cwd, env=env, text=True).strip()


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def fixed_tool_environment():
    environment = dict(os.environ)
    for name in (
        "JAVA_HOME",
        "JAVA_TOOL_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "_JAVA_OPTIONS",
        "JAVA_OPTS",
        "TOOLS_OPTS",
        "GRADLE_OPTS",
    ):
        environment.pop(name, None)
    environment.update(
        {
            "JAVA_HOME": str(FIXED_JAVA_HOME),
            "PATH": f"{FIXED_JAVA_HOME}/bin:/usr/bin:/bin",
            "LANG": "C.UTF-8",
            "LC_ALL": "C.UTF-8",
            "TZ": "UTC",
        }
    )
    return environment


def fixed_java_identity(environment):
    executable = FIXED_JAVA_HOME / "bin/java"
    if FIXED_JAVA_HOME.is_symlink() or executable.is_symlink() or not executable.is_file():
        raise SystemExit(f"fixed Java runtime is unavailable: {executable}")
    version = subprocess.check_output(
        [str(executable), "-version"],
        env=environment,
        stderr=subprocess.STDOUT,
        text=True,
    ).strip().splitlines()
    if version != FIXED_JAVA_VERSION:
        raise SystemExit(f"fixed Java runtime drifted: {version!r} != {FIXED_JAVA_VERSION!r}")
    return {
        "java_home": str(FIXED_JAVA_HOME),
        "java_executable_sha256": sha256_file(executable),
        "version": version,
    }


def fixed_gradle_identity(repo_root, environment):
    text = output([str(repo_root / "gradlew"), "--version"], cwd=repo_root, env=environment)
    version_match = re.search(r"^Gradle ([^\n]+)$", text, re.MULTILINE)
    revision_match = re.search(r"^Revision:\s+([0-9a-f]+)$", text, re.MULTILINE)
    launcher_match = re.search(r"^Launcher JVM:\s+(.+)$", text, re.MULTILINE)
    daemon_match = re.search(r"^Daemon JVM:\s+(.+)$", text, re.MULTILINE)
    if (
        version_match is None
        or version_match.group(1) != FIXED_GRADLE_VERSION
        or revision_match is None
        or revision_match.group(1) != FIXED_GRADLE_REVISION
        or launcher_match is None
        or not launcher_match.group(1).startswith("25.0.3 ")
        or daemon_match is None
        or not daemon_match.group(1).startswith(str(FIXED_JAVA_HOME))
    ):
        raise SystemExit("fixed Gradle/Java toolchain drifted")
    wrapper_files = [
        repo_root / "gradlew",
        repo_root / "gradle/wrapper/gradle-wrapper.jar",
        repo_root / "gradle/wrapper/gradle-wrapper.properties",
    ]
    if any(path.is_symlink() or not path.is_file() for path in wrapper_files):
        raise SystemExit("Gradle wrapper files are unavailable or unsafe")
    return {
        "version": version_match.group(1),
        "revision": revision_match.group(1),
        "launcher_jvm": launcher_match.group(1),
        "daemon_jvm": daemon_match.group(1),
        "wrapper_files": [
            {
                "path": str(path.relative_to(repo_root)),
                "size_bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }
            for path in wrapper_files
        ],
    }


def tool_distribution_identity(repo_root):
    distribution = repo_root / TOOL_DISTRIBUTION_RELATIVE
    if distribution.is_symlink() or not distribution.is_dir():
        raise SystemExit(f"fixed tool distribution is unavailable: {distribution}")
    files = []
    for path in sorted(distribution.rglob("*")):
        if path.is_symlink():
            raise SystemExit(f"fixed tool distribution contains a symlink: {path}")
        if path.is_dir():
            continue
        if not path.is_file():
            raise SystemExit(f"fixed tool distribution contains a special file: {path}")
        files.append(
            {
                "path": str(path.relative_to(repo_root)),
                "mode": path.stat().st_mode & 0o777,
                "size_bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }
        )
    paths = {row["path"] for row in files}
    required = {str(TOOL_EXECUTABLE_RELATIVE), str(TOOL_LIBRARY_RELATIVE)}
    if not required.issubset(paths):
        raise SystemExit(f"fixed tool distribution is incomplete: {sorted(required - paths)}")
    launcher = repo_root / TOOL_EXECUTABLE_RELATIVE
    if not os.access(launcher, os.X_OK):
        raise SystemExit(f"fixed tool launcher is not executable: {launcher}")
    return {
        "path": str(TOOL_DISTRIBUTION_RELATIVE),
        "entry_count": len(files),
        "files": files,
    }


def current_tooling_identity(repo_root, environment=None):
    environment = fixed_tool_environment() if environment is None else environment
    return {
        "schema_version": 1,
        "java": fixed_java_identity(environment),
        "gradle": fixed_gradle_identity(repo_root, environment),
        "distribution": tool_distribution_identity(repo_root),
        "environment": {
            key: environment[key]
            for key in ("JAVA_HOME", "PATH", "LANG", "LC_ALL", "TZ")
        },
    }


def tooling_identity_sha256(identity):
    encoded = json.dumps(identity, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    return sha256_bytes(encoded.encode("utf-8"))


def require_safe_new_entry_names():
    names = [LM_ENTRY]
    if len(names) != len(set(names)):
        raise ValueError("downloadable component entry names must be unique")
    for name in names:
        lowered = name.lower()
        dangerous = [part for part in DANGEROUS_NEW_ENTRY_SUBSTRINGS if part in lowered]
        if dangerous:
            raise ValueError(f"unsafe downloadable component entry {name!r}: contains {dangerous}")


def component_info(zip_entry, runtime_name, kind, path, **extra):
    return {
        "zip_entry": zip_entry,
        "runtime_name": runtime_name,
        "kind": kind,
        "sha256": sha256_file(path),
        "size_bytes": path.stat().st_size,
        **extra,
    }


def require_pack_entries(pack, expected):
    with zipfile.ZipFile(pack) as archive:
        names = archive.namelist()
        if names != list(expected):
            raise ValueError(f"pack entries/order mismatch: {names}; expected {list(expected)}")
        for name in expected:
            info = archive.getinfo(name)
            if info.file_size <= 0:
                raise ValueError(f"pack entry is empty: {name}")
            if info.date_time != (1980, 1, 1, 0, 0, 0):
                raise ValueError(f"pack entry timestamp is not deterministic: {name}")
            if info.compress_type != zipfile.ZIP_DEFLATED:
                raise ValueError(f"pack entry compression is not deterministic: {name}")
            if info.external_attr >> 16 != 0o100644:
                raise ValueError(f"pack entry mode is not deterministic: {name}")
        return {name: archive.read(name) for name in expected}


def path_in_repo(repo_root, relative):
    target = Path(os.path.normpath(repo_root / relative))
    if repo_root not in target.parents:
        raise ValueError(f"git reported a path outside the repository: {relative!r}")
    return target


def tree_dirt(repo_root):
    fields = subprocess.check_output(
        ["git", "status", "--porcelain", "-z", "--untracked-files=all"],
        cwd=repo_root,
        text=True,
    ).split("\0")
    rows = []
    index = 0
    while index < len(fields):
        entry = fields[index]
        index += 1
        if not entry:
            continue
        row = {"status": entry[:2], "path": entry[3:]}
        if "R" in entry[:2] or "C" in entry[:2]:
            row["renamed_from"] = fields[index]
            index += 1
        target = path_in_repo(repo_root, row["path"])
        stat = target.stat() if target.is_file() else None
        row["sha256"] = sha256_file(target) if stat else None
        row["size_bytes"] = stat.st_size if stat else None
        rows.append(row)
    return rows


def default_asset_name(release_tag):
    match = re.fullmatch(r"v\d+\.\d+\.\d+-debug\.(\d+)", release_tag)
    if match:
        return f"aegis_dict_pack_debug{match.group(1)}.zip"
    safe = re.sub(r"[^A-Za-z0-9._-]+", "-", release_tag).strip("-")
    return f"aegis_dict_pack_{safe}.zip"


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
    tag = release.get("tag_name")
    if not isinstance(tag, str) or not re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9._-]*", tag):
        raise ValueError(f"{GRAMMAR_NAME} names no plain release tag: {tag!r}")
    expected_asset_url = f"{GRAMMAR_REPO_HTTPS}/releases/download/{tag}/{GRAMMAR_NAME}"
    if asset.get("browser_download_url") != expected_asset_url:
        raise ValueError(
            f"{GRAMMAR_NAME} is not served from {expected_asset_url}: {asset.get('browser_download_url')!r}"
        )
    expected_release_url = f"{GRAMMAR_REPO_HTTPS}/releases/tag/{tag}"
    if release.get("html_url") != expected_release_url:
        raise ValueError(
            f"{GRAMMAR_NAME} is not released at {expected_release_url}: {release.get('html_url')!r}"
        )
    return {
        "kind": "grammar_model",
        "physical_asset": {
            "name": GRAMMAR_NAME,
            "url": asset["browser_download_url"],
            "release_tag": tag,
            "release_url": release["html_url"],
            "prerelease": bool(release.get("prerelease")),
            "published_at": asset.get("updated_at") or release.get("published_at"),
            "sha256": digest.removeprefix("sha256:").lower(),
            "size_bytes": size,
            "github_asset_id": asset.get("id"),
        },
        "source": {
            "repo": GRAMMAR_REPO_HTTPS,
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
        try:
            head = output(["git", "rev-parse", "--verify", "HEAD^{commit}"], cwd=source)
            tag_commit = (
                output(
                    ["git", "rev-parse", "--verify", f"{args.source_tag}^{{commit}}"],
                    cwd=source,
                )
                if args.source_tag
                else head
            )
            dirty = output(["git", "status", "--short"], cwd=source)
        except subprocess.CalledProcessError as error:
            raise SystemExit("source dir is not a valid checkout of the requested source tag") from error
        if dirty:
            raise SystemExit("source dir must be clean")
        if head != tag_commit:
            raise SystemExit(f"source dir HEAD does not match source tag {args.source_tag}")
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


def ensure_beta31_lm_source(args, work_dir):
    if args.lm_reproduction_source_dir:
        source = Path(args.lm_reproduction_source_dir).resolve()
        if not source.is_dir():
            raise SystemExit(f"Beta.31 LM reproduction source missing: {source}")
        if output(["git", "rev-parse", "--verify", "HEAD^{commit}"], cwd=source) != LM_BETA31_UPSTREAM_COMMIT:
            raise SystemExit("Beta.31 LM reproduction source is at the wrong commit")
        if output(["git", "status", "--short"], cwd=source):
            raise SystemExit("Beta.31 LM reproduction source must be clean")
        return source

    source = work_dir / "rime-wanxiang-beta31-lm-reproduction"
    if source.exists():
        shutil.rmtree(source)
    run(["git", "init", "-q", str(source)], cwd=work_dir)
    run(
        ["git", "-C", str(source), "remote", "add", "origin", "https://github.com/amzxyz/rime-wanxiang.git"],
        cwd=work_dir,
    )
    run(
        ["git", "-C", str(source), "fetch", "--depth=1", "origin", LM_BETA31_UPSTREAM_COMMIT],
        cwd=work_dir,
    )
    run(["git", "-C", str(source), "checkout", "-q", "--detach", "FETCH_HEAD"], cwd=work_dir)
    if output(["git", "rev-parse", "HEAD"], cwd=source) != LM_BETA31_UPSTREAM_COMMIT:
        raise SystemExit("fetched Beta.31 LM reproduction source resolved to the wrong commit")
    return source


def lm_command(tool_bin, output_path, t2s_dir, source):
    input_paths = [source / "dicts" / f"{table}.dict.yaml" for table in TABLES]
    return [
        str(tool_bin),
        "lm",
        "--out",
        str(output_path),
        "--min-bigram",
        str(LM_MIN_BIGRAM),
        "--compatibility-profile",
        LM_COMPATIBILITY_PROFILE,
        "--t2s-data",
        str(t2s_dir),
        *[str(path) for path in input_paths],
    ]


def materialize_beta31_t2s(repo_root, work_dir):
    object_repo = repo_root
    missing_blobs = []
    for expected_blob in LM_BETA31_T2S_BLOBS.values():
        probe = subprocess.run(
            ["git", "cat-file", "-e", f"{expected_blob}^{{blob}}"],
            cwd=object_repo,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if probe.returncode != 0:
            missing_blobs.append(expected_blob)
    historical_commit = subprocess.run(
        ["git", "cat-file", "-e", f"{LM_COMPATIBILITY_COMMIT}^{{commit}}"],
        cwd=object_repo,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    if missing_blobs or historical_commit.returncode != 0:
        object_repo = work_dir / "aegis-beta31-lm-compatibility-source"
        if object_repo.exists():
            shutil.rmtree(object_repo)
        run(["git", "init", "-q", str(object_repo)], cwd=work_dir)
        run(
            [
                "git",
                "-C",
                str(object_repo),
                "fetch",
                "--depth=1",
                "https://github.com/lurixo/Aegis.git",
                LM_COMPATIBILITY_COMMIT,
            ],
            cwd=work_dir,
        )
        if output(["git", "rev-parse", "FETCH_HEAD"], cwd=object_repo) != LM_COMPATIBILITY_COMMIT:
            raise SystemExit("fetched Beta.31 LM compatibility source resolved to the wrong commit")
    builder_blob = output(
        ["git", "rev-parse", f"{LM_COMPATIBILITY_COMMIT}:tools/src/main/kotlin/com/aegis/tools/LmBuilder.kt"],
        cwd=object_repo,
    )
    if builder_blob != LM_COMPATIBILITY_BUILDER_BLOB:
        raise SystemExit(
            f"Beta.31 LM builder blob drift: {builder_blob} != {LM_COMPATIBILITY_BUILDER_BLOB}"
        )

    fixture = work_dir / "aegis-beta31-lm-t2s"
    if fixture.exists():
        shutil.rmtree(fixture)
    fixture.mkdir()
    actual_blobs = {}
    for name, expected_blob in LM_BETA31_T2S_BLOBS.items():
        historical_path = f"tools/t2s-data/{name}"
        actual_blob = output(
            ["git", "rev-parse", f"{LM_COMPATIBILITY_COMMIT}:{historical_path}"],
            cwd=object_repo,
        )
        if actual_blob != expected_blob:
            raise SystemExit(f"Beta.31 LM t2s blob drift: {name} {actual_blob} != {expected_blob}")
        data = subprocess.check_output(["git", "cat-file", "blob", actual_blob], cwd=object_repo)
        destination = fixture / name
        destination.write_bytes(data)
        if output(["git", "hash-object", str(destination)], cwd=repo_root) != expected_blob:
            raise SystemExit(f"materialized Beta.31 LM t2s bytes drifted: {name}")
        actual_blobs[name] = actual_blob
    return fixture, actual_blobs


def verify_beta31_lm_reproduction(args, repo_root, work_dir, output_dir, tool_bin):
    tool_environment = fixed_tool_environment()
    java_identity = fixed_java_identity(tool_environment)
    source = ensure_beta31_lm_source(args, work_dir)
    if list(LM_BETA31_INPUT_BLOBS) != [f"dicts/{table}.dict.yaml" for table in TABLES]:
        raise SystemExit("Beta.31 LM fixture table order drifted")
    actual_input_blobs = {}
    for path, expected_blob in LM_BETA31_INPUT_BLOBS.items():
        actual_blob = output(["git", "rev-parse", f"HEAD:{path}"], cwd=source)
        if actual_blob != expected_blob:
            raise SystemExit(f"Beta.31 LM input blob drift: {path} {actual_blob} != {expected_blob}")
        actual_input_blobs[path] = actual_blob
    historical_t2s_dir, actual_t2s_blobs = materialize_beta31_t2s(repo_root, work_dir)

    reproduction = output_dir / "aegis-lm-beta31-reproduction.bin"
    command = lm_command(tool_bin, reproduction, historical_t2s_dir, source)
    run(command, cwd=repo_root, env=tool_environment)
    model_shape = require_aegl_v1(reproduction)
    actual_sha = sha256_file(reproduction)
    actual_size = reproduction.stat().st_size
    if (actual_sha, actual_size) != (LM_BETA31_EXPECTED_SHA256, LM_BETA31_EXPECTED_SIZE):
        raise SystemExit(
            "Beta.31 LM byte reproduction failed: "
            f"sha256={actual_sha} size={actual_size}; "
            f"expected sha256={LM_BETA31_EXPECTED_SHA256} size={LM_BETA31_EXPECTED_SIZE}"
        )
    result = {
        "schema_version": 1,
        "kind": "aegis.lm.beta31-byte-reproduction",
        "status": "pass",
        "compatibility_profile": LM_COMPATIBILITY_PROFILE,
        "compatibility_source_commit": LM_COMPATIBILITY_COMMIT,
        "compatibility_builder_blob": LM_COMPATIBILITY_BUILDER_BLOB,
        "upstream_commit": LM_BETA31_UPSTREAM_COMMIT,
        "input_order": TABLES,
        "input_git_blobs": actual_input_blobs,
        "t2s_git_blobs": actual_t2s_blobs,
        "min_bigram": LM_MIN_BIGRAM,
        "java_runtime": java_identity,
        "output": {
            "path": reproduction.name,
            "sha256": actual_sha,
            "size_bytes": actual_size,
            **model_shape,
        },
        "command": [
            part.replace(str(source), "<beta31-upstream>")
            .replace(str(work_dir), "<work>")
            .replace(str(output_dir), "<output>")
            .replace(str(repo_root), "<aegis>")
            for part in command
        ],
    }
    write_json_atomic(output_dir / "aegis-lm-beta31-reproduction.json", result)
    return result


def write_zip(zip_path, entries):
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for entry_name, file_path in entries:
            info = zipfile.ZipInfo(entry_name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            zf.writestr(info, file_path.read_bytes())


def build_info(
    args,
    repo_root,
    source_commit,
    asset_name,
    zip_path,
    component_infos,
    source_infos,
    grammar_info,
    pack_state="intermediate",
    lm_reproduction=None,
    tooling=None,
):
    if not isinstance(tooling, dict) or tooling.get("schema_version") != 1:
        raise ValueError("fixed builder tooling identity is missing")
    release_url = f"https://github.com/lurixo/Aegis/releases/tag/{args.release_tag}"
    asset_url = f"https://github.com/lurixo/Aegis/releases/download/{args.release_tag}/{asset_name}"
    builder_commit = output(["git", "rev-parse", "HEAD"], cwd=repo_root)
    dirt = tree_dirt(repo_root)
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
                    "pack_state": pack_state,
                    "builder_path": "tools/src/main/kotlin/com/aegis/tools/DictBuilder.kt",
                    "builder_commit": builder_commit,
                    "builder_tree_dirty": bool(dirt),
                    "builder_tree_dirt": dirt,
                    "tooling": tooling,
                    "full_pack_parameters": {
                        "min_freq": 1,
                        "max_per_key": None,
                        "commands": [
                            "--out aegis_dict_full.bin --min-freq 1 --keytype letter --t2s-data tools/t2s-data",
                            "--out aegis_t9_full.bin --min-freq 1 --keytype digit --t2s-data tools/t2s-data",
                            "--out aegis_jianpin_full.bin --min-freq 1 --keytype initials --t2s-data tools/t2s-data",
                            f"lm --out {LM_ENTRY} --min-bigram {LM_MIN_BIGRAM} --compatibility-profile {LM_COMPATIBILITY_PROFILE} --t2s-data tools/t2s-data",
                        ],
                    },
                    "language_model": {
                        "format": "AEGL v1",
                        "compatibility_profile": LM_COMPATIBILITY_PROFILE,
                        "compatibility_source_commit": LM_COMPATIBILITY_COMMIT,
                        "min_bigram": LM_MIN_BIGRAM,
                        "beta31_reproduction": {
                            "upstream_commit": LM_BETA31_UPSTREAM_COMMIT,
                            "expected_sha256": LM_BETA31_EXPECTED_SHA256,
                            "expected_size_bytes": LM_BETA31_EXPECTED_SIZE,
                            "result": lm_reproduction,
                        },
                    },
                    "t2s_data": {
                        "path": "tools/t2s-data",
                        "provenance": "tools/t2s-data/PROVENANCE.md",
                        "license": "Apache-2.0 for the OpenCC tables (tools/t2s-data/LICENSE-OpenCC)",
                        "effect": "traditional and variant forms merge into their simplified image with frequency merging",
                    },
                    "output_bins": component_infos,
                    "zip_packaging": {
                        "file_order": PACK_ENTRIES,
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


def require_aegl_v1(path):
    data = path.read_bytes()
    if len(data) < 20 or data[:4] != b"AEGL" or struct.unpack_from("<i", data, 4)[0] != 1:
        raise ValueError(f"language model is not AEGL v1: {path}")
    num_chars = struct.unpack_from("<i", data, 8)[0]
    total_unigrams = struct.unpack_from("<q", data, 12)[0]
    if num_chars <= 0 or total_unigrams <= 0:
        raise ValueError(f"invalid AEGL v1 header counts: {path}")
    char_codes_offset = 20
    unigram_counts_offset = char_codes_offset + num_chars * 4
    row_totals_offset = unigram_counts_offset + num_chars * 8
    row_starts_offset = row_totals_offset + num_chars * 8
    num_bigrams_offset = row_starts_offset + (num_chars + 1) * 4
    if num_bigrams_offset + 4 > len(data):
        raise ValueError(f"truncated AEGL v1 header arrays: {path}")
    num_bigrams = struct.unpack_from("<i", data, num_bigrams_offset)[0]
    if num_bigrams < 0:
        raise ValueError(f"invalid AEGL v1 bigram count: {path}")
    bigram_targets_offset = num_bigrams_offset + 4
    bigram_counts_offset = bigram_targets_offset + num_bigrams * 4
    if bigram_counts_offset + num_bigrams * 8 != len(data):
        raise ValueError(f"invalid AEGL v1 file extent: {path}")

    previous_code = -1
    unigram_sum = 0
    for index in range(num_chars):
        code = struct.unpack_from("<i", data, char_codes_offset + index * 4)[0]
        count = struct.unpack_from("<q", data, unigram_counts_offset + index * 8)[0]
        if not (code > previous_code and 0 <= code <= 0x10FFFF and not 0xD800 <= code <= 0xDFFF):
            raise ValueError(f"invalid AEGL v1 character index: {path}")
        if count <= 0:
            raise ValueError(f"invalid AEGL v1 unigram count: {path}")
        previous_code = code
        unigram_sum += count
        if unigram_sum > 0x7FFF_FFFF_FFFF_FFFF:
            raise ValueError(f"overflowing AEGL v1 unigram total: {path}")
    if unigram_sum != total_unigrams:
        raise ValueError(f"AEGL v1 unigram total mismatch: {path}")

    row_starts = [
        struct.unpack_from("<i", data, row_starts_offset + index * 4)[0]
        for index in range(num_chars + 1)
    ]
    if row_starts[0] != 0 or row_starts[-1] != num_bigrams:
        raise ValueError(f"invalid AEGL v1 row boundary: {path}")
    previous_start = 0
    for start in row_starts:
        if not previous_start <= start <= num_bigrams:
            raise ValueError(f"invalid AEGL v1 row index: {path}")
        previous_start = start
    for row in range(num_chars):
        start, end = row_starts[row : row + 2]
        total = struct.unpack_from("<q", data, row_totals_offset + row * 8)[0]
        if total < 0 or (start != end and total <= 0):
            raise ValueError(f"invalid AEGL v1 row total: {path}")
        previous_target = -1
        retained = 0
        for index in range(start, end):
            target = struct.unpack_from("<i", data, bigram_targets_offset + index * 4)[0]
            count = struct.unpack_from("<q", data, bigram_counts_offset + index * 8)[0]
            if not 0 <= target < num_chars or target <= previous_target:
                raise ValueError(f"invalid AEGL v1 bigram index: {path}")
            if count <= 0:
                raise ValueError(f"invalid AEGL v1 bigram count: {path}")
            previous_target = target
            retained += count
            if retained > 0x7FFF_FFFF_FFFF_FFFF:
                raise ValueError(f"overflowing AEGL v1 bigram row: {path}")
        if retained > total:
            raise ValueError(f"invalid AEGL v1 row denominator: {path}")
    return {
        "format": "AEGL v1",
        "char_count": num_chars,
        "bigram_count": num_bigrams,
        "total_unigram_count": total_unigrams,
    }


def write_json_atomic(path, payload):
    path = Path(path)
    descriptor, temporary_name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output_file:
            output_file.write(json.dumps(payload, ensure_ascii=True, indent=2) + "\n")
            output_file.flush()
            os.fsync(output_file.fileno())
        replace_file_durable(temporary_name, path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def fsync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def replace_file_durable(source, destination):
    source = Path(source)
    destination = Path(destination)
    with source.open("rb") as source_file:
        os.fsync(source_file.fileno())
    os.replace(source, destination)
    fsync_directory(destination.parent)


def pack_asset_identity(pack):
    return (pack.name, pack.stat().st_size, sha256_file(pack))


def validate_update_document(build_info_json, update_json):
    if update_json != update_payload(build_info_json):
        raise ValueError("update-json metadata mismatch")


def validate_intermediate_metadata(pack, build_info_json, update_json):
    if build_info_json.get("schema_name") != "aegis.resource-build-info":
        raise ValueError("build-info schema mismatch")
    resources = build_info_json.get("resources") or []
    if len(resources) != 1 or resources[0].get("kind") != "dictionary":
        raise ValueError("build-info dictionary resource mismatch")
    resource = resources[0]
    build = resource.get("build") or {}
    if build.get("pack_state") != "intermediate":
        raise ValueError("finalization only accepts an intermediate pack")
    if (build.get("zip_packaging") or {}).get("file_order") != PACK_ENTRIES:
        raise ValueError("intermediate build-info file order mismatch")
    asset = resource.get("physical_asset") or {}
    identity = pack_asset_identity(pack)
    if (asset.get("name"), asset.get("size_bytes"), asset.get("sha256")) != identity:
        raise ValueError("intermediate build-info asset identity mismatch")
    validate_update_document(build_info_json, update_json)
    component_rows = build.get("output_bins") or []
    if [item.get("zip_entry") for item in component_rows] != PACK_ENTRIES[1:]:
        raise ValueError("intermediate component metadata order/set mismatch")
    components = {item["zip_entry"]: item for item in component_rows}
    return resource, components


def verify_component_metadata(payloads, components, names):
    for name in names:
        data = payloads[name]
        metadata = components.get(name) or {}
        if (metadata.get("size_bytes"), metadata.get("sha256")) != (
            len(data),
            sha256_bytes(data),
        ):
            raise ValueError(f"component metadata mismatch: {name}")


def collect_final_output_infos(staged):
    output_infos = [
        component_info(
            zip_entry,
            runtime_name,
            "dictionary",
            staged[zip_entry],
            key_type=key_type,
        )
        for zip_entry, runtime_name, key_type in OUTPUTS
    ]
    output_infos.append(
        component_info(
            LM_ENTRY,
            LM_RUNTIME_NAME,
            "language_model",
            staged[LM_ENTRY],
            format="AEGL v1",
            compatibility_profile=LM_COMPATIBILITY_PROFILE,
            min_bigram=LM_MIN_BIGRAM,
        )
    )
    return output_infos


def frozen_builder_commit(resource):
    builder_commit = (resource.get("build") or {}).get("builder_commit")
    if not re.fullmatch(r"[0-9a-f]{40}", builder_commit or ""):
        raise ValueError("build-info builder commit is not a fixed object id")
    return builder_commit


def apply_final_metadata(resource, pack, output_infos, builder_commit):
    build = resource["build"]
    tooling = build.get("tooling")
    if not isinstance(tooling, dict):
        raise ValueError("fixed finalizer tooling identity is missing")
    build["pack_state"] = "final"
    build["output_bins"] = output_infos
    build["zip_packaging"]["file_order"] = PACK_ENTRIES
    build["finalization"] = {
        "builder_commit": builder_commit,
        "builder_path": "tools/release/build_dictionary_pack.py",
        "tooling_identity_sha256": tooling_identity_sha256(tooling),
    }
    asset = resource["physical_asset"]
    asset["sha256"] = sha256_file(pack)
    asset["size_bytes"] = pack.stat().st_size
    return asset


def validate_final_metadata(pack, build_info_json, update_json, output_infos):
    if build_info_json.get("schema_name") != "aegis.resource-build-info":
        raise ValueError("build-info schema mismatch")
    resources = build_info_json.get("resources") or []
    if len(resources) != 1 or resources[0].get("kind") != "dictionary":
        raise ValueError("build-info dictionary resource mismatch")
    resource = resources[0]
    build = resource.get("build") or {}
    if build.get("pack_state") != "final":
        raise ValueError("final build-info pack state mismatch")
    if (build.get("zip_packaging") or {}).get("file_order") != PACK_ENTRIES:
        raise ValueError("final build-info file order mismatch")
    finalization = build.get("finalization") or {}
    if (
        finalization.get("builder_path") != "tools/release/build_dictionary_pack.py"
        or finalization.get("builder_commit") != frozen_builder_commit(resource)
        or finalization.get("tooling_identity_sha256")
        != tooling_identity_sha256(build.get("tooling") or {})
    ):
        raise ValueError("finalization metadata mismatch")
    if build.get("output_bins") != output_infos:
        raise ValueError("final component metadata mismatch")
    asset = resource.get("physical_asset") or {}
    identity = pack_asset_identity(pack)
    if (asset.get("name"), asset.get("size_bytes"), asset.get("sha256")) != identity:
        raise ValueError("final build-info asset identity mismatch")
    validate_update_document(build_info_json, update_json)
    return resource


def finalize_main(argv):
    parser = argparse.ArgumentParser(description="Finalize an injected Aegis dictionary pack.")
    parser.add_argument("--pack", required=True)
    parser.add_argument("--build-info", required=True)
    parser.add_argument("--update-json", required=True)
    args = parser.parse_args(argv)

    require_safe_new_entry_names()
    repo_root = Path(__file__).resolve().parents[2]
    pack = Path(args.pack).resolve()
    build_info_path = Path(args.build_info).resolve()
    update_json_path = Path(args.update_json).resolve()
    for required in (pack, build_info_path, update_json_path):
        if not required.is_file():
            raise SystemExit(f"finalization input missing: {required}")

    payloads = require_pack_entries(pack, PACK_ENTRIES)
    if "derived character unigram and bigram statistics" not in payloads[NOTICE_NAME].decode("utf-8"):
        raise SystemExit("pack NOTICE does not attribute the language model")
    build_info_json = json.loads(build_info_path.read_text(encoding="utf-8"))
    update_json = json.loads(update_json_path.read_text(encoding="utf-8"))
    resources = build_info_json.get("resources") or []
    if len(resources) != 1 or resources[0].get("kind") != "dictionary":
        raise ValueError("build-info dictionary resource mismatch")
    builder_commit = frozen_builder_commit(resources[0])
    current_builder_commit = output(["git", "rev-parse", "HEAD"], cwd=repo_root)
    if current_builder_commit != builder_commit:
        raise ValueError(
            "current finalizer commit differs from the frozen builder"
        )
    build = resources[0].get("build") or {}
    frozen_tooling = build.get("tooling")
    if not isinstance(frozen_tooling, dict):
        raise ValueError("fixed finalizer tooling identity is missing")
    tool_environment = fixed_tool_environment()
    if current_tooling_identity(repo_root, tool_environment) != frozen_tooling:
        raise ValueError("current finalizer tooling differs from the frozen builder tooling")
    frozen_tree_dirt = build.get("builder_tree_dirt")
    if (
        not isinstance(frozen_tree_dirt, list)
        or build.get("builder_tree_dirty") is not bool(frozen_tree_dirt)
        or tree_dirt(repo_root) != frozen_tree_dirt
    ):
        raise ValueError("current finalizer tree differs from the frozen builder tree")

    with tempfile.TemporaryDirectory(prefix="aegis-pack-finalize-", dir=pack.parent) as directory:
        staging = Path(directory)
        staged = {}
        for name, data in payloads.items():
            path = staging / name
            path.write_bytes(data)
            staged[name] = path
        require_aegl_v1(staged[LM_ENTRY])
        output_infos = collect_final_output_infos(staged)

        pack_state = build.get("pack_state")
        if pack_state == "intermediate":
            resource, existing_components = validate_intermediate_metadata(
                pack, build_info_json, update_json
            )
            verify_component_metadata(payloads, existing_components, PACK_ENTRIES[1:])
            asset = apply_final_metadata(resource, pack, output_infos, builder_commit)
            write_json_atomic(build_info_path, build_info_json)
            write_json_atomic(update_json_path, update_payload(build_info_json))
            print(f"finalized {pack}: sha256={asset['sha256']} size={asset['size_bytes']}")
            return 0
        if pack_state != "final":
            raise ValueError(f"invalid build-info pack state: {pack_state!r}")

        validate_final_metadata(pack, build_info_json, update_json, output_infos)
        print(
            f"already finalized {pack}: "
            f"sha256={sha256_file(pack)} size={pack.stat().st_size}"
        )
        return 0


def main(argv):
    if argv and argv[0] == "finalize":
        return finalize_main(argv[1:])
    require_safe_new_entry_names()
    parser = argparse.ArgumentParser(description="Build the latest Aegis full dictionary release pack.")
    parser.add_argument("--release-tag", required=True, help="GitHub release tag that will host the dictionary asset (use dict-latest for the rolling production dictionary pack).")
    parser.add_argument("--output-dir", default="build/release-dictionary", help="Directory for generated artifacts.")
    parser.add_argument("--source-dir", help="Existing rime-wanxiang checkout to use instead of cloning.")
    parser.add_argument("--source-repo", default="https://github.com/amzxyz/rime-wanxiang.git")
    parser.add_argument("--source-repo-https", default="https://github.com/amzxyz/rime-wanxiang")
    parser.add_argument("--source-branch", default="wanxiang")
    parser.add_argument("--source-tag", help="Upstream release tag to pin (records source.tag and clones this tag instead of the branch HEAD). Prefer the latest stable tag that carries the dicts/ tables.")
    parser.add_argument("--lm-reproduction-source-dir", help="Optional clean checkout of the fixed Beta.31 LM upstream commit; otherwise the verifier fetches it from the official repository.")
    parser.add_argument("--asset-name", help="Dictionary ZIP asset name. Defaults to the debug.13 naming pattern.")
    parser.add_argument("--grammar-release-api", default=GRAMMAR_RELEASE_API)
    parser.add_argument("--grammar-release-json")
    args = parser.parse_args(argv)

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

    tool_environment = fixed_tool_environment()
    fixed_java_identity(tool_environment)
    tool_distribution = repo_root / TOOL_DISTRIBUTION_RELATIVE
    if tool_distribution.exists():
        if tool_distribution.is_symlink() or not tool_distribution.is_dir():
            raise SystemExit(f"unsafe existing tool distribution path: {tool_distribution}")
        shutil.rmtree(tool_distribution)
    run(
        [str(repo_root / "gradlew"), ":tools:installDist"],
        cwd=repo_root,
        env=tool_environment,
    )
    tool_bin = repo_root / TOOL_EXECUTABLE_RELATIVE
    tooling = current_tooling_identity(repo_root, tool_environment)

    t2s_dir = repo_root / "tools" / "t2s-data"
    if not t2s_dir.exists():
        raise SystemExit(f"t2s data dir missing: {t2s_dir}")
    lm_reproduction = verify_beta31_lm_reproduction(
        args,
        repo_root,
        work_dir,
        output_dir,
        tool_bin,
    )

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
            env=tool_environment,
        )
        bin_infos.append(
            component_info(
                zip_entry,
                runtime_name,
                "dictionary",
                out_path,
                key_type=key_type,
            )
        )

    lm_path = staging_dir / LM_ENTRY
    run(
        lm_command(tool_bin, lm_path, t2s_dir, source),
        cwd=repo_root,
        env=tool_environment,
    )
    require_aegl_v1(lm_path)
    bin_infos.append(
        component_info(
            LM_ENTRY,
            LM_RUNTIME_NAME,
            "language_model",
            lm_path,
            format="AEGL v1",
            compatibility_profile=LM_COMPATIBILITY_PROFILE,
            min_bigram=LM_MIN_BIGRAM,
        )
    )

    asset_name = args.asset_name or default_asset_name(args.release_tag)
    zip_path = output_dir / asset_name
    source_commit = output(["git", "rev-parse", "HEAD"], cwd=source)
    notice_path = staging_dir / NOTICE_NAME
    notice_path.write_bytes(
        attribution_text(args.source_repo_https, args.source_tag, args.source_branch, source_commit).encode("utf-8")
    )
    zip_entries = [(NOTICE_NAME, notice_path)] + [
        (name, staging_dir / name) for name in PACK_ENTRIES[1:]
    ]
    write_zip(zip_path, zip_entries)
    require_pack_entries(zip_path, PACK_ENTRIES)

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
        pack_state="intermediate",
        lm_reproduction=lm_reproduction,
        tooling=tooling,
    )
    (output_dir / "aegis-build-info.json").write_text(json.dumps(info, ensure_ascii=True, indent=2) + "\n")
    (output_dir / "aegis-dictionary-update.json").write_text(json.dumps(update_payload(info), ensure_ascii=True, indent=2) + "\n")

    print("\nArtifacts:")
    print(zip_path)
    print(output_dir / "aegis-build-info.json")
    print(output_dir / "aegis-dictionary-update.json")
    print("\nThis is an intermediate four-component pack and MUST NOT be published.")
    print("After the automation overlay is injected, run this script's finalize subcommand.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
