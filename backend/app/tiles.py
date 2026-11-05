"""Transparent raster overlay tiles (256 px PNG, XYZ scheme) rendered from layer features."""

from __future__ import annotations

import io

from PIL import Image, ImageDraw

from app.geo import lonlat_to_pixel, tile_bounds
from app.layers import Layer
from app.storage.base import Feature, Storage

TILE_SIZE = 256
MAX_FEATURES_PER_TILE = 5000


def empty_tile() -> bytes:
    buf = io.BytesIO()
    Image.new("RGBA", (TILE_SIZE, TILE_SIZE), (0, 0, 0, 0)).save(buf, "PNG", optimize=True)
    return buf.getvalue()


def _marker_radius(z: int) -> int:
    return max(3, min(9, z - 7))


def render_tile(storage: Storage, layer: Layer, z: int, x: int, y: int) -> bytes:
    if z < layer.zoom.min or z > layer.zoom.max:
        return empty_tile()
    bounds = tile_bounds(z, x, y)
    radius = _marker_radius(z)
    # Include features just outside the tile so markers on the edge are not cut.
    pad_lon = (bounds.max_lon - bounds.min_lon) * (radius + 2) / TILE_SIZE
    pad_lat = (bounds.max_lat - bounds.min_lat) * (radius + 2) / TILE_SIZE
    features = storage.features_in_bbox(layer.id, bounds.expand(pad_lon, pad_lat), MAX_FEATURES_PER_TILE)
    if not features:
        return empty_tile()

    img = Image.new("RGBA", (TILE_SIZE, TILE_SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    ox, oy = x * TILE_SIZE, y * TILE_SIZE

    def px(lon: float, lat: float) -> tuple[float, float]:
        gx, gy = lonlat_to_pixel(lon, lat, z, TILE_SIZE)
        return gx - ox, gy - oy

    for f in features:
        _draw_feature(draw, f, layer.color_for(f.kind), radius, px)

    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def _rgba(hex_color: str, alpha: int = 255) -> tuple[int, int, int, int]:
    return int(hex_color[1:3], 16), int(hex_color[3:5], 16), int(hex_color[5:7], 16), alpha


def _draw_feature(draw: ImageDraw.ImageDraw, f: Feature, color: str, radius: int, px) -> None:
    geom = f.geometry
    gtype, coords = geom["type"], geom["coordinates"]
    if gtype == "Point":
        cx, cy = px(*coords[:2])
        draw.ellipse((cx - radius - 1, cy - radius - 1, cx + radius + 1, cy + radius + 1), fill=(255, 255, 255, 230))
        draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=_rgba(color))
    elif gtype in ("LineString", "MultiLineString"):
        lines = [coords] if gtype == "LineString" else coords
        for line in lines:
            draw.line([px(*c[:2]) for c in line], fill=_rgba(color), width=max(2, radius // 2 + 1), joint="curve")
    elif gtype in ("Polygon", "MultiPolygon"):
        polygons = [coords] if gtype == "Polygon" else coords
        for poly in polygons:
            draw.polygon([px(*c[:2]) for c in poly[0]], fill=_rgba(color, 90), outline=_rgba(color))
