# Data model

Schema: [`database/migrations/001_initial.sql`](../database/migrations/001_initial.sql). All geometries are
WGS84 (`EPSG:4326`), all timestamps `timestamptz` (UTC).

## `layers`
Mirror of `layers/*.yaml` (`app.cli sync-layers`), used for foreign keys and audits.

## `features`
| Column | Notes |
|---|---|
| `layer_id`, `external_id` | Unique together; `external_id` is the stable id from the source (e.g. `osm:node/123`) |
| `kind` | Must be defined in the layer's `kinds` |
| `name_ar`, `name_en` | Arabic preferred |
| `properties` | Extra attributes (jsonb) |
| `geom` | Valid, non-empty geometry; GiST index on `geom` and `geom::geography` |
| `source`, `license`, `attribution` | Required for every record |
| `is_synthetic` | `true` for development fixtures |
| `created_at`, `updated_at` | |

## `notices` (traffic and safety)
| Column | Notes |
|---|---|
| `notice_type` | `road_closure`, `construction`, `congestion`, `restriction`, `road_hazard`, `safety_notice` |
| `source_name`, `source_url` | Authorized source |
| `verification_status` | `unverified` / `verified` / `rejected` |
| `verified_by`, `verified_at` | Required (DB constraint) when verified |
| `reported_at`, `valid_from`, `expires_at` | `expires_at` required when verified, must be after `valid_from` |

**Public rule** (SQL and Python, kept identical): `verified` ∧ `verified_at` ∧ non-empty `source_name`
∧ `valid_from ≤ now < expires_at`. Everything else is never returned by the public API.

## `dataset_versions`
`(layer_id, version)` with SHA-256 `checksum` of the canonical import and `feature_count`.
A new version is created only when the checksum changes.

## Import format
GeoJSON `FeatureCollection` + `metadata` (`layer_id`, `source`, `license`, `attribution`, `synthetic`,
optional `kind: notices`). See the module docstring of `backend/app/datasets.py` and the fixture in
`database/fixtures/`.
