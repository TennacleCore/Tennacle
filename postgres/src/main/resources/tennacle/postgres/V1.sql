-- times are epoch millis; 9223372036854775807 is forever

CREATE TABLE player_data (
    player     uuid        NOT NULL,
    key        text        NOT NULL,
    value      bytea,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (player, key)
);

CREATE TABLE players (
    id        uuid   PRIMARY KEY,
    name      text   NOT NULL,
    last_seen bigint NOT NULL
);
CREATE INDEX players_name ON players (lower(name), last_seen DESC);

CREATE TABLE matches (
    id         text   PRIMARY KEY,
    code       text   NOT NULL,
    mode       text   NOT NULL,
    platform   text   NOT NULL,
    outcome    text   NOT NULL,
    winner     text,
    started_at bigint NOT NULL,
    ended_at   bigint NOT NULL,
    ticks      bigint NOT NULL
);
CREATE INDEX matches_ended ON matches (ended_at DESC);

-- player is null only for a match imported from a log that kept names alone
CREATE TABLE match_players (
    match_id text NOT NULL REFERENCES matches (id) ON DELETE CASCADE,
    side     int  NOT NULL,
    seat     int  NOT NULL,
    player   uuid,
    name     text NOT NULL,
    PRIMARY KEY (match_id, side, seat)
);
CREATE INDEX match_players_player ON match_players (player);

CREATE TABLE punishments (
    id             bigserial PRIMARY KEY,
    player         uuid      NOT NULL,
    kind           text      NOT NULL,
    reason         text      NOT NULL,
    by_id          uuid,
    by_name        text      NOT NULL,
    at             bigint    NOT NULL,
    until          bigint    NOT NULL,
    lifted_by      uuid,
    lifted_by_name text      NOT NULL DEFAULT '',
    lifted_at      bigint    NOT NULL DEFAULT 0
);
CREATE INDEX punishments_player ON punishments (player, at DESC);

CREATE TABLE imports (
    name text        PRIMARY KEY,
    at   timestamptz NOT NULL DEFAULT now()
);
