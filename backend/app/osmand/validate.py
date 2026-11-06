"""Structural validation of ``.osf`` packages against the rules in :mod:`app.osmand.spec`.

This checks what OsmAnd's importer checks (and what it silently skips). It does NOT prove
that a package imports correctly on a device — that still needs a real OsmAnd installation.
"""

from __future__ import annotations

import io
import json
import re
import zipfile
from urllib.parse import urlsplit
from xml.etree import ElementTree

from app.datasets import ValidationReport
from app.osmand.spec import (
    FILE_SUBTYPES,
    ITEM_TYPES,
    MAP_SOURCE_KEYS,
    MAP_SOURCE_REQUIRED,
    MAX_OSF_VERSION,
    PLUGIN_KEYS,
)

PLUGIN_ID_RE = re.compile(r"^[a-z0-9_]+(\.[a-z0-9_]+)+$")
SAFE_NAME_RE = re.compile(r"^[A-Za-z0-9 ._()\-]+$")
GPX_NS = "{http://www.topografix.com/GPX/1/1}"


def _norm(path: str) -> str:
    return path.lstrip("/")


def validate_osf(data: bytes) -> ValidationReport:
    report = ValidationReport()
    try:
        zf = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        report.errors.append("not a ZIP archive")
        return report

    names = [i.filename for i in zf.infolist() if not i.is_dir()]
    for name in names:
        if name.startswith("/") or ".." in name.split("/") or "\\" in name:
            report.errors.append(f"unsafe entry path: {name!r}")
    if "items.json" not in names:
        report.errors.append("items.json must be at the root of the archive")
        return report
    try:
        root = json.loads(zf.read("items.json").decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        report.errors.append(f"items.json is not valid UTF-8 JSON: {exc}")
        return report
    if not isinstance(root, dict):
        report.errors.append("items.json root must be an object")
        return report

    version = root.get("version", 1)
    if not isinstance(version, int) or not 1 <= version <= MAX_OSF_VERSION:
        report.errors.append(f"root version must be an integer 1..{MAX_OSF_VERSION}")
    items = root.get("items")
    if not isinstance(items, list) or not items:
        report.errors.append("'items' must be a non-empty array")
        return report

    claimed: set[str] = {"items.json"}
    plugin_ids: list[str] = []
    resource_dirs: list[str] = []
    icon_refs: list[str] = []

    for idx, item in enumerate(items):
        where = f"items[{idx}]"
        if not isinstance(item, dict):
            report.errors.append(f"{where}: must be an object")
            continue
        itype = item.get("type")
        if itype not in ITEM_TYPES:
            report.errors.append(f"{where}: unknown type {itype!r} (OsmAnd would silently skip it)")
            continue
        if itype == "PLUGIN":
            _check_plugin(item, where, report, plugin_ids, icon_refs)
        elif itype == "RESOURCES":
            folder = _norm(str(item.get("file", ""))).rstrip("/")
            if not folder:
                report.errors.append(f"{where}: RESOURCES needs 'file' (folder name)")
                continue
            entries = [n for n in names if n.startswith(folder + "/")]
            if not entries:
                report.errors.append(f"{where}: no archive entries under {folder}/")
            claimed.update(entries)
            resource_dirs.append(folder)
        elif itype == "MAP_SOURCES":
            _check_map_sources(item, where, report)
        elif itype == "FILE":
            _check_file(zf, names, item, where, report, claimed)
        else:
            report.warnings.append(f"{where}: type {itype} is not checked in depth by this validator")

    if len(plugin_ids) > 1:
        report.errors.append("more than one PLUGIN item")
    if plugin_ids:
        for idx, item in enumerate(items):
            pid = item.get("pluginId") if isinstance(item, dict) else None
            if pid is not None and pid != plugin_ids[0]:
                report.errors.append(f"items[{idx}]: pluginId {pid!r} differs from plugin {plugin_ids[0]!r}")
    for ref in icon_refs:
        if not any(n.endswith("/" + ref) and n.split("/", 1)[0] in resource_dirs for n in names):
            report.errors.append(f"PLUGIN references {ref!r} but no RESOURCES entry provides it")

    for name in names:
        if name not in claimed:
            report.warnings.append(f"archive entry {name!r} is not referenced by any item (ignored by OsmAnd)")
    return report


def _check_plugin(item, where, report, plugin_ids, icon_refs) -> None:
    pid = item.get("pluginId")
    if not isinstance(pid, str) or not PLUGIN_ID_RE.match(pid):
        report.errors.append(f"{where}: pluginId must look like 'org.name' (lowercase, dot separated)")
    else:
        plugin_ids.append(pid)
    if not isinstance(item.get("version"), int):
        report.errors.append(f"{where}: PLUGIN version must be an integer")
    for key in ("name", "description"):
        value = item.get(key)
        if not isinstance(value, dict) or not isinstance(value.get(""), str) or not value[""].strip():
            report.errors.append(f"{where}: {key} must be a localized object with a default '' entry")
    for key in ("icon", "image"):
        value = item.get(key)
        if value is None:
            continue
        if not isinstance(value, dict) or not all(isinstance(v, str) for v in value.values()):
            report.errors.append(f"{where}: {key} must be a localized object of file names")
            continue
        icon_refs.extend(v.lstrip("@") for v in value.values())
    for key in item:
        if key not in PLUGIN_KEYS:
            report.warnings.append(f"{where}: key {key!r} is not read by OsmAnd")


def _check_map_sources(item, where, report) -> None:
    sources = item.get("items")
    if not isinstance(sources, list) or not sources:
        report.errors.append(f"{where}: MAP_SOURCES needs a non-empty 'items' array")
        return
    seen: set[str] = set()
    for j, src in enumerate(sources):
        at = f"{where}.items[{j}]"
        if not isinstance(src, dict):
            report.errors.append(f"{at}: must be an object")
            continue
        for key in MAP_SOURCE_REQUIRED:
            if key not in src:
                report.errors.append(f"{at}: missing {key!r}")
        for key, value in src.items():
            expected = MAP_SOURCE_KEYS.get(key)
            if expected is None:
                report.warnings.append(f"{at}: key {key!r} is not read by OsmAnd")
            elif isinstance(value, bool) != (expected is bool) or not isinstance(value, expected):
                report.errors.append(f"{at}: {key!r} must be {expected.__name__}")
        name = src.get("name")
        if isinstance(name, str):
            if not SAFE_NAME_RE.match(name):
                report.errors.append(f"{at}: name {name!r} should only use [A-Za-z0-9 ._()-] (it becomes a folder)")
            if name in seen:
                report.errors.append(f"{at}: duplicate name {name!r}")
            seen.add(name)
        url = src.get("url")
        if isinstance(url, str) and not src.get("sql"):
            for ph in ("{0}", "{1}", "{2}"):
                if ph not in url:
                    report.errors.append(f"{at}: url must contain {ph} (zoom/x/y)")
            if (urlsplit(url.replace("{0}", "0").replace("{1}", "0").replace("{2}", "0")).hostname or "") in (
                "0.0.0.0", "::"):
                report.errors.append(f"{at}: tile url {url!r} points at a listen address (0.0.0.0); "
                                     "phones cannot load tiles from it — use the public server address")
            elif not url.startswith("https://"):
                if url.startswith(("http://localhost", "http://127.0.0.1", "http://10.0.2.2")):
                    report.warnings.append(f"{at}: development URL {url!r} — rebuild with an https base URL for release")
                else:
                    report.errors.append(f"{at}: tile url must use https")
        zmin, zmax = src.get("minZoom"), src.get("maxZoom")
        if isinstance(zmin, int) and isinstance(zmax, int) and not 1 <= zmin <= zmax <= 22:
            report.errors.append(f"{at}: zoom range must satisfy 1 <= minZoom <= maxZoom <= 22")
        if src.get("tileSize") not in (256, 512):
            report.errors.append(f"{at}: tileSize must be 256 or 512")
        ext = src.get("ext")
        if isinstance(ext, str) and not ext.startswith("."):
            report.errors.append(f"{at}: ext must start with '.' (e.g. '.png')")


def _check_file(zf, names, item, where, report, claimed) -> None:
    subtype = item.get("subtype")
    if subtype not in FILE_SUBTYPES:
        report.errors.append(f"{where}: FILE subtype {subtype!r} is not a known OsmAnd FileSubtype")
    path = _norm(str(item.get("file", "")))
    if not path:
        report.errors.append(f"{where}: FILE needs 'file'")
        return
    if path not in names:
        report.errors.append(f"{where}: archive entry {path!r} is missing")
        return
    claimed.add(path)
    if subtype == "gpx":
        try:
            root = ElementTree.fromstring(zf.read(path))
        except ElementTree.ParseError as exc:
            report.errors.append(f"{where}: {path} is not well-formed XML ({exc})")
            return
        if root.tag != f"{GPX_NS}gpx" or root.get("version") != "1.1":
            report.errors.append(f"{where}: {path} is not a GPX 1.1 document")
        elif not root.findall(f"{GPX_NS}wpt"):
            report.warnings.append(f"{where}: {path} contains no waypoints")
