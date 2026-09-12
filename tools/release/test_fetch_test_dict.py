#!/usr/bin/env python3
#
# SPDX-License-Identifier: GPL-3.0-only
#

import hashlib
import io
import json
import sys
import tempfile
import unittest
import urllib.error
from http.client import HTTPResponse, IncompleteRead
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fetch_test_dict as ftd


def http_error(code):
    return urllib.error.HTTPError("https://example.invalid/asset", code, "stub", {}, io.BytesIO(b""))


class RecordingClock:
    def __init__(self, step=0.0):
        self.now = 0.0
        self.step = step
        self.slept = []

    def sleep(self, seconds):
        self.slept.append(seconds)
        self.now += seconds

    def monotonic(self):
        self.now += self.step
        return self.now


class ScriptedResponse:
    def __init__(self, chunks, length=None):
        self.chunks = list(chunks)
        self.headers = {} if length is None else {"Content-Length": str(length)}
        self.closed = False

    def read(self, size=-1):
        chunk = self.chunks.pop(0) if self.chunks else b""
        if isinstance(chunk, Exception):
            raise chunk
        return chunk

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.closed = True


class BoundedRetryTest(unittest.TestCase):
    URL = "https://example.invalid/asset"

    def test_a_transient_404_is_retried_until_it_succeeds(self):
        clock = RecordingClock()
        responses = [http_error(404), http_error(404), io.BytesIO(b"payload")]

        def urlopen(request, timeout=None):
            outcome = responses.pop(0)
            if isinstance(outcome, Exception):
                raise outcome
            return outcome

        with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=urlopen):
            self.assertEqual(
                b"payload", ftd.http_get(self.URL, 11, sleep=clock.sleep, clock=clock.monotonic)
            )
        self.assertEqual([], responses)
        self.assertEqual([2.0, 4.0], clock.slept)

    def test_every_retryable_status_and_transport_error_is_retried(self):
        for failure in [
            http_error(404),
            http_error(408),
            http_error(425),
            http_error(429),
            http_error(500),
            http_error(502),
            http_error(503),
            http_error(504),
            urllib.error.URLError("connection reset"),
            TimeoutError("read timed out"),
            ConnectionResetError("connection reset"),
            IncompleteRead(b"partial", 12),
        ]:
            with self.subTest(failure=type(failure).__name__ + str(getattr(failure, "code", ""))):
                clock = RecordingClock()
                responses = [failure, io.BytesIO(b"ok")]

                def urlopen(request, timeout=None):
                    outcome = responses.pop(0)
                    if isinstance(outcome, Exception):
                        raise outcome
                    return outcome

                with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=urlopen):
                    self.assertEqual(
                        b"ok", ftd.http_get(self.URL, 11, sleep=clock.sleep, clock=clock.monotonic)
                    )
                self.assertEqual([2.0], clock.slept)

    def test_a_non_retryable_status_fails_on_the_first_attempt(self):
        clock = RecordingClock()
        with mock.patch.object(
            ftd.urllib.request, "urlopen", side_effect=http_error(403)
        ) as urlopen:
            with self.assertRaisesRegex(SystemExit, "failed: HTTP Error 403"):
                ftd.http_get(self.URL, 11, sleep=clock.sleep, clock=clock.monotonic)
        self.assertEqual(1, urlopen.call_count)
        self.assertEqual([], clock.slept)

    def test_persistent_failure_still_exits_non_zero_after_a_bounded_number_of_attempts(self):
        clock = RecordingClock()
        with mock.patch.object(
            ftd.urllib.request, "urlopen", side_effect=http_error(404)
        ) as urlopen:
            with self.assertRaisesRegex(SystemExit, "failed after bounded retries"):
                ftd.http_get(self.URL, 11, sleep=clock.sleep, clock=clock.monotonic)
        self.assertEqual(ftd.RETRY_ATTEMPTS, urlopen.call_count)
        self.assertEqual(ftd.RETRY_ATTEMPTS - 1, len(clock.slept))
        self.assertLessEqual(sum(clock.slept), ftd.RETRY_WINDOW_SECONDS)

    def test_no_request_starts_when_backoff_reaches_the_deadline(self):
        clock = RecordingClock()
        with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=http_error(503)) as urlopen:
            with self.assertRaisesRegex(SystemExit, "failed after bounded retries"):
                ftd.http_get(
                    self.URL,
                    30,
                    attempts=40,
                    base_delay=8.0,
                    window=20.0,
                    sleep=clock.sleep,
                    clock=clock.monotonic,
                )
        self.assertEqual([8.0, 12.0], clock.slept)
        self.assertEqual(2, urlopen.call_count)
        self.assertEqual([20.0, 12.0], [call.kwargs["timeout"] for call in urlopen.call_args_list])

    def test_an_expired_window_prevents_the_first_request(self):
        clock = RecordingClock(step=30.0)
        with mock.patch.object(ftd.urllib.request, "urlopen") as urlopen:
            with self.assertRaisesRegex(SystemExit, "failed after bounded retries"):
                ftd.http_get(
                    self.URL,
                    11,
                    attempts=9,
                    base_delay=1.0,
                    window=20.0,
                    sleep=clock.sleep,
                    clock=clock.monotonic,
                )
        self.assertEqual([], clock.slept)
        self.assertEqual(0, urlopen.call_count)

    def test_a_failed_attempt_that_spends_the_window_does_not_retry(self):
        clock = RecordingClock()

        def urlopen(request, timeout=None):
            clock.now += 21.0
            raise TimeoutError("read timed out")

        with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=urlopen) as opened:
            with self.assertRaisesRegex(SystemExit, "failed after bounded retries"):
                ftd.http_get(self.URL, 60, window=20, sleep=clock.sleep, clock=clock.monotonic)
        self.assertEqual(1, opened.call_count)
        self.assertEqual(20, opened.call_args.kwargs["timeout"])
        self.assertEqual([], clock.slept)

    def test_each_attempt_caps_socket_timeout_by_remaining_budget(self):
        clock = RecordingClock()
        responses = [http_error(503), io.BytesIO(b"ok")]

        def urlopen(request, timeout=None):
            clock.now += 5.0
            outcome = responses.pop(0)
            if isinstance(outcome, Exception):
                raise outcome
            return outcome

        with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=urlopen) as opened:
            self.assertEqual(
                b"ok",
                ftd.http_get(self.URL, 15, window=20, sleep=clock.sleep, clock=clock.monotonic),
            )
        self.assertEqual([15, 13], [call.kwargs["timeout"] for call in opened.call_args_list])

    def test_a_transfer_already_running_can_finish_after_the_retry_start_window(self):
        clock = RecordingClock()

        def urlopen(request, timeout=None):
            clock.now += 21.0
            return io.BytesIO(b"ok")

        with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=urlopen) as opened:
            self.assertEqual(
                b"ok",
                ftd.http_get(self.URL, 60, window=20, sleep=clock.sleep, clock=clock.monotonic),
            )
        self.assertEqual(1, opened.call_count)
        self.assertEqual(20, opened.call_args.kwargs["timeout"])


class ResolutionAfterTransientFailuresTest(unittest.TestCase):
    def manifest(self):
        return json.dumps(
            {
                "schema_version": 1,
                "kind": "dictionary_update",
                "asset": {
                    "name": f"aegis_dict_pack_{ftd.DICT_LATEST_TAG}.zip",
                    "url": (
                        "https://github.com/lurixo/Aegis/releases/download/"
                        f"{ftd.DICT_LATEST_TAG}/aegis_dict_pack_{ftd.DICT_LATEST_TAG}.zip"
                    ),
                    "sha256": "ab" * 32,
                },
            }
        ).encode("utf-8")

    def test_the_manifest_resolves_after_a_transient_404(self):
        responses = [http_error(404), io.BytesIO(self.manifest())]

        def urlopen(request, timeout=None):
            outcome = responses.pop(0)
            if isinstance(outcome, Exception):
                raise outcome
            return outcome

        with mock.patch.object(ftd.time, "sleep") as sleep, mock.patch.object(
            ftd.urllib.request, "urlopen", side_effect=urlopen
        ):
            url, sha256, name = ftd.resolve_asset(ftd.MANIFEST_URL)
        self.assertEqual(f"aegis_dict_pack_{ftd.DICT_LATEST_TAG}.zip", name)
        self.assertEqual("ab" * 32, sha256)
        self.assertEqual([mock.call(2.0)], sleep.call_args_list)
        self.assertEqual([], responses)

    def test_a_download_retries_transient_http_errors_and_cleans_up_on_failure(self):
        payload = b"pack" * 512
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "aegis_dict_pack.zip"
            responses = [http_error(404), io.BytesIO(payload)]

            def urlopen(request, timeout=None):
                outcome = responses.pop(0)
                if isinstance(outcome, Exception):
                    raise outcome
                return outcome

            with mock.patch.object(ftd.time, "sleep"), mock.patch.object(
                ftd.urllib.request, "urlopen", side_effect=urlopen
            ):
                ftd.download_to("https://example.invalid/pack.zip", destination, 13)
            self.assertEqual(payload, destination.read_bytes())
            self.assertEqual(
                hashlib.sha256(payload).hexdigest(), ftd.sha256_file(destination)
            )
            self.assertFalse(destination.with_name(destination.name + ".part").exists())

            destination.unlink()
            with mock.patch.object(ftd.time, "sleep"), mock.patch.object(
                ftd.urllib.request, "urlopen", side_effect=http_error(404)
            ):
                with self.assertRaisesRegex(SystemExit, "failed after bounded retries"):
                    ftd.download_to("https://example.invalid/pack.zip", destination, 13)
            self.assertFalse(destination.exists())
            self.assertFalse(destination.with_name(destination.name + ".part").exists())


class BodyTransferRetryTest(unittest.TestCase):
    URL = "https://example.invalid/asset"

    def test_metadata_body_failures_retry_the_entire_response(self):
        for failure in (
            ConnectionResetError("reset during body"),
            TimeoutError("timeout during body"),
            IncompleteRead(b"partial", 10),
        ):
            with self.subTest(failure=type(failure).__name__):
                clock = RecordingClock()
                broken = ScriptedResponse([failure])
                success = ScriptedResponse([b"complete body"])
                with mock.patch.object(
                    ftd.urllib.request, "urlopen", side_effect=[broken, success]
                ) as opened:
                    self.assertEqual(
                        b"complete body",
                        ftd.http_get(self.URL, 30, sleep=clock.sleep, clock=clock.monotonic),
                    )
                self.assertEqual(2, opened.call_count)
                self.assertEqual([2.0], clock.slept)
                self.assertTrue(broken.closed)
                self.assertTrue(success.closed)

    def test_both_metadata_resolvers_retry_a_body_reset(self):
        grammar = json.dumps(
            {
                "tag_name": ftd.GRAMMAR_TAG,
                "assets": [{
                    "name": ftd.GRAMMAR_NAME,
                    "digest": "sha256:" + "cd" * 32,
                    "size": 2048,
                    "browser_download_url": ftd.GRAMMAR_URL,
                }],
            }
        ).encode("utf-8")
        manifest = ResolutionAfterTransientFailuresTest().manifest()
        for resolve, url, payload, expected in (
            (ftd.resolve_asset, ftd.MANIFEST_URL, manifest, "ab" * 32),
            (ftd.resolve_grammar_asset, ftd.GRAMMAR_RELEASE_API, grammar, "cd" * 32),
        ):
            with self.subTest(resolve=resolve.__name__):
                broken = ScriptedResponse([ConnectionResetError("reset during body")])
                with mock.patch.object(ftd.time, "sleep"), mock.patch.object(
                    ftd.urllib.request, "urlopen", side_effect=[broken, io.BytesIO(payload)]
                ) as opened:
                    self.assertEqual(expected, resolve(url)[1])
                self.assertEqual(2, opened.call_count)
                self.assertTrue(broken.closed)

    def test_persistent_body_failure_exhausts_attempts_and_closes_each_response(self):
        clock = RecordingClock()
        responses = [ScriptedResponse([IncompleteRead(b"partial", 5)]) for _ in range(3)]
        with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=responses) as opened:
            with self.assertRaisesRegex(SystemExit, "failed after bounded retries"):
                ftd.http_get(
                    self.URL, 30, attempts=3, sleep=clock.sleep, clock=clock.monotonic
                )
        self.assertEqual(3, opened.call_count)
        self.assertEqual([2.0, 4.0], clock.slept)
        self.assertTrue(all(response.closed for response in responses))

    def test_truncated_http_responses_retry_for_metadata_and_streamed_downloads(self):
        incomplete_responses = (
            b"HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\nshort",
            b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n9\r\nshort",
        )
        for wire_bytes in incomplete_responses:
            for download in (False, True):
                with self.subTest(wire_bytes=wire_bytes, download=download):
                    clock = RecordingClock()
                    socket = mock.Mock()
                    socket.makefile.return_value = io.BytesIO(wire_bytes)
                    broken = HTTPResponse(socket)
                    broken.begin()
                    with tempfile.TemporaryDirectory() as directory, mock.patch.object(
                        ftd.urllib.request, "urlopen",
                        side_effect=[broken, io.BytesIO(b"complete")],
                    ) as opened:
                        if download:
                            destination = Path(directory) / "asset"
                            ftd.download_to(
                                self.URL, destination, 30,
                                sleep=clock.sleep, clock=clock.monotonic,
                            )
                            self.assertEqual(b"complete", destination.read_bytes())
                            self.assertFalse(destination.with_name("asset.part").exists())
                        else:
                            self.assertEqual(
                                b"complete",
                                ftd.http_get(
                                    self.URL, 30, sleep=clock.sleep, clock=clock.monotonic
                                ),
                            )
                    self.assertEqual(2, opened.call_count)
                    self.assertTrue(broken.closed)

    def test_download_restarts_from_zero_after_body_failure_or_short_content_length(self):
        failures = (
            ([b"discarded prefix", ConnectionResetError("reset")], None),
            ([b"discarded prefix", TimeoutError("timeout")], None),
            ([b"discarded prefix", IncompleteRead(b"partial", 5)], None),
            ([b"discarded prefix"], 100),
        )
        for chunks, length in failures:
            with self.subTest(chunks=chunks, length=length), tempfile.TemporaryDirectory() as directory:
                clock = RecordingClock()
                destination = Path(directory) / "asset"
                part = destination.with_name(destination.name + ".part")
                part.write_bytes(b"stale partial")
                broken = ScriptedResponse(chunks, length)
                success = ScriptedResponse([b"complete", b" payload"], 16)
                responses = [broken, success]

                def urlopen(request, timeout=None):
                    self.assertFalse(part.exists())
                    return responses.pop(0)

                with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=urlopen) as opened:
                    ftd.download_to(
                        self.URL, destination, 30, sleep=clock.sleep, clock=clock.monotonic
                    )
                self.assertEqual(2, opened.call_count)
                self.assertEqual(b"complete payload", destination.read_bytes())
                self.assertFalse(part.exists())
                self.assertTrue(broken.closed)
                self.assertTrue(success.closed)

    def test_persistent_download_body_failures_remove_part_and_preserve_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            clock = RecordingClock()
            destination = Path(directory) / "asset"
            destination.write_bytes(b"previous complete asset")
            part = destination.with_name(destination.name + ".part")
            responses = [
                ScriptedResponse([b"partial", ConnectionResetError("reset")])
                for _ in range(3)
            ]
            with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=responses) as opened:
                with self.assertRaisesRegex(SystemExit, "failed after bounded retries"):
                    ftd.download_to(
                        self.URL, destination, 30, attempts=3,
                        sleep=clock.sleep, clock=clock.monotonic,
                    )
            self.assertEqual(3, opened.call_count)
            self.assertEqual(b"previous complete asset", destination.read_bytes())
            self.assertFalse(part.exists())
            self.assertTrue(all(response.closed for response in responses))

    def test_an_expired_download_window_cleans_stale_part_without_starting_a_request(self):
        with tempfile.TemporaryDirectory() as directory:
            clock = RecordingClock()
            destination = Path(directory) / "asset"
            part = destination.with_name(destination.name + ".part")
            part.write_bytes(b"stale partial")
            with mock.patch.object(ftd.urllib.request, "urlopen") as opened:
                with self.assertRaisesRegex(SystemExit, "before the first attempt"):
                    ftd.download_to(
                        self.URL, destination, 30, window=0,
                        sleep=clock.sleep, clock=clock.monotonic,
                    )
            opened.assert_not_called()
            self.assertFalse(destination.exists())
            self.assertFalse(part.exists())

    def test_a_download_http_403_fails_immediately_and_removes_stale_part(self):
        with tempfile.TemporaryDirectory() as directory:
            clock = RecordingClock()
            destination = Path(directory) / "asset"
            part = destination.with_name(destination.name + ".part")
            part.write_bytes(b"stale partial")
            with mock.patch.object(
                ftd.urllib.request, "urlopen", side_effect=http_error(403)
            ) as opened:
                with self.assertRaisesRegex(SystemExit, "failed: HTTP Error 403"):
                    ftd.download_to(
                        self.URL, destination, 30, sleep=clock.sleep, clock=clock.monotonic
                    )
            self.assertEqual(1, opened.call_count)
            self.assertEqual([], clock.slept)
            self.assertFalse(destination.exists())
            self.assertFalse(part.exists())

    def test_sha_mismatches_remain_fatal_and_remove_the_download(self):
        payload = b"incorrect payload" * 256
        expected = "ab" * 32
        for kind in ("pack", "grammar"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                destination = Path(directory) / "asset"
                with mock.patch.object(
                    ftd.urllib.request, "urlopen", return_value=io.BytesIO(payload)
                ) as opened:
                    with self.assertRaisesRegex(SystemExit, "mismatch"):
                        if kind == "pack":
                            ftd.ensure_pack(self.URL, expected, destination, None, 30)
                        else:
                            ftd.ensure_grammar(self.URL, expected, len(payload), destination, 30)
                self.assertEqual(1, opened.call_count)
                self.assertFalse(destination.exists())
                self.assertFalse(destination.with_name(destination.name + ".part").exists())


class CommittedPinTest(unittest.TestCase):
    PIN_SHA = "11" * 32
    LIVE_SHA = "22" * 32
    GRAMMAR_PIN_SHA = "33" * 32
    GRAMMAR_LIVE_SHA = "44" * 32
    GRAMMAR_SIZE = 420343852

    def build_info(self, pack_sha256=PIN_SHA, grammar_sha256=GRAMMAR_PIN_SHA, **overrides):
        document = {
            "schema_version": 1,
            "schema_name": ftd.BUILD_INFO_SCHEMA,
            "resources": [
                {
                    "kind": "dictionary",
                    "physical_asset": {
                        "name": ftd.PACK_NAME,
                        "url": ftd.PACK_URL,
                        "sha256": pack_sha256,
                        "size_bytes": 103958228,
                    },
                }
            ],
            "external_resource_references": [
                {
                    "kind": "grammar_model",
                    "physical_asset": {
                        "name": ftd.GRAMMAR_NAME,
                        "url": ftd.GRAMMAR_URL,
                        "sha256": grammar_sha256,
                        "size_bytes": self.GRAMMAR_SIZE,
                    },
                }
            ],
        }
        document.update(overrides)
        return document

    def written(self, directory, document):
        path = Path(directory) / ftd.BUILD_INFO_NAME
        path.write_text(json.dumps(document), encoding="utf-8")
        return path

    def live_manifest(self, sha256):
        return json.dumps(
            {
                "schema_version": 1,
                "kind": "dictionary_update",
                "asset": {"name": ftd.PACK_NAME, "url": ftd.PACK_URL, "sha256": sha256},
            }
        ).encode("utf-8")

    def live_grammar_release(self, sha256):
        return json.dumps(
            {
                "tag_name": ftd.GRAMMAR_TAG,
                "assets": [
                    {
                        "name": ftd.GRAMMAR_NAME,
                        "browser_download_url": ftd.GRAMMAR_URL,
                        "digest": f"sha256:{sha256}",
                        "size": self.GRAMMAR_SIZE,
                    }
                ],
            }
        ).encode("utf-8")

    def test_a_dead_manifest_falls_back_to_the_committed_pack_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.written(directory, self.build_info())
            with mock.patch.object(ftd.time, "sleep"), mock.patch.object(
                ftd.urllib.request, "urlopen", side_effect=http_error(404)
            ):
                url, sha256, name = ftd.resolve_dictionary(ftd.MANIFEST_URL, path)
        self.assertEqual(ftd.PACK_URL, url)
        self.assertEqual(self.PIN_SHA, sha256)
        self.assertEqual(ftd.PACK_NAME, name)

    def test_a_dead_grammar_release_api_falls_back_to_the_committed_grammar_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.written(directory, self.build_info())
            with mock.patch.object(ftd.time, "sleep"), mock.patch.object(
                ftd.urllib.request, "urlopen", side_effect=http_error(503)
            ):
                url, sha256, name, size = ftd.resolve_grammar(ftd.GRAMMAR_RELEASE_API, path)
        self.assertEqual(ftd.GRAMMAR_URL, url)
        self.assertEqual(self.GRAMMAR_PIN_SHA, sha256)
        self.assertEqual(ftd.GRAMMAR_NAME, name)
        self.assertEqual(self.GRAMMAR_SIZE, size)

    def test_the_published_channel_wins_over_a_pin_it_disagrees_with(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.written(directory, self.build_info())
            with mock.patch.object(
                ftd.urllib.request,
                "urlopen",
                return_value=io.BytesIO(self.live_manifest(self.LIVE_SHA)),
            ):
                _, sha256, _ = ftd.resolve_dictionary(ftd.MANIFEST_URL, path)
            self.assertEqual(
                self.LIVE_SHA,
                sha256,
                "the rolling URL serves what is published now, not what the pin recorded",
            )
            with mock.patch.object(
                ftd.urllib.request,
                "urlopen",
                return_value=io.BytesIO(self.live_grammar_release(self.GRAMMAR_LIVE_SHA)),
            ):
                _, grammar_sha256, _, _ = ftd.resolve_grammar(ftd.GRAMMAR_RELEASE_API, path)
            self.assertEqual(self.GRAMMAR_LIVE_SHA, grammar_sha256)

    def test_an_unusable_pin_is_not_fatal_while_the_channel_answers(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / ftd.BUILD_INFO_NAME
            with mock.patch.object(
                ftd.urllib.request,
                "urlopen",
                return_value=io.BytesIO(self.live_manifest(self.LIVE_SHA)),
            ):
                _, sha256, _ = ftd.resolve_dictionary(ftd.MANIFEST_URL, missing)
            self.assertEqual(self.LIVE_SHA, sha256)

            wrong_schema = self.written(directory, self.build_info(schema_name="something.else"))
            with mock.patch.object(
                ftd.urllib.request,
                "urlopen",
                return_value=io.BytesIO(self.live_manifest(self.LIVE_SHA)),
            ):
                _, sha256, _ = ftd.resolve_dictionary(ftd.MANIFEST_URL, wrong_schema)
            self.assertEqual(self.LIVE_SHA, sha256)

    def test_a_dead_channel_with_no_usable_pin_still_exits_non_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / ftd.BUILD_INFO_NAME
            with mock.patch.object(ftd.time, "sleep"), mock.patch.object(
                ftd.urllib.request, "urlopen", side_effect=http_error(404)
            ):
                with self.assertRaisesRegex(SystemExit, "failed after bounded retries"):
                    ftd.resolve_dictionary(ftd.MANIFEST_URL, missing)
                with self.assertRaisesRegex(SystemExit, "failed after bounded retries"):
                    ftd.resolve_grammar(ftd.GRAMMAR_RELEASE_API, missing)

    def test_malformed_pins_do_not_block_a_healthy_channel(self):
        documents = [
            [], None, 7,
            self.build_info(resources=None),
            self.build_info(resources=[None, 7]),
            self.build_info(resources=[{"kind": "dictionary", "physical_asset": "invalid"}]),
            self.build_info(pack_sha256=7),
            self.build_info(external_resource_references=None),
            self.build_info(grammar_sha256=[]),
        ]
        for document in documents:
            with self.subTest(document=document), tempfile.TemporaryDirectory() as directory:
                path = self.written(directory, document)
                with mock.patch.object(
                    ftd.urllib.request, "urlopen", side_effect=[
                        io.BytesIO(self.live_manifest(self.LIVE_SHA)),
                        io.BytesIO(self.live_grammar_release(self.GRAMMAR_LIVE_SHA)),
                    ],
                ):
                    self.assertEqual(self.LIVE_SHA, ftd.resolve_dictionary(ftd.MANIFEST_URL, path)[1])
                    self.assertEqual(self.GRAMMAR_LIVE_SHA, ftd.resolve_grammar(ftd.GRAMMAR_RELEASE_API, path)[1])

    def test_channel_failure_uses_verified_cached_bytes_without_asset_requests(self):
        pack_bytes = b"cached pack"
        grammar_bytes = b"cached grammar" * 1024
        pack_sha = hashlib.sha256(pack_bytes).hexdigest()
        grammar_sha = hashlib.sha256(grammar_bytes).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            document = self.build_info(pack_sha, grammar_sha)
            document["external_resource_references"][0]["physical_asset"]["size_bytes"] = len(grammar_bytes)
            path = self.written(directory, document)
            pack_path = Path(directory) / ftd.PACK_NAME
            grammar_path = Path(directory) / ftd.GRAMMAR_NAME
            pack_path.write_bytes(pack_bytes)
            grammar_path.write_bytes(grammar_bytes)
            with mock.patch.object(ftd.urllib.request, "urlopen", side_effect=http_error(404)) as opened:
                pack = ftd.resolve_dictionary(ftd.MANIFEST_URL, path, attempts=1)
                grammar = ftd.resolve_grammar(ftd.GRAMMAR_RELEASE_API, path, attempts=1)
                self.assertEqual(pack_path, ftd.ensure_pack(pack[0], pack[1], pack_path, None, 30))
                self.assertEqual(grammar_path, ftd.ensure_grammar(grammar[0], grammar[1], grammar[3], grammar_path, 30))
            self.assertEqual(2, opened.call_count)

    def test_a_pin_that_names_a_different_asset_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            document = self.build_info()
            document["resources"][0]["physical_asset"]["url"] = "https://example.invalid/other.zip"
            document["external_resource_references"][0]["physical_asset"]["size_bytes"] = 12
            path = self.written(directory, document)
            with mock.patch.object(ftd.time, "sleep"), mock.patch.object(
                ftd.urllib.request, "urlopen", side_effect=http_error(404)
            ):
                with self.assertRaisesRegex(SystemExit, "failed after bounded retries"):
                    ftd.resolve_dictionary(ftd.MANIFEST_URL, path)
                with self.assertRaisesRegex(SystemExit, "failed after bounded retries"):
                    ftd.resolve_grammar(ftd.GRAMMAR_RELEASE_API, path)

    def test_the_committed_build_info_in_this_repository_is_a_usable_pin(self):
        repo_root = Path(ftd.__file__).resolve().parents[1]
        info, problem = ftd.load_build_info(repo_root / ftd.BUILD_INFO_NAME)
        self.assertIsNone(problem)
        pack, pack_problem = ftd.pinned_pack(info)
        self.assertIsNone(pack_problem)
        self.assertEqual(ftd.PACK_URL, pack[0])
        self.assertRegex(pack[1], r"^[0-9a-f]{64}$")
        grammar, grammar_problem = ftd.pinned_grammar(info)
        self.assertIsNone(grammar_problem)
        self.assertEqual(ftd.GRAMMAR_URL, grammar[0])
        self.assertRegex(grammar[1], r"^[0-9a-f]{64}$")
        self.assertGreater(grammar[3], 1024)


if __name__ == "__main__":
    unittest.main(verbosity=2)
