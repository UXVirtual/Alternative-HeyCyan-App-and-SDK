#!/usr/bin/env python3
"""Offline, non-certifying review of exact thumbnail callback bytes.

No packet is deleted, cropped, deduplicated by content, or searched for JPEG
markers. A decoded candidate is only a hypothesis until hardware independently
establishes packet semantics, framing and a first AND last index.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import urllib.request

MAX_PACKET_BYTES = 4 * 1024 * 1024
MAX_CALLBACKS = 2048
MAX_CANDIDATES = 12


def validate_jpeg(data: bytes) -> dict:
    result: dict = {"valid": False, "bytes": len(data)}
    if not data.startswith(b"\xff\xd8") or not data.endswith(b"\xff\xd9"):
        result["error"] = "SOI or terminal EOI missing (no cropping permitted)"
        return result
    try:
        from PIL import Image, ImageFile
    except ImportError:
        result["error"] = "Pillow unavailable; full decode required (install Pillow)"
        return result
    try:
        ImageFile.LOAD_TRUNCATED_IMAGES = False
        with Image.open(io.BytesIO(data)) as image:
            if image.format != "JPEG":
                raise ValueError("Not JPEG")
            width, height = image.size
            image.verify()  # Header/segment verification; re-open for actual entropy decode.
        with Image.open(io.BytesIO(data)) as image:
            image.load()
            image.convert("RGB").tobytes()  # Force full pixel materialization.
        result.update(valid=True, width=width, height=height, sha256=hashlib.sha256(data).hexdigest())
    except Exception as error:
        result["error"] = str(error)
    return result


def load_capture(root: Path, record: dict) -> tuple[list[tuple[int, bool, bytes]], list[str]]:
    ordinal = record["ordinal"]
    folder = root / f"capture_{ordinal}"
    callbacks = record.get("thumbnailCallbacks", [])
    failures: list[str] = []
    if not callbacks or len(callbacks) > MAX_CALLBACKS:
        return [], ["missing or excessive callback metadata"]
    packets: list[tuple[int, bool, bytes]] = []
    for sequence, callback in enumerate(callbacks, 1):
        if callback.get("sequence") != sequence:
            failures.append(f"callback sequence not contiguous at {sequence}")
        if callback.get("lateCallback") or callback.get("limitExceeded"):
            failures.append(f"late/oversize callback {sequence}")
        filename = callback.get("file")
        if filename != f"packet_{sequence:04d}.bin":
            failures.append(f"missing raw packet {sequence}")
            continue
        path = folder / filename
        if not path.is_file() or path.stat().st_size > MAX_PACKET_BYTES:
            failures.append(f"missing/oversize raw packet {sequence}")
            continue
        payload = path.read_bytes()
        if (len(payload) != callback.get("length") or
                hashlib.sha256(payload).hexdigest() != callback.get("sha256")):
            failures.append(f"raw packet hash/size mismatch {sequence}")
        packets.append((callback["index"], callback["complete"], payload))
    if len(packets) != len(callbacks):
        failures.append("not all callbacks have authenticated raw bytes")
    return packets, failures


def candidate_streams(packets: list[tuple[int, bool, bytes]]) -> tuple[dict[str, bytes], list[str], dict]:
    failures: list[str] = []
    indices: dict[int, bytes] = {}
    duplicates = 0
    for index, _, payload in packets:
        if not payload:
            continue  # Empty completion callback is metadata, not a JPEG byte range.
        if index in indices:
            if indices[index] != payload:
                failures.append(f"conflicting duplicate index {index}")
            else:
                duplicates += 1
        else:
            indices[index] = payload
    sorted_indices = sorted(indices)
    gaps = [index for index in range(sorted_indices[0], sorted_indices[-1] + 1)
            if index not in indices] if sorted_indices and sorted_indices[-1] - sorted_indices[0] < MAX_CALLBACKS else []
    if sorted_indices and sorted_indices[-1] - sorted_indices[0] >= MAX_CALLBACKS:
        failures.append("index range exceeds callback bound")
    if gaps:
        failures.append(f"interior gaps: {gaps[:20]}")
    if len(indices) > MAX_CALLBACKS or sum(map(len, indices.values())) > MAX_PACKET_BYTES:
        failures.append("candidate exceeds packet or byte bound")
        return {}, failures, {}
    if not any(done for _, done, _ in packets):
        failures.append("completion callback missing")
    if packets and next((i for i, (_, done, _) in enumerate(packets) if done), len(packets)) < len(packets) - 1:
        failures.append("data callbacks after completion")
    streams = {
        "index_exact": b"".join(indices[i] for i in sorted_indices),
        "arrival_exact": b"".join(payload for _, _, payload in packets if payload),
    }
    evidence = {"indicesInArrivalOrder": [p[0] for p in packets],
                "firstObservedIndex": min(sorted_indices) if sorted_indices else None,
                "lastObservedIndex": max(sorted_indices) if sorted_indices else None,
                "completionIndices": [index for index, done, _ in packets if done],
                "identicalIndexDuplicates": duplicates,
                "independentFirstLastIndexKnown": False,
                "framingVerifiedOnHardware": False}
    return streams, failures, evidence


def review(report_path: Path, output: Path) -> dict:
    report = json.loads(report_path.read_text())
    output.mkdir(parents=True, exist_ok=True)
    root = report_path.parent
    summary: dict = {"runId": report.get("runId"), "contractApproved": False,
                     "observationComplete": report.get("observationComplete", False),
                     "captures": [], "limitations": [
                         "Observed first/last index does not prove independent packet bounds.",
                         "JPEG decode and vision review cannot establish bit-perfect integrity.",
                         "No automatic framing removal, overlapping-byte repair, SOI search or EOI cropping."]}
    for record in report.get("captures", []):
        ordinal = record["ordinal"]
        entry: dict = {"ordinal": ordinal, "afterTeardown": record.get("afterTeardown", False)}
        packets, errors = load_capture(root, record)
        streams, stream_errors, evidence = candidate_streams(packets)
        entry["errors"] = errors + stream_errors
        entry["packetEvidence"] = evidence
        entry["candidates"] = {}
        if not errors and not stream_errors:
            for name, data in streams.items():
                analysis = validate_jpeg(data)
                entry["candidates"][name] = analysis
                if analysis["valid"]:
                    filename = f"capture_{ordinal}_{name}.jpg"
                    (output / filename).write_bytes(data)
                    analysis["file"] = filename
        summary["captures"].append(entry)
    (output / "review.json").write_text(json.dumps(summary, indent=2) + "\n")
    return summary


def vision_review(summary: dict, output: Path, model: str) -> None:
    """Opt-in remote visual triage only; raw packets, MAC and notify frames stay local."""
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        raise ValueError("OPENAI_API_KEY is required for --vision-model")
    # Only send JPEGs which already passed the exact-byte decoder; never send invalid candidates.
    for capture in summary["captures"]:
        for name, candidate in capture["candidates"].items():
            if not candidate.get("valid"):
                continue
            data = (output / candidate["file"]).read_bytes()
            request = urllib.request.Request(
                "https://api.openai.com/v1/responses",
                data=json.dumps({"model": model, "max_output_tokens": 250,
                    "input": [{"role": "user", "content": [
                        {"type": "input_text", "text": "Visually inspect for conspicuous corruption, missing image blocks, broken colors or orientation. Respond with a brief assessment and uncertainty. Do not assert packet integrity, correct scene identity or bit-perfect transfer."},
                        {"type": "input_image", "image_url": "data:image/jpeg;base64," + base64.b64encode(data).decode("ascii")}
                    ]}]}).encode(),
                headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=40) as response:
                result = json.load(response)
            candidate["visualReview"] = {"model": model, "response": [item.get("content", [])
                for item in result.get("output", [])], "notIntegrityEvidence": True}
    (output / "review.json").write_text(json.dumps(summary, indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="extracted report.json (raw capture_* files alongside)")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--vision-model", help="opt-in: sends valid candidate JPEGs to OpenAI for visual triage")
    args = parser.parse_args()
    try:
        summary = review(args.report, args.output)
        if args.vision_model:
            vision_review(summary, args.output, args.vision_model)
        print(f"Reviewed {len(summary['captures'])} captures; contractApproved=false; output={args.output}")
        return 0 if summary["observationComplete"] and len(summary["captures"]) >= 4 and all(
            any(item["valid"] for item in c["candidates"].values()) and not c["errors"]
            for c in summary["captures"]) else 1
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        parser.exit(2, f"Review failed: {error}\n")


if __name__ == "__main__":
    raise SystemExit(main())