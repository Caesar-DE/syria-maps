"""Facts about the OsmAnd ``.osf`` format, transcribed from the OsmAnd source (master, 2026-11).

See docs/feasibility.md for the files they come from. Keep this module free of guesses:
anything not confirmed in the OsmAnd source must not be added here.
"""

# SettingsHelper.VERSION — highest root "version" OsmAnd accepts.
MAX_OSF_VERSION = 3

# SettingsItemType enum.
ITEM_TYPES = frozenset(
    {
        "GLOBAL", "PROFILE", "PLUGIN", "DATA", "FILE", "RESOURCES", "GPX", "GPX_DIR",
        "QUICK_ACTIONS", "POI_UI_FILTERS", "MAP_SOURCES", "AVOID_ROADS", "SUGGESTED_DOWNLOADS",
        "DOWNLOADS", "OSM_NOTES", "OSM_EDITS", "FAVOURITES", "ACTIVE_MARKERS", "HISTORY_MARKERS",
        "SEARCH_HISTORY", "NAVIGATION_HISTORY", "ONLINE_ROUTING_ENGINES", "ITINERARY_GROUPS",
    }
)

# FileSettingsItem.FileSubtype#subtypeName (UNKNOWN = "" is rejected by OsmAnd).
FILE_SUBTYPES = frozenset(
    {
        "other", "routing_config", "rendering_style", "wiki_map", "srtm_map", "terrain", "obf_map",
        "tiles_map", "road_map", "gpx", "tts_voice", "voice", "travel", "multimedia_notes",
        "nautical_depth", "favorites_backup", "colors_palette", "attached_media",
    }
)

# CustomOsmandPlugin(app, json) reads these keys.
PLUGIN_KEYS = frozenset(
    {"type", "pluginId", "version", "name", "description", "icon", "image", "regionsJson",
     "rendererNames", "routerNames", "pluginResDir"}
)

# MapSourcesSettingsItem.readItemsFromJson — key -> expected Python type.
MAP_SOURCE_KEYS: dict[str, type | tuple[type, ...]] = {
    "sql": bool,
    "name": str,
    "minZoom": int,
    "maxZoom": int,
    "url": str,
    "randoms": str,
    "ellipsoid": bool,
    "inverted_y": bool,
    "referer": str,
    "userAgent": str,
    "timesupported": bool,  # lower-case "s" in OsmAnd
    "expire": int,  # minutes when 0 < expire < 3_600_000, used only if timesupported
    "inversiveZoom": bool,  # spelling as in OsmAnd; SQLite sources only
    "ext": str,
    "tileSize": int,
    "bitDensity": int,
    "avgSize": int,
    "rule": str,
}
MAP_SOURCE_REQUIRED = ("name", "url", "minZoom", "maxZoom", "ext", "tileSize")
