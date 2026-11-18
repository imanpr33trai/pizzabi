#!/usr/bin/env python3
"""Generates and verifies PIZZORA release update metadata (update.json and SHA256SUMS.txt)."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path


def compute_sha256(file_path: Path) -> str:
  digest = hashlib.sha256()
  with file_path.open("rb") as fh:
    while True:
      chunk = fh.read(1024 * 1024)
      if not chunk:
        break
      digest.update(chunk)
  return digest.hexdigest()


def load_policy(policy_path: Path) -> dict:
  if not policy_path.is_file():
    raise FileNotFoundError(f"Release policy file not found: {policy_path}")
  with policy_path.open("r", encoding="utf-8") as fh:
    policy = json.load(fh)
  if not isinstance(policy, dict):
    raise ValueError("release-policy.json must be a JSON object")
  schema_version = policy.get("schemaVersion")
  if schema_version != 1:
    raise ValueError(f"Unsupported release-policy.json schemaVersion: {schema_version!r}")
  min_supported = policy.get("minimumSupportedVersionCode")
  if not isinstance(min_supported, int) or isinstance(min_supported, bool) or min_supported < 1:
    raise ValueError(
      f"minimumSupportedVersionCode must be a positive integer, got: {min_supported!r}"
    )
  mandatory = policy.get("mandatory")
  if not isinstance(mandatory, bool):
    raise ValueError(f"mandatory must be a boolean, got: {mandatory!r}")
  override = policy.get("releaseNotesOverride", "")
  if override is None:
    override = ""
  if not isinstance(override, str):
    raise ValueError("releaseNotesOverride must be a string")
  return {
    "schemaVersion": 1,
    "minimumSupportedVersionCode": min_supported,
    "mandatory": mandatory,
    "releaseNotesOverride": override.strip(),
  }


def generate_metadata(args: argparse.Namespace) -> None:
  apk_path = Path(args.apk_path)
  if not apk_path.is_file():
    raise FileNotFoundError(f"Signed APK not found: {apk_path}")
  apk_size_bytes = apk_path.stat().st_size
  if apk_size_bytes <= 0:
    raise ValueError(f"Signed APK is empty: {apk_path}")

  policy = load_policy(Path(args.policy_file))
  min_supported = policy["minimumSupportedVersionCode"]
  if min_supported > args.version_code:
    raise ValueError(
      f"minimumSupportedVersionCode ({min_supported}) cannot exceed current release versionCode ({args.version_code})"
    )

  release_notes = policy["releaseNotesOverride"]
  if not release_notes and args.release_notes_file:
    notes_path = Path(args.release_notes_file)
    if notes_path.is_file():
      release_notes = notes_path.read_text(encoding="utf-8").strip()
  if not release_notes:
    release_notes = f"PIZZORA {args.tag}"

  apk_sha256 = compute_sha256(apk_path)
  published_at = args.published_at or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
  supported_abis = [abi.strip() for abi in args.abis.split(",") if abi.strip()]
  if not supported_abis:
    raise ValueError("supportedAbis must not be empty")

  metadata = {
    "schemaVersion": 1,
    "applicationId": args.application_id,
    "versionName": args.version_name,
    "versionCode": args.version_code,
    "minimumSupportedVersionCode": min_supported,
    "mandatory": policy["mandatory"],
    "releaseNotes": release_notes,
    "apkAssetName": args.apk_filename,
    "apkSha256": apk_sha256,
    "apkSizeBytes": apk_size_bytes,
    "minSdk": args.min_sdk,
    "supportedAbis": supported_abis,
    "releaseUrl": args.release_url,
    "publishedAt": published_at,
    "prerelease": args.prerelease == "true",
  }

  out_dir = Path(args.out_dir)
  out_dir.mkdir(parents=True, exist_ok=True)
  update_json_path = out_dir / "update.json"
  checksums_path = out_dir / "SHA256SUMS.txt"

  update_json_path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
  checksums_path.write_text(f"{apk_sha256}  {args.apk_filename}\n", encoding="utf-8")

  print(f"Generated {update_json_path} and {checksums_path} (sha256={apk_sha256}, size={apk_size_bytes})")


def verify_metadata(args: argparse.Namespace) -> None:
  out_dir = Path(args.out_dir)
  apk_path = Path(args.apk_path)
  update_json_path = out_dir / "update.json"
  checksums_path = out_dir / "SHA256SUMS.txt"

  if not apk_path.is_file():
    raise FileNotFoundError(f"Missing APK: {apk_path}")
  if not update_json_path.is_file():
    raise FileNotFoundError(f"Missing update.json: {update_json_path}")
  if not checksums_path.is_file():
    raise FileNotFoundError(f"Missing SHA256SUMS.txt: {checksums_path}")

  actual_sha256 = compute_sha256(apk_path)
  actual_size = apk_path.stat().st_size

  checksum_line = checksums_path.read_text(encoding="utf-8").strip()
  expected_line = f"{actual_sha256}  {args.apk_filename}"
  if checksum_line != expected_line:
    raise ValueError(f"SHA256SUMS.txt mismatch: got {checksum_line!r}, expected {expected_line!r}")

  metadata = json.loads(update_json_path.read_text(encoding="utf-8"))
  required_keys = {
    "schemaVersion",
    "applicationId",
    "versionName",
    "versionCode",
    "minimumSupportedVersionCode",
    "mandatory",
    "releaseNotes",
    "apkAssetName",
    "apkSha256",
    "apkSizeBytes",
    "minSdk",
    "supportedAbis",
    "releaseUrl",
    "publishedAt",
    "prerelease",
  }
  missing = required_keys - set(metadata.keys())
  if missing:
    raise ValueError(f"update.json missing keys: {sorted(missing)}")
  if metadata["schemaVersion"] != 1:
    raise ValueError(f"Unexpected schemaVersion: {metadata['schemaVersion']!r}")
  if metadata["applicationId"] != args.application_id:
    raise ValueError(f"applicationId mismatch: {metadata['applicationId']!r}")
  if metadata["versionName"] != args.version_name:
    raise ValueError(f"versionName mismatch: {metadata['versionName']!r}")
  if metadata["versionCode"] != args.version_code:
    raise ValueError(f"versionCode mismatch: {metadata['versionCode']!r}")
  if metadata["apkAssetName"] != args.apk_filename:
    raise ValueError(f"apkAssetName mismatch: {metadata['apkAssetName']!r}")
  if metadata["apkSha256"] != actual_sha256:
    raise ValueError(f"apkSha256 mismatch: {metadata['apkSha256']!r} != {actual_sha256!r}")
  if metadata["apkSizeBytes"] != actual_size:
    raise ValueError(f"apkSizeBytes mismatch: {metadata['apkSizeBytes']!r} != {actual_size!r}")
  if metadata["prerelease"] != (args.prerelease == "true"):
    raise ValueError(f"prerelease mismatch: {metadata['prerelease']!r}")
  if not str(metadata["releaseUrl"]).startswith("https://"):
    raise ValueError(f"releaseUrl must be HTTPS: {metadata['releaseUrl']!r}")

  print(f"Verified update.json and SHA256SUMS.txt against {args.apk_filename} ({actual_sha256})")


def parse_args() -> argparse.Namespace:
  parser = argparse.ArgumentParser()
  parser.add_argument("--verify", action="store_true")
  parser.add_argument("--validate-policy-only", action="store_true")
  parser.add_argument("--tag", default="")
  parser.add_argument("--version-name", default="")
  parser.add_argument("--version-code", type=int, default=0)
  parser.add_argument("--application-id", default="com.aistudio.pizzora.xkqzmw")
  parser.add_argument("--min-sdk", type=int, default=24)
  parser.add_argument("--abis", default="arm64-v8a,x86_64")
  parser.add_argument("--apk-path", default="")
  parser.add_argument("--apk-filename", default="")
  parser.add_argument("--release-url", default="")
  parser.add_argument("--policy-file", default="release-policy.json")
  parser.add_argument("--prerelease", default="false", choices=["true", "false"])
  parser.add_argument("--published-at", default="")
  parser.add_argument("--release-notes-file", default="")
  parser.add_argument("--out-dir", default=".")
  return parser.parse_args()


def main() -> int:
  args = parse_args()
  if args.validate_policy_only:
    policy = load_policy(Path(args.policy_file))
    print(f"Validated {args.policy_file}: {policy}")
    return 0
  if args.verify:
    verify_metadata(args)
  else:
    generate_metadata(args)
  return 0


if __name__ == "__main__":
  sys.exit(main())
