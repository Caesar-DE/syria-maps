-- Community submissions from the app (moderated). Stores only what the sender typed and the chosen
-- location: no names, accounts, device ids or IP addresses.

CREATE TABLE submissions (
    id                   text PRIMARY KEY,
    type                 text NOT NULL CHECK (type IN ('new_place', 'wrong_name', 'closed', 'other')),
    geom                 geometry (Point, 4326) NOT NULL,
    kind                 text,
    name_ar              text CHECK (length(name_ar) <= 200),
    name_en              text CHECK (length(name_en) <= 200),
    current_name         text CHECK (length(current_name) <= 200),
    details              text CHECK (length(details) <= 1000),
    status               text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'accepted', 'rejected')),
    created_at           timestamptz NOT NULL DEFAULT now(),
    reviewed_at          timestamptz,
    review_note          text,
    feature_external_id  text
);

CREATE INDEX submissions_status_created_idx ON submissions (status, created_at DESC);
