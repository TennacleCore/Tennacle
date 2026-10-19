-- the map, and each side's team as the game named and colored it
ALTER TABLE matches ADD COLUMN map text NOT NULL DEFAULT '';

CREATE TABLE match_sides (
    match_id text NOT NULL REFERENCES matches (id) ON DELETE CASCADE,
    side     int  NOT NULL,
    team     text NOT NULL,
    name     text NOT NULL,
    color    text NOT NULL,
    PRIMARY KEY (match_id, side)
);
