# API request examples

```bash
API=http://localhost:8000

curl "$API/health"
curl "$API/api/v1/layers"
curl "$API/api/v1/layers/public-services"

# features in a bounding box (minLon,minLat,maxLon,maxLat)
curl "$API/api/v1/layers/public-services/features?bbox=36.1,33.4,36.3,33.5&limit=20"

# nearest hospitals and pharmacies within 3 km of a point (lat,lon)
curl "$API/api/v1/public-services?kind=hospital,pharmacy&near=33.45,36.20&radius_m=3000"

# Arabic text search
curl -G "$API/api/v1/layers/public-services/features" --data-urlencode "q=صيدلية"

# verified road closures only
curl "$API/api/v1/road-closures?bbox=35.5,32.3,42.4,37.4"

# dataset version check and downloads
curl "$API/api/v1/datasets/public-services/version"
curl -OJ "$API/api/v1/datasets/public-services/download?format=gpx"

# a tile and the OsmAnd package
curl -o tile.png "$API/tiles/public-services/12/2459/1643.png"
curl -OJ "$API/osmand/syria-maps.osf"
```
