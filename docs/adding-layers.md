# Adding a community layer / إضافة طبقة مجتمعية

No core code changes are needed. A layer is one YAML file plus (optionally) a dataset.

## 1. Define the layer

Create `layers/<id>.yaml` (file name = `id`, lowercase kebab-case). Template:
[`examples/community-layer/water-points.yaml`](../examples/community-layer/water-points.yaml).

Key fields:

| Field | Meaning |
|---|---|
| `category` | `base`, `services`, `buildings`, `traffic`, `safety`, `community` |
| `status` | `planned` (no data), `experimental`, `implemented` |
| `license`, `attribution`, `source` | Required. License must allow redistribution (ODbL, CC0, CC-BY …) |
| `kinds` | Allowed feature kinds with colour (`#rrggbb`) and ar/en labels |
| `tiles` | Serve `/tiles/<id>/…` |
| `osmand.map_source` | Include the tile source in the `.osf` package (requires `tiles`) |
| `osmand.gpx_snapshot` | Include an offline GPX of the layer in the `.osf` package |
| `verified_only` | Mandatory for `safety`; data is then published only via `/api/v1/notices` |

Check it: `python -m app.cli validate-layers`.

## 2. Prepare the dataset

GeoJSON FeatureCollection with `metadata` (`layer_id`, `source`, `license`, `attribution`, `synthetic`)
and `properties.id`, `kind`, `name_ar`, `name_en` per feature. Example:
[`examples/community-layer/water-points.sample.geojson`](../examples/community-layer/water-points.sample.geojson).

```bash
python -m app.cli validate-dataset my-layer.geojson
```

## 3. Open a pull request

Include: where the data comes from, proof that the license allows redistribution, and who maintains it.
A maintainer imports it:

```bash
docker compose run --rm -v "$PWD/my-layer.geojson:/data.geojson:ro" migrate python -m app.cli import /data.geojson
```

## Review checklist (maintainers)

- [ ] License compatible (no Google/proprietary-derived data)
- [ ] No personal data, nothing that marks people or groups
- [ ] `synthetic` flag correct
- [ ] Status in README tables updated
