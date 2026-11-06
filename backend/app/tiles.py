"""Transparent raster overlay tiles (PNG, XYZ scheme) rendered from layer features.

Tiles come in two pixel densities for the same XYZ area: 256 px (``scale=1``) and 512 px
(``scale=2``, served as ``@2x``). Phones have 2-3x dense screens; a 256 px tile is stretched there and
looks soft, so the OsmAnd package uses the 512 px variant.
"""

from __future__ import annotations

import io

from PIL import Image, ImageDraw

from app.geo import lonlat_to_pixel, tile_bounds
from app.layers import Layer
from app.storage.base import Feature, Storage

TILE_SIZE = 256
MAX_FEATURES_PER_TILE = 5000


SCALES = (1, 2)


def empty_tile(scale: int = 1) -> bytes:
    size = TILE_SIZE * scale
    buf = io.BytesIO()
    Image.new("RGBA", (size, size), (0, 0, 0, 0)).save(buf, "PNG", optimize=True)
    return buf.getvalue()


def _marker_radius(z: int) -> int:
    return max(3, min(9, z - 7))


def render_tile(storage: Storage, layer: Layer, z: int, x: int, y: int, scale: int = 1) -> bytes:
    if scale not in SCALES:
        raise ValueError(f"scale must be one of {SCALES}")
    if z < layer.zoom.min or z > layer.zoom.max:
        return empty_tile(scale)
    size = TILE_SIZE * scale
    bounds = tile_bounds(z, x, y)
    radius = _marker_radius(z)  # in 256-px units; everything below is multiplied by scale
    # Include features just outside the tile so markers on the edge are not cut.
    pad_lon = (bounds.max_lon - bounds.min_lon) * (radius + 2) / TILE_SIZE
    pad_lat = (bounds.max_lat - bounds.min_lat) * (radius + 2) / TILE_SIZE
    features = storage.features_in_bbox(layer.id, bounds.expand(pad_lon, pad_lat), MAX_FEATURES_PER_TILE)
    if not features:
        return empty_tile(scale)

    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    ox, oy = x * size, y * size

    def px(lon: float, lat: float) -> tuple[float, float]:
        gx, gy = lonlat_to_pixel(lon, lat, z, size)
        return gx - ox, gy - oy

    for f in features:
        _draw_feature(draw, f, layer.color_for(f.kind), radius, px, scale)

    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def _rgba(hex_color: str, alpha: int = 255) -> tuple[int, int, int, int]:
    return int(hex_color[1:3], 16), int(hex_color[3:5], 16), int(hex_color[5:7], 16), alpha


def _draw_feature(draw: ImageDraw.ImageDraw, f: Feature, color: str, radius: int, px, scale: int = 1) -> None:
    geom = f.geometry
    gtype, coords = geom["type"], geom["coordinates"]
    if gtype == "Point":
        cx, cy = px(*coords[:2])
        r, halo = radius * scale, scale
        draw.ellipse((cx - r - halo, cy - r - halo, cx + r + halo, cy + r + halo), fill=(255, 255, 255, 230))
        draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=_rgba(color))
    elif gtype in ("LineString", "MultiLineString"):
        lines = [coords] if gtype == "LineString" else coords
        for line in lines:
            draw.line([px(*c[:2]) for c in line], fill=_rgba(color),
                      width=max(2, radius // 2 + 1) * scale, joint="curve")
    elif gtype in ("Polygon", "MultiPolygon"):
        polygons = [coords] if gtype == "Polygon" else coords
        for poly in polygons:
            draw.polygon([px(*c[:2]) for c in poly[0]], fill=_rgba(color, 90), outline=_rgba(color), width=scale)
