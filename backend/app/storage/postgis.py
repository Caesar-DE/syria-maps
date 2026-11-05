"""PostgreSQL/PostGIS storage."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool

from app.datasets import checksum, parse_time
from app.geo import BBox
from app.layers import Layer
from app.storage.base import DatasetVersion, Feature, FeatureQuery, Notice, NoticeQuery

FEATURE_COLUMNS = """
    f.id, f.layer_id, f.external_id, f.kind, f.name_ar, f.name_en, f.properties,
    ST_AsGeoJSON(f.geom, 7)::json AS geometry, f.source, f.license, f.attribution,
    f.is_synthetic, f.updated_at
"""

# Keep in sync with app.storage.memory.is_publishable.
PUBLIC_NOTICE_SQL = """
    n.verification_status = 'verified'
    AND n.verified_at IS NOT NULL
    AND length(btrim(n.source_name)) > 0
    AND n.expires_at IS NOT NULL
    AND n.valid_from <= %(now)s AND %(now)s < n.expires_at
"""


class PostgisStorage:
    def __init__(self, dsn: str, max_size: int = 10) -> None:
        self.pool = ConnectionPool(dsn, min_size=1, max_size=max_size, kwargs={"row_factory": dict_row}, open=True)

    def close(self) -> None:
        self.pool.close()

    def ping(self) -> bool:
        try:
            with self.pool.connection(timeout=3) as conn:
                return conn.execute("SELECT postgis_version() AS v").fetchone() is not None
        except Exception:
            return False

    # -- reads -------------------------------------------------------------

    def query_features(self, q: FeatureQuery) -> tuple[list[Feature], int]:
        where = ["f.layer_id = %(layer_id)s"]
        params: dict[str, Any] = {"layer_id": q.layer_id, "limit": q.limit, "offset": q.offset}
        distance_sql = "NULL::float8"
        order = "f.id"
        if q.bbox:
            where.append("f.geom && ST_MakeEnvelope(%(x1)s, %(y1)s, %(x2)s, %(y2)s, 4326)")
            where.append("ST_Intersects(f.geom, ST_MakeEnvelope(%(x1)s, %(y1)s, %(x2)s, %(y2)s, 4326))")
            params.update(x1=q.bbox.min_lon, y1=q.bbox.min_lat, x2=q.bbox.max_lon, y2=q.bbox.max_lat)
        if q.kinds:
            where.append("f.kind = ANY(%(kinds)s)")
            params["kinds"] = q.kinds
        if q.text:
            where.append("(f.name_ar ILIKE %(text)s OR f.name_en ILIKE %(text)s)")
            params["text"] = f"%{_escape_like(q.text)}%"
        if q.near:
            params.update(lon=q.near[0], lat=q.near[1])
            point = "ST_SetSRID(ST_MakePoint(%(lon)s, %(lat)s), 4326)::geography"
            distance_sql = f"round(ST_Distance(f.geom::geography, {point})::numeric, 1)::float8"
            order = "distance_m, f.id"
            if q.radius_m is not None:
                where.append(f"ST_DWithin(f.geom::geography, {point}, %(radius)s)")
                params["radius"] = q.radius_m
        sql = f"""
            SELECT {FEATURE_COLUMNS}, {distance_sql} AS distance_m, count(*) OVER () AS total
            FROM features f WHERE {' AND '.join(where)}
            ORDER BY {order} LIMIT %(limit)s OFFSET %(offset)s
        """
        with self.pool.connection() as conn:
            rows = conn.execute(sql, params).fetchall()
            if rows:
                total = rows[0]["total"]
            else:
                count_sql = f"SELECT count(*) AS c FROM features f WHERE {' AND '.join(where)}"
                total = conn.execute(count_sql, params).fetchone()["c"]
        return [_feature(r) for r in rows], total

    def get_feature(self, layer_id: str, feature_id: int) -> Feature | None:
        with self.pool.connection() as conn:
            row = conn.execute(
                f"SELECT {FEATURE_COLUMNS} FROM features f WHERE f.layer_id = %s AND f.id = %s",
                (layer_id, feature_id),
            ).fetchone()
        return _feature(row) if row else None

    def iter_layer_features(self, layer_id: str) -> list[Feature]:
        with self.pool.connection() as conn:
            rows = conn.execute(
                f"SELECT {FEATURE_COLUMNS} FROM features f WHERE f.layer_id = %s ORDER BY f.id", (layer_id,)
            ).fetchall()
        return [_feature(r) for r in rows]

    def features_in_bbox(self, layer_id: str, bbox: BBox, limit: int) -> list[Feature]:
        rows, _ = self.query_features(FeatureQuery(layer_id=layer_id, bbox=bbox, limit=limit))
        return rows

    def public_notices(self, q: NoticeQuery) -> tuple[list[Notice], int]:
        where = [PUBLIC_NOTICE_SQL]
        params: dict[str, Any] = {"now": q.now, "limit": q.limit, "offset": q.offset}
        if q.layer_ids:
            where.append("n.layer_id = ANY(%(layers)s)")
            params["layers"] = q.layer_ids
        if q.notice_types:
            where.append("n.notice_type = ANY(%(types)s)")
            params["types"] = q.notice_types
        if q.bbox:
            where.append("ST_Intersects(n.geom, ST_MakeEnvelope(%(x1)s, %(y1)s, %(x2)s, %(y2)s, 4326))")
            params.update(x1=q.bbox.min_lon, y1=q.bbox.min_lat, x2=q.bbox.max_lon, y2=q.bbox.max_lat)
        sql = f"""
            SELECT n.*, ST_AsGeoJSON(n.geom, 7)::json AS geometry, count(*) OVER () AS total
            FROM notices n WHERE {' AND '.join(where)}
            ORDER BY n.valid_from DESC, n.id DESC LIMIT %(limit)s OFFSET %(offset)s
        """
        with self.pool.connection() as conn:
            rows = conn.execute(sql, params).fetchall()
        total = rows[0]["total"] if rows else 0
        return [_notice(r) for r in rows], total

    def latest_version(self, layer_id: str) -> DatasetVersion | None:
        versions = self.list_versions(layer_id)
        return versions[-1] if versions else None

    def list_versions(self, layer_id: str | None = None) -> list[DatasetVersion]:
        sql = "SELECT * FROM dataset_versions"
        params: tuple = ()
        if layer_id:
            sql += " WHERE layer_id = %s"
            params = (layer_id,)
        with self.pool.connection() as conn:
            rows = conn.execute(sql + " ORDER BY layer_id, version", params).fetchall()
        return [DatasetVersion(**r) for r in rows]

    def feature_counts(self) -> dict[str, int]:
        with self.pool.connection() as conn:
            rows = conn.execute("SELECT layer_id, count(*) AS c FROM features GROUP BY layer_id").fetchall()
        return {r["layer_id"]: r["c"] for r in rows}

    # -- writes (CLI only) -------------------------------------------------

    def migrate(self, migrations_dir: Path) -> list[str]:
        applied: list[str] = []
        with self.pool.connection() as conn:
            conn.execute(
                "CREATE TABLE IF NOT EXISTS schema_migrations "
                "(name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())"
            )
            done = {r["name"] for r in conn.execute("SELECT name FROM schema_migrations").fetchall()}
            for file in sorted(migrations_dir.glob("*.sql")):
                if file.name in done:
                    continue
                with conn.transaction():
                    conn.execute(file.read_text(encoding="utf-8"))
                    conn.execute("INSERT INTO schema_migrations (name) VALUES (%s)", (file.name,))
                applied.append(file.name)
        return applied

    def sync_layers(self, layers: list[Layer]) -> None:
        with self.pool.connection() as conn, conn.transaction():
            for layer in layers:
                conn.execute(
                    """
                    INSERT INTO layers (id, title_ar, title_en, category, status, license, attribution,
                                        synthetic, verified_only, definition, updated_at)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, now())
                    ON CONFLICT (id) DO UPDATE SET
                        title_ar = EXCLUDED.title_ar, title_en = EXCLUDED.title_en,
                        category = EXCLUDED.category, status = EXCLUDED.status,
                        license = EXCLUDED.license, attribution = EXCLUDED.attribution,
                        synthetic = EXCLUDED.synthetic, verified_only = EXCLUDED.verified_only,
                        definition = EXCLUDED.definition, updated_at = now()
                    """,
                    (
                        layer.id, layer.title.ar, layer.title.en, layer.category, layer.status,
                        layer.license, layer.attribution, layer.synthetic, layer.verified_only,
                        Jsonb(layer.model_dump(mode="json")),
                    ),
                )

    def import_collection(self, data: dict[str, Any]) -> DatasetVersion | None:
        """Upsert a validated dataset; returns the new version or None if unchanged."""
        meta = data["metadata"]
        layer_id = meta["layer_id"]
        if meta.get("kind") == "notices":
            self._import_notices(layer_id, meta, data["features"])
            return None
        ids = [str(f["properties"]["id"]) for f in data["features"]]
        with self.pool.connection() as conn, conn.transaction():
            for feat in data["features"]:
                props = dict(feat["properties"])
                conn.execute(
                    """
                    INSERT INTO features (layer_id, external_id, kind, name_ar, name_en, properties, geom,
                                          source, license, attribution, is_synthetic)
                    VALUES (%s, %s, %s, %s, %s, %s, ST_SetSRID(ST_GeomFromGeoJSON(%s), 4326), %s, %s, %s, %s)
                    ON CONFLICT (layer_id, external_id) DO UPDATE SET
                        kind = EXCLUDED.kind, name_ar = EXCLUDED.name_ar, name_en = EXCLUDED.name_en,
                        properties = EXCLUDED.properties, geom = EXCLUDED.geom, source = EXCLUDED.source,
                        license = EXCLUDED.license, attribution = EXCLUDED.attribution,
                        is_synthetic = EXCLUDED.is_synthetic, updated_at = now()
                    """,
                    (
                        layer_id, str(props.pop("id")), props.pop("kind", None), props.pop("name_ar", None),
                        props.pop("name_en", None), Jsonb(props), json.dumps(feat["geometry"]),
                        meta["source"], meta["license"], meta["attribution"], bool(meta.get("synthetic", False)),
                    ),
                )
            conn.execute(
                "DELETE FROM features WHERE layer_id = %s AND NOT (external_id = ANY(%s))", (layer_id, ids)
            )
            digest = checksum(data["features"])
            last = conn.execute(
                "SELECT version, checksum FROM dataset_versions WHERE layer_id = %s ORDER BY version DESC LIMIT 1",
                (layer_id,),
            ).fetchone()
            if last and last["checksum"] == digest:
                return None
            row = conn.execute(
                """
                INSERT INTO dataset_versions (layer_id, version, checksum, feature_count)
                VALUES (%s, %s, %s, %s) RETURNING *
                """,
                (layer_id, (last["version"] if last else 0) + 1, digest, len(ids)),
            ).fetchone()
        return DatasetVersion(**row)

    def _import_notices(self, layer_id: str, meta: dict, features: list[dict]) -> None:
        with self.pool.connection() as conn, conn.transaction():
            for feat in features:
                p = feat["properties"]
                conn.execute(
                    """
                    INSERT INTO notices (layer_id, external_id, notice_type, geom, title_ar, title_en,
                        description_ar, description_en, source_name, source_url, verification_status,
                        verified_by, verified_at, reported_at, valid_from, expires_at, is_synthetic)
                    VALUES (%s, %s, %s, ST_SetSRID(ST_GeomFromGeoJSON(%s), 4326), %s, %s, %s, %s, %s, %s,
                            %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (layer_id, external_id) DO UPDATE SET
                        notice_type = EXCLUDED.notice_type, geom = EXCLUDED.geom,
                        title_ar = EXCLUDED.title_ar, title_en = EXCLUDED.title_en,
                        description_ar = EXCLUDED.description_ar, description_en = EXCLUDED.description_en,
                        source_name = EXCLUDED.source_name, source_url = EXCLUDED.source_url,
                        verification_status = EXCLUDED.verification_status, verified_by = EXCLUDED.verified_by,
                        verified_at = EXCLUDED.verified_at, reported_at = EXCLUDED.reported_at,
                        valid_from = EXCLUDED.valid_from, expires_at = EXCLUDED.expires_at,
                        is_synthetic = EXCLUDED.is_synthetic, updated_at = now()
                    """,
                    (
                        layer_id, str(p["id"]), p["notice_type"], json.dumps(feat["geometry"]), p["title_ar"],
                        p.get("title_en"), p.get("description_ar"), p.get("description_en"), p["source_name"],
                        p.get("source_url"), p["verification_status"], p.get("verified_by"),
                        parse_time(p.get("verified_at")), parse_time(p["reported_at"]),
                        parse_time(p["valid_from"]), parse_time(p.get("expires_at")),
                        bool(meta.get("synthetic", False)),
                    ),
                )


def _escape_like(text: str) -> str:
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _feature(r: dict[str, Any]) -> Feature:
    return Feature(
        id=r["id"],
        layer_id=r["layer_id"],
        external_id=r["external_id"],
        geometry=r["geometry"],
        kind=r["kind"],
        name_ar=r["name_ar"],
        name_en=r["name_en"],
        properties=r["properties"],
        source=r["source"],
        license=r["license"],
        attribution=r["attribution"],
        is_synthetic=r["is_synthetic"],
        updated_at=r["updated_at"],
        distance_m=r.get("distance_m"),
    )


def _notice(r: dict[str, Any]) -> Notice:
    return Notice(
        id=r["id"],
        layer_id=r["layer_id"],
        notice_type=r["notice_type"],
        geometry=r["geometry"],
        title_ar=r["title_ar"],
        title_en=r["title_en"],
        description_ar=r["description_ar"],
        description_en=r["description_en"],
        source_name=r["source_name"],
        source_url=r["source_url"],
        verification_status=r["verification_status"],
        verified_at=r["verified_at"],
        reported_at=r["reported_at"],
        valid_from=r["valid_from"],
        expires_at=r["expires_at"],
        updated_at=r["updated_at"],
        is_synthetic=r["is_synthetic"],
    )
