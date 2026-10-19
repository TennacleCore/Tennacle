-- the index over replay bytes kept elsewhere (a bucket, or the replay folder); expired rows stay, bytes go.
-- keep_until is a hold (a bookmark, a report): 0 = none, so only the server's keep applies
CREATE TABLE replays (
    id         text   PRIMARY KEY,
    code       text   NOT NULL,
    mode       text   NOT NULL,
    map        text   NOT NULL,
    played_at  bigint NOT NULL,
    ticks      bigint NOT NULL,
    size       bigint NOT NULL,
    blob       text   NOT NULL,
    keep_until bigint NOT NULL DEFAULT 0,
    expired_at bigint NOT NULL DEFAULT 0
);
CREATE INDEX replays_code ON replays (code, played_at DESC);
CREATE INDEX replays_live ON replays (played_at) WHERE expired_at = 0;
