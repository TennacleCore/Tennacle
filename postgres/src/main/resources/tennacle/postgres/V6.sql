-- a match's numbers per player, and between a player and another player or a team. No reference to matches:
-- the two are written apart, and either may land first
CREATE TABLE match_stats (
    match_id text   NOT NULL,
    player   uuid   NOT NULL,
    stat     text   NOT NULL,
    value    bigint NOT NULL,
    PRIMARY KEY (match_id, player, stat)
);
CREATE INDEX match_stats_player ON match_stats (player, stat);

CREATE TABLE match_pairs (
    match_id text   NOT NULL,
    player   uuid   NOT NULL,
    other    text   NOT NULL,
    stat     text   NOT NULL,
    value    bigint NOT NULL,
    PRIMARY KEY (match_id, player, other, stat)
);

-- Bed Wars' career numbers take the catalog's names (docs/bedwars-stats.md)
UPDATE stats SET stat = 'bedwars:beds-broken' WHERE stat = 'bedwars:career-beds';
UPDATE stats SET stat = 'bedwars:' || substr(stat, length('bedwars:career-') + 1) WHERE stat LIKE 'bedwars:career-%';
INSERT INTO stats (player, stat, value)
SELECT player, 'bedwars:games', sum(value) FROM stats WHERE stat IN ('bedwars:wins', 'bedwars:losses') GROUP BY player
ON CONFLICT (player, stat) DO NOTHING;
