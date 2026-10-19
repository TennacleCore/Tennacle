-- the index over replay bytes kept elsewhere (a bucket, or the replay folder); expired rows stay, bytes go
CREATE TABLE replays (
    id         text   PRIMARY KEY,
    code       text   NOT NULL,
    mode       text   NOT NULL,
    map        text   NOT NULL,
    played_at  bigint NOT NULL,
    ticks      bigint NOT NULL,
    size       bigint NOT NULL,
    blob       text   NOT NULL,
    expired_at bigint NOT NULL DEFAULT 0
);
CREATE INDEX replays_code ON replays (code, played_at DESC);
CREATE INDEX replays_live ON replays (played_at) WHERE expired_at = 0;

-- what keeps a replay past the server's keep. A player's bookmark keeps it watchable for that player; a staff
-- hold (a report) keeps the bytes for staff only. holder is the nil uuid on a staff hold
CREATE TABLE replay_holds (
    replay_id text   NOT NULL REFERENCES replays (id) ON DELETE CASCADE,
    kind      text   NOT NULL,
    holder    uuid   NOT NULL,
    until     bigint NOT NULL,
    at        bigint NOT NULL,
    PRIMARY KEY (replay_id, kind, holder)
);
CREATE INDEX replay_holds_holder ON replay_holds (holder, kind);
