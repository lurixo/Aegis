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


if __name__ == "__main__":
    unittest.main(verbosity=2)
