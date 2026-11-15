#!/usr/bin/env python3
"""Create the metadata asset consumed by HOOT Android's signed-update verifier."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


APPLICATION_ID = "app.hoot"
REPOSITORY = "sugan0025/HOOT"
EXPECTED_SIGNER = "30e9d23fb1a37a9d9dc1f117e2366d23a17186cf745b0570b7b909f75602fcd6"
SIGNER_DIGEST_PATTERN = re.compile(
    r"Signer\s+#(?P<number>\d+)\s+certificate\s+SHA-256\s+digest\s*:\s*"
    r"(?P<digest>[0-9a-fA-F][0-9a-fA-F: \t-]*)",
    re.IGNORECASE,
)


def signer_sha256(stdout: str, stderr: str) -> str:
    """Read the single signer digest from apksigner's combined output."""
    output = f"{stdout}\n{stderr}"
    signers: set[tuple[int, str]] = set()
    for match in SIGNER_DIGEST_PATTERN.finditer(output):
        digest = re.sub(r"[\s:-]", "", match.group("digest")).lower()
        if len(digest) != 64 or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
            raise ValueError("apksigner returned a malformed SHA-256 certificate digest")
        signers.add((int(match.group("number")), digest))

    if not signers:
        raise ValueError("apksigner did not report a signer SHA-256 certificate digest")
    if len(signers) != 1 or next(iter(signers))[0] != 1:
        raise ValueError("Expected exactly one APK signer certificate")
    return next(iter(signers))[1]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--output-metadata", required=True, type=Path)
    parser.add_argument("--apksigner", required=True, type=Path)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--release-notes", required=True)
    args = parser.parse_args()

    build = json.loads(args.output_metadata.read_text(encoding="utf-8"))
    if build.get("applicationId") != APPLICATION_ID:
        raise SystemExit(f"Unexpected Android application ID: {build.get('applicationId')!r}")
    elements = build.get("elements") or []
    if len(elements) != 1:
        raise SystemExit("Expected exactly one release APK output")
    version_code = int(elements[0]["versionCode"])
    version_name = str(elements[0]["versionName"])
    if version_code < 1 or args.tag != f"android-v{version_name}":
        raise SystemExit("Android tag must exactly match the APK's positive version code and version name")
    if not args.apk.is_file():
        raise SystemExit(f"Release APK not found: {args.apk}")

    signer_result = subprocess.run(
        [str(args.apksigner), "verify", "--print-certs", str(args.apk)],
        check=True,
        capture_output=True,
        text=True,
    )
    try:
        apk_signer_sha256 = signer_sha256(signer_result.stdout, signer_result.stderr)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    if apk_signer_sha256 != EXPECTED_SIGNER:
        raise SystemExit("The APK signing certificate does not match the established Android update identity")

    digest = hashlib.sha256()
    with args.apk.open("rb") as apk_file:
        for block in iter(lambda: apk_file.read(1024 * 1024), b""):
            digest.update(block)

    metadata = {
        "schemaVersion": 1,
        "applicationId": APPLICATION_ID,
        "tag": args.tag,
        "versionCode": version_code,
        "versionName": version_name,
        "releaseNotes": args.release_notes,
        "apkUrl": f"https://github.com/{REPOSITORY}/releases/download/{args.tag}/Hoot-Android.apk",
        "apkSizeBytes": args.apk.stat().st_size,
        "sha256": digest.hexdigest(),
        "signerSha256": apk_signer_sha256,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
