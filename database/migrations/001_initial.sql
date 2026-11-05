-- Syria Maps — initial schema (PostgreSQL 16 + PostGIS 3)

CREATE EXTENSION IF NOT EXISTS postgis;

-- Mirror of layers/*.yaml (synchronised by `python -m app.cli sync-layers`).
CREATE TABLE layers (
    id              text PRIMARY KEY CHECK (id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    title_ar        text NOT NULL,
    title_en        text NOT NULL,
    category        text NOT NULL,
    status          text NOT NULL CHECK (status IN ('implemented', 'experimental', 'planned')),
    license         text NOT NULL,
    attribution     text NOT NULL,
    synthetic       boolean NOT NULL DEFAULT false,
    verified_only   boolean NOT NULL DEFAULT false,
    definition      jsonb NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE features (
    id              bigserial PRIMARY KEY,
    layer_id        text NOT NULL REFERENCES layers (id) ON DELETE CASCADE,
    external_id     text NOT NULL,
    kind            text,
    name_ar         text,
    name_en         text,
    properties      jsonb NOT NULL DEFAULT '{}'::jsonb,
    geom            geometry (Geometry, 4326) NOT NULL CHECK (ST_IsValid(geom) AND NOT ST_IsEmpty(geom)),
    source          text NOT NULL,
    license         text NOT NULL,
    attribution     text NOT NULL,
    is_synthetic    boolean NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (layer_id, external_id)
);

CREATE INDEX features_geom_gix ON features USING gist (geom);
CREATE INDEX features_geog_gix ON features USING gist ((geom::geography));
CREATE INDEX features_layer_kind_idx ON features (layer_id, kind);

-- Traffic and safety notices. Only verified, sourced, time-bounded rows are public.
CREATE TABLE notices (
    id                   bigserial PRIMARY KEY,
    layer_id             text NOT NULL REFERENCES layers (id) ON DELETE CASCADE,
    external_id          text NOT NULL,
    notice_type          text NOT NULL CHECK (notice_type IN
                          ('road_closure', 'construction', 'congestion', 'restriction', 'road_hazard', 'safety_notice')),
    geom                 geometry (Geometry, 4326) NOT NULL CHECK (ST_IsValid(geom)),
    title_ar             text NOT NULL,
    title_en             text,
    description_ar       text,
    description_en       text,
    source_name          text NOT NULL,
    source_url           text,
    verification_status  text NOT NULL DEFAULT 'unverified'
                          CHECK (verification_status IN ('unverified', 'verified', 'rejected')),
    verified_by          text,
    verified_at          timestamptz,
    reported_at          timestamptz NOT NULL,
    valid_from           timestamptz NOT NULL,
    expires_at           timestamptz,
    is_synthetic         boolean NOT NULL DEFAULT false,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (layer_id, external_id),
    CHECK (expires_at IS NULL OR expires_at > valid_from),
    CHECK (verification_status <> 'verified'
           OR (verified_at IS NOT NULL AND expires_at IS NOT NULL AND length(btrim(source_name)) > 0))
);

CREATE INDEX notices_geom_gix ON notices USING gist (geom);
CREATE INDEX notices_public_idx ON notices (layer_id, notice_type, expires_at)
    WHERE verification_status = 'verified';

CREATE TABLE dataset_versions (
    layer_id        text NOT NULL REFERENCES layers (id) ON DELETE CASCADE,
    version         integer NOT NULL CHECK (version > 0),
    checksum        text NOT NULL,
    feature_count   integer NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (layer_id, version)
);
