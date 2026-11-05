# OsmAnd package

The `.osf` package is **generated**, not hand-written, because the tile URL depends on where the API
is deployed:

```bash
cd backend
python -m app.cli build-osf --base-url https://maps.example.org --storage memory
python -m app.cli validate-osf ../dist/syria-maps.osf
```

- [`items.example.json`](items.example.json) — the `items.json` produced for `https://maps.example.org`.
- Builder: `backend/app/osmand/package.py`; format facts: `backend/app/osmand/spec.py`;
  validator: `backend/app/osmand/validate.py`.
- Background and limitations: [`docs/feasibility.md`](../docs/feasibility.md),
  [`docs/osmand-integration.md`](../docs/osmand-integration.md).

Note: Structural validation passes; **import on a real Android device has not been verified yet.**
