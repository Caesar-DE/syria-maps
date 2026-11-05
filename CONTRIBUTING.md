# Contributing / المساهمة

أهلاً بك! Contributions in Arabic or English are welcome.

## Ways to help

1. **Map data first in OpenStreetMap.** Street names (`name:ar`), neighbourhoods, buildings
   (`height`, `building:levels`) and public services belong in OSM. They then reach every OsmAnd
   user through the official maps. Follow the OSM rules: no copying from Google Maps or other
   proprietary sources.
2. **New layers** — add a YAML file in `layers/` and a dataset (see [docs/adding-layers.md](docs/adding-layers.md)).
3. **Code** — backend, validation, packaging, docs, translations.
4. **Device testing** — import the `.osf` on a real phone and report results using the checklist in
   [docs/osmand-integration.md](docs/osmand-integration.md).

## Development workflow

```bash
cd backend
python -m venv .venv && . .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -r requirements-dev.txt
pytest
python -m app.cli validate-layers
python -m app.cli validate-dataset ../database/fixtures/*.geojson
```

- Keep the API, layer docs and README status tables in sync. **Never mark a planned feature as implemented.**
- Add tests for every endpoint or validation rule you change.
- Pull requests must pass CI (unit + PostGIS tests, `.osf` validation, Compose smoke test).

## Data rules (mandatory)

- Every dataset declares `source`, `license` and `attribution`; the license must allow redistribution.
- Never commit invented real-world data. Development data must be marked `synthetic: true` and named "Synthetic …".
- Safety and traffic notices follow [docs/safety-policy.md](docs/safety-policy.md) — verified, sourced and expiring only.
- No personal data (names of private individuals, phone numbers of private persons, home addresses).

## Commit messages

Short imperative subject (`Add pharmacy kind to osm-public-services`), body explaining *why*.
