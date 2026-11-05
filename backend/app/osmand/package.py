"""Build the Syria Maps ``.osf`` package (a ZIP with ``items.json`` at the root)."""

from __future__ import annotations

import io
import json
import zipfile
from dataclasses import dataclass
from typing import Any

from PIL import Image, ImageDraw

from app.exports import layer_gpx
from app.layers import Layer, LayerRegistry
from app.storage.base import Storage

PLUGIN_ID = "syria.maps"
ICON_FILE = "syria_maps_icon.png"
IMAGE_FILE = "syria_maps_image.png"
RES_DIR = "res"
# Fixed timestamp keeps the archive byte-for-byte reproducible.
ZIP_DATE = (2026, 1, 1, 0, 0, 0)


@dataclass
class PackageOptions:
    base_url: str
    plugin_version: int = 1
    include_gpx: bool = True


def tile_url_template(base_url: str, layer: Layer) -> str:
    # OsmAnd template placeholders: {0} = zoom, {1} = x, {2} = y.
    return f"{base_url.rstrip('/')}/tiles/{layer.id}/{{0}}/{{1}}/{{2}}.png"


def map_source_name(layer: Layer) -> str:
    suffix = " (synthetic)" if layer.synthetic else ""
    return f"Syria Maps - {layer.title.en}{suffix}"


def map_source_entry(base_url: str, layer: Layer) -> dict[str, Any]:
    return {
        "sql": False,
        "name": map_source_name(layer),
        "minZoom": layer.zoom.min,
        "maxZoom": layer.zoom.max,
        "url": tile_url_template(base_url, layer),
        "randoms": "",
        "ellipsoid": False,
        "inverted_y": False,
        "referer": "",
        "userAgent": "",
        # Re-fetch cached tiles after 60 minutes so server-side updates reach users.
        "timesupported": True,
        "expire": 60,
        "inversiveZoom": False,
        "ext": ".png",
        "tileSize": 256,
        "bitDensity": 32,
        "avgSize": 4000,
        "rule": "",
    }


def gpx_entry_name(layer: Layer) -> str:
    return f"tracks/syria-maps-{layer.id}.gpx"


def build_items(registry: LayerRegistry, opts: PackageOptions, gpx_layers: list[Layer]) -> dict[str, Any]:
    tile_layers = [l for l in registry.all() if l.osmand.map_source and l.status != "planned"]
    items: list[dict[str, Any]] = [
        {
            "type": "PLUGIN",
            "pluginId": PLUGIN_ID,
            "version": opts.plugin_version,
            "name": {"": "Syria Maps", "en": "Syria Maps", "ar": "خرائط سوريا"},
            "description": {
                "": "Community map layers for Syria: online overlays and offline waypoint datasets. "
                "Open source, data under ODbL/CC licenses.",
                "en": "Community map layers for Syria: online overlays and offline waypoint datasets. "
                "Open source, data under ODbL/CC licenses.",
                "ar": "طبقات خرائط مجتمعية لسوريا: طبقات تراكب عبر الإنترنت وبيانات نقاط للاستخدام دون اتصال. "
                "مشروع مفتوح المصدر.",
            },
            "icon": {"": f"@{ICON_FILE}"},
            "image": {"": f"@{IMAGE_FILE}"},
        },
        {"type": "RESOURCES", "pluginId": PLUGIN_ID, "file": RES_DIR},
    ]
    if tile_layers:
        items.append(
            {
                "type": "MAP_SOURCES",
                "pluginId": PLUGIN_ID,
                "items": [map_source_entry(opts.base_url, l) for l in tile_layers],
            }
        )
    for layer in gpx_layers:
        items.append({"type": "FILE", "pluginId": PLUGIN_ID, "subtype": "gpx", "file": "/" + gpx_entry_name(layer)})
    return {"version": 1, "items": items}


def build_osf(registry: LayerRegistry, storage: Storage, opts: PackageOptions) -> bytes:
    gpx_layers = (
        [l for l in registry.all() if l.osmand.gpx_snapshot and l.status != "planned"] if opts.include_gpx else []
    )
    files: dict[str, bytes] = {
        f"{RES_DIR}/{ICON_FILE}": _icon_png(),
        f"{RES_DIR}/{IMAGE_FILE}": _image_png(),
    }
    for layer in gpx_layers:
        files[gpx_entry_name(layer)] = layer_gpx(layer, storage.iter_layer_features(layer.id), storage.latest_version(layer.id))
    items = build_items(registry, opts, gpx_layers)

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        _write(zf, "items.json", json.dumps(items, ensure_ascii=False, indent=2).encode("utf-8"))
        for name in sorted(files):
            _write(zf, name, files[name])
    return buf.getvalue()


def _write(zf: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(name, date_time=ZIP_DATE)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    zf.writestr(info, data)


# -- original artwork (generated, no third-party assets) ---------------------

GREEN = (0, 122, 61, 255)
WHITE = (255, 255, 255, 255)


def _pin(draw: ImageDraw.ImageDraw, cx: float, cy: float, r: float, fill, hole) -> None:
    draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=fill)
    draw.polygon([(cx - r * 0.82, cy + r * 0.55), (cx + r * 0.82, cy + r * 0.55), (cx, cy + r * 2.1)], fill=fill)
    draw.ellipse((cx - r * 0.42, cy - r * 0.42, cx + r * 0.42, cy + r * 0.42), fill=hole)


def _png(img: Image.Image) -> bytes:
    out = io.BytesIO()
    img.save(out, "PNG", optimize=True)
    return out.getvalue()


def _icon_png() -> bytes:
    size = 192
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((8, 8, size - 8, size - 8), radius=40, fill=GREEN)
    _pin(d, size / 2, size * 0.40, size * 0.20, WHITE, GREEN)
    return _png(img)


def _image_png() -> bytes:
    w, h = 640, 320
    img = Image.new("RGBA", (w, h), GREEN)
    d = ImageDraw.Draw(img)
    for i in range(-h, w, 32):  # subtle grid suggesting a map
        d.line([(i, 0), (i + h, h)], fill=(255, 255, 255, 40), width=2)
    for y in range(0, h, 40):
        d.line([(0, y), (w, y)], fill=(255, 255, 255, 25), width=1)
    _pin(d, w / 2, h * 0.38, 56, WHITE, GREEN)
    return _png(img)
