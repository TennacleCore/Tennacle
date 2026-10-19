-- a stat is named by its vault key id, bedwars:career-kills
CREATE TABLE stats (
    player uuid   NOT NULL,
    stat   text   NOT NULL,
    value  bigint NOT NULL,
    PRIMARY KEY (player, stat)
);
CREATE INDEX stats_rank ON stats (stat, value DESC);
