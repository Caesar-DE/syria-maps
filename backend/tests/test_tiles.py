from __future__ import annotations

import io
import math

from PIL import Image


def tile_xy(lon: float, lat: float, z: int) -> tuple[int, int]:
    n = 2**z
    x = int((lon + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return x, y


def _png(r, size: int = 256) -> Image.Image:
    assert r.status_code == 200
    assert r.headers["content-type"] == "image/png"
    img = Image.open(io.BytesIO(r.content))
    assert img.size == (size, size)
    return img.convert("RGBA")


def test_tile_with_features_has_pixels(client, fixture_data):
    lon, lat = fixture_data["features"][0]["geometry"]["coordinates"]
    x, y = tile_xy(lon, lat, 14)
    img = _png(client.get(f"/tiles/public-services/14/{x}/{y}.png"))
    assert img.getchannel("A").getbbox() is not None  # something drawn
    assert "max-age" in client.get(f"/tiles/public-services/14/{x}/{y}.png").headers["cache-control"]


def test_empty_tile_is_transparent(client):
    x, y = tile_xy(-40.0, 10.0, 12)  # Atlantic Ocean
    img = _png(client.get(f"/tiles/public-services/12/{x}/{y}.png"))
    assert img.getchannel("A").getbbox() is None


def test_tile_below_min_zoom_is_empty(client):
    x, y = tile_xy(36.2, 33.45, 5)
    img = _png(client.get(f"/tiles/public-services/5/{x}/{y}.png"))
    assert img.getchannel("A").getbbox() is None


def test_tile_errors(client):
    assert client.get("/tiles/public-services/3/8/0.png").status_code == 404  # x out of range
    assert client.get("/tiles/public-services/23/0/0.png").status_code == 422
    assert client.get("/tiles/nope/1/0/0.png").status_code == 404
    assert client.get("/tiles/buildings-3d/1/0/0.png").status_code == 404  # planned
    assert client.get("/tiles/safety-notices/1/0/0.png").status_code == 404  # no tiles


def test_2x_tile_is_512px_and_matches_1x_layout(client, fixture_data):
    lon, lat = fixture_data["features"][0]["geometry"]["coordinates"]
    x, y = tile_xy(lon, lat, 14)
    one = _png(client.get(f"/tiles/public-services/14/{x}/{y}.png"))
    two = _png(client.get(f"/tiles/public-services/14/{x}/{y}@2x.png"), size=512)
    b1, b2 = one.getchannel("A").getbbox(), two.getchannel("A").getbbox()
    assert b1 is not None and b2 is not None
    # Same markers at twice the pixel density: bounding boxes scale by 2 (within antialiasing slack).
    assert all(abs(2 * a - b) <= 3 for a, b in zip(b1, b2))


def test_2x_tile_errors_and_empty(client):
    assert client.get("/tiles/public-services/3/8/0@2x.png").status_code == 404
    assert client.get("/tiles/nope/1/0/0@2x.png").status_code == 404
    x, y = tile_xy(-40.0, 10.0, 12)
    assert _png(client.get(f"/tiles/public-services/12/{x}/{y}@2x.png"), size=512).getchannel("A").getbbox() is None
