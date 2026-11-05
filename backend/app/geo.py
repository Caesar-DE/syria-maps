"""Small, dependency-free geographic helpers (bbox parsing, Web-Mercator tile math)."""

from __future__ import annotations

import math
from dataclasses import dataclass

# Rough envelope of Syria (lon/lat, WGS84). Used for validation warnings only,
# never to decide what is "inside" the country.
SYRIA_ENVELOPE = (35.5, 32.3, 42.4, 37.4)
MAX_MERCATOR_LAT = 85.05112878


@dataclass(frozen=True)
class BBox:
    min_lon: float
    min_lat: float
    max_lon: float
    max_lat: float

    @classmethod
    def parse(cls, value: str) -> "BBox":
        parts = value.split(",")
        if len(parts) != 4:
            raise ValueError("bbox must be 'minLon,minLat,maxLon,maxLat'")
        try:
            min_lon, min_lat, max_lon, max_lat = (float(p) for p in parts)
        except ValueError:
            raise ValueError("bbox values must be numbers") from None
        if not all(math.isfinite(v) for v in (min_lon, min_lat, max_lon, max_lat)):
            raise ValueError("bbox values must be finite")
        if not (-180 <= min_lon <= 180 and -180 <= max_lon <= 180):
            raise ValueError("longitude must be within [-180, 180]")
        if not (-90 <= min_lat <= 90 and -90 <= max_lat <= 90):
            raise ValueError("latitude must be within [-90, 90]")
        if min_lon >= max_lon or min_lat >= max_lat:
            raise ValueError("bbox min values must be smaller than max values")
        return cls(min_lon, min_lat, max_lon, max_lat)

    def expand(self, d_lon: float, d_lat: float) -> "BBox":
        return BBox(
            max(-180.0, self.min_lon - d_lon),
            max(-90.0, self.min_lat - d_lat),
            min(180.0, self.max_lon + d_lon),
            min(90.0, self.max_lat + d_lat),
        )


def tile_bounds(z: int, x: int, y: int) -> BBox:
    """WGS84 bounds of an XYZ (slippy map, y from the north) tile."""
    n = 2**z
    min_lon = x / n * 360.0 - 180.0
    max_lon = (x + 1) / n * 360.0 - 180.0
    max_lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / n))))
    min_lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 1) / n))))
    return BBox(min_lon, min_lat, max_lon, max_lat)


def lonlat_to_pixel(lon: float, lat: float, z: int, tile_size: int = 256) -> tuple[float, float]:
    """Global pixel coordinates at zoom z."""
    lat = max(-MAX_MERCATOR_LAT, min(MAX_MERCATOR_LAT, lat))
    scale = tile_size * 2**z
    px = (lon + 180.0) / 360.0 * scale
    sin = math.sin(math.radians(lat))
    py = (0.5 - math.log((1 + sin) / (1 - sin)) / (4 * math.pi)) * scale
    return px, py


def haversine_m(lon1: float, lat1: float, lon2: float, lat2: float) -> float:
    r = 6_371_008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def in_syria_envelope(lon: float, lat: float) -> bool:
    min_lon, min_lat, max_lon, max_lat = SYRIA_ENVELOPE
    return min_lon <= lon <= max_lon and min_lat <= lat <= max_lat
