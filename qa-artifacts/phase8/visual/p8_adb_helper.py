#!/usr/bin/env python3
"""Small ADB/UIAutomator helper used for Phase 8 Android runtime QA evidence.

Usage:
  p8_adb_helper.py dump <out.xml>
  p8_adb_helper.py find <needle>
  p8_adb_helper.py tap <needle>
  p8_adb_helper.py type <text>
  p8_adb_helper.py shot <out.png>
"""
from __future__ import annotations

import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REMOTE_XML = "/sdcard/p8_window.xml"
LOCAL_XML = Path("/tmp/opencode/p8_window.xml")


def adb(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["adb", *args], text=True, capture_output=True, check=False
    )
    if check and result.returncode != 0:
        raise SystemExit(
            f"adb {' '.join(args)} failed ({result.returncode}):\n"
            f"{result.stdout}\n{result.stderr}"
        )
    return result


def dump() -> ET.Element:
    LOCAL_XML.parent.mkdir(parents=True, exist_ok=True)
    adb("shell", "uiautomator", "dump", REMOTE_XML)
    adb("pull", REMOTE_XML, str(LOCAL_XML))
    return ET.parse(LOCAL_XML).getroot()


def node_text(node: ET.Element) -> str:
    return " | ".join(
        filter(
            None,
            [
                node.get("text", ""),
                node.get("resource-id", ""),
                node.get("content-desc", ""),
                node.get("class", ""),
            ],
        )
    )


def matching(node: ET.Element, needle: str) -> bool:
    return needle.lower() in node_text(node).lower()


def walk(root: ET.Element, needle: str) -> list[ET.Element]:
    return [node for node in root.iter("node") if matching(node, needle)]


def bounds(node: ET.Element) -> tuple[int, int, int, int]:
    match = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not match:
        raise SystemExit(f"node has no usable bounds: {ET.tostring(node, encoding='unicode')}")
    return tuple(int(value) for value in match.groups())  # type: ignore[return-value]


def main() -> None:
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    command, arg = sys.argv[1], sys.argv[2]
    if command == "dump":
        Path(arg).write_bytes(LOCAL_XML.read_bytes())
        print(f"wrote {arg}")
        return
    if command == "find":
        for node in walk(dump(), arg):
            print(f"{bounds(node)} :: {node_text(node)}")
        return
    if command == "tap":
        nodes = walk(dump(), arg)
        if not nodes:
            raise SystemExit(f"no UI element matches {arg!r}")
        x1, y1, x2, y2 = bounds(nodes[0])
        x, y = (x1 + x2) // 2, (y1 + y2) // 2
        print(f"tapping {x},{y} :: {node_text(nodes[0])}")
        adb("shell", "input", "tap", str(x), str(y))
        return
    if command == "type":
        escaped = arg.replace("\\", "\\\\").replace(" ", "%s").replace("'", "\\'")
        adb("shell", "input", "text", escaped)
        print(f"typed {arg!r}")
        return
    if command == "shot":
        Path(arg).parent.mkdir(parents=True, exist_ok=True)
        with Path(arg).open("wb") as handle:
            process = subprocess.Popen(
                ["adb", "exec-out", "screencap", "-p"], stdout=handle
            )
            if process.wait() != 0:
                raise SystemExit("screencap failed")
        print(f"wrote {arg}")
        return
    raise SystemExit(f"unknown command: {command}")


if __name__ == "__main__":
    main()
