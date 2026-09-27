"""Offline contract-review regressions; no device or API key required."""

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from review_heycyan_image_contract import candidate_streams, load_capture, review, validate_jpeg


class ContractReviewTest(unittest.TestCase):
    def test_reorder_identical_duplicates_without_trimming(self):
        streams, errors, evidence = candidate_streams([
            (2, False, b"end"), (1, False, b"start"),
            (2, False, b"end"), (3, True, b""),
        ])
        self.assertEqual([], errors)
        self.assertEqual(b"startend", streams["index_exact"])
        self.assertEqual(b"endstartend", streams["arrival_exact"])
        self.assertEqual(1, evidence["identicalIndexDuplicates"])
        self.assertFalse(evidence["independentFirstLastIndexKnown"])

    def test_conflicts_gaps_and_late_completion(self):
        _, errors, _ = candidate_streams([
            (0, True, b"a"), (2, False, b"b"), (0, False, b"c")
        ])
        self.assertTrue(any("conflicting duplicate" in x for x in errors))
        self.assertTrue(any("interior gaps" in x for x in errors))
        self.assertTrue(any("after completion" in x for x in errors))

    def test_no_soi_search_or_eoi_cropping(self):
        self.assertFalse(validate_jpeg(b"garbage\xff\xd8\xff\xd9")["valid"])
        self.assertFalse(validate_jpeg(b"\xff\xd8\xff\xd9garbage")["valid"])
        self.assertFalse(validate_jpeg(b"\xff\xd8\xff\xd9")["valid"])

    def test_failed_report_stays_unapproved(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            record = {"ordinal": 1, "thumbnailCallbacks": [{
                "sequence": 1, "index": 0, "complete": True,
                "file": "packet_0001.bin", "length": 4,
                "sha256": hashlib.sha256(b"oops").hexdigest(),
            }]}
            packet = folder / "capture_1" / "packet_0001.bin"
            packet.parent.mkdir()
            packet.write_bytes(b"bad!")
            report = folder / "report.json"
            report.write_text(json.dumps({"runId": "test", "observationComplete": True,
                                          "captures": [record]}))
            _, errors = load_capture(folder, record)
            self.assertTrue(any("hash/size mismatch" in x for x in errors))
            result = review(report, folder / "out")
            self.assertFalse(result["contractApproved"])
            self.assertEqual({}, result["captures"][0]["candidates"])


if __name__ == "__main__":
    unittest.main()