#!/usr/bin/env python3
"""Phase 8 Android runtime QA helper (temporary, evidence-only).

All outputs are written under qa-artifacts/phase8/visual/runtime/.
Usage:
  p8_runtime_helper.py dump   <out.xml>
  p8_runtime_helper.py nodes  [--interactive]
  p8_runtime_helper.py find   <needle>
  p8_runtime_helper.py tap    <needle> [--nth N] [--clickable] [--exact]
  p8_runtime_helper.py tapxy  <x> <y>
  p8_runtime_helper.py type   <text>
  p8_runtime_helper.py key    <KEYCODE>
  p8_runtime_helper.py shot   <out.png>
  p8_runtime_helper.py shell  <command ...>
  p8_runtime_helper.py wake   (Settings->app input-channel refresh workaround)
"""
from __future__ import annotations

import datetime as dt
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
LOG = HERE / "command_log.txt"
REMOTE_XML = "/sdcard/p8_runtime_window.xml"
LOCAL_XML = Path("/tmp/opencode/p8_runtime_window.xml")


def log(cmd: list[str], rc: int, extra: str = "") -> None:
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    with LOG.open("a") as fh:
        fh.write(f"{stamp} rc={rc} cmd={' '.join(cmd)}")
        if extra:
            fh.write(f" :: {extra}")
        fh.write("\n")


def adb(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(["adb", *args], text=True, capture_output=True, check=False)
    log(["adb", *args], proc.returncode, (proc.stdout + proc.stderr).strip()[:300])
    if check and proc.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed ({proc.returncode}):\n{proc.stdout}\n{proc.stderr}")
    return proc


def dump_raw() -> ET.Element:
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


def bounds(node: ET.Element) -> tuple[int, int, int, int]:
    match = re.search(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    if not match:
        raise SystemExit(f"no bounds: {ET.tostring(node, encoding='unicode')}")
    return tuple(int(v) for v in match.groups())  # type: ignore[return-value]


def selected_nodes(root: ET.Element, needle: str, exact: bool, clickable: bool) -> list[ET.Element]:
    out: list[ET.Element] = []
    for node in root.iter("node"):
        if clickable and node.get("clickable") != "true":
            continue
        text = node.get("text", "")
        desc = node.get("content-desc", "")
        rid = node.get("resource-id", "")
        hay = [text, desc, rid]
        ok = any((v == needle) if exact else (needle.lower() in v.lower()) for v in hay)
        if ok:
            out.append(node)
    return out


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    cmd = sys.argv[1]
    args = sys.argv[2:]
    if cmd == "dump":
        out = Path(args[0])
        out.parent.mkdir(parents=True, exist_ok=True)
        root = dump_raw()
        ET.ElementTree(root).write(out, encoding="utf-8", xml_declaration=True)
        print(f"wrote {out}")
        return
    if cmd == "nodes":
        root = dump_raw()
        for node in root.iter("node"):
            interactive = node.get("clickable") == "true" or node.get("long-clickable") == "true" or node.get("scrollable") == "true"
            if "--interactive" in args and not interactive:
                continue
            print(f"{bounds(node)} c={node.get('clickable')} f={node.get('focusable')} :: {node_text(node)}")
        return
    if cmd == "find":
        root = dump_raw()
        for node in selected_nodes(root, args[0], False, False):
            print(f"{bounds(node)} :: {node_text(node)}")
        return
    if cmd == "tap":
        needle = args[0]
        rest = args[1:]
        nth = int(rest[rest.index("--nth") + 1]) if "--nth" in rest else 0
        exact = "--exact" in rest
        clickable = "--clickable" in rest
        root = dump_raw()
        nodes = selected_nodes(root, needle, exact, clickable)
        if not nodes:
            raise SystemExit(f"no UI node matches {needle!r}")
        node = nodes[nth]
        x1, y1, x2, y2 = bounds(node)
        x, y = (x1 + x2) // 2, (y1 + y2) // 2
        print(f"tap {x},{y} :: {node_text(node)} [{nth}/{len(nodes)}]")
        adb("shell", "input", "tap", str(x), str(y))
        return
    if cmd == "tapxy":
        adb("shell", "input", "tap", args[0], args[1])
        return
    if cmd == "type":
        escaped = args[0].replace("\\", "\\\\").replace(" ", "%s").replace("'", "\\'")
        adb("shell", "input", "text", escaped)
        return
    if cmd == "key":
        adb("shell", "input", "keyevent", args[0])
        return
    if cmd == "shot":
        out = Path(args[0])
        out.parent.mkdir(parents=True, exist_ok=True)
        with out.open("wb") as fh:
            proc = subprocess.Popen(["adb", "exec-out", "screencap", "-p"], stdout=fh)
            rc = proc.wait()
        log(["adb", "exec-out", "screencap", "-p"], rc, str(out))
        if rc != 0:
            raise SystemExit(f"screencap failed ({rc})")
        print(f"wrote {out}")
        return
    if cmd == "shell":
        joined = " ".join(args)
        proc = subprocess.run(joined, shell=True, text=True, capture_output=True, check=False)
        log(["bash", "-c", joined], proc.returncode, (proc.stdout + proc.stderr).strip()[:300])
        print(proc.stdout, end="")
        if proc.stderr:
            print(proc.stderr, end="", file=sys.stderr)
        raise SystemExit(proc.returncode)
    if cmd == "wake":
        adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
        adb("shell", "am", "start", "-n", "com.aistudio.pizzora.xkqzmw/com.example.MainActivity")
        return
    raise SystemExit(f"unknown command: {cmd}")


if __name__ == "__main__":
    main()
