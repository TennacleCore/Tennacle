# Storage and the APIs: draft

Where Tennacle keeps each kind of data, and the two APIs that will read it: a private one for our own services and
a public one for players' bots. Drafted 2026-10-19 with Postgres live for accounts, the directory, matches and
punishments. Nothing below the "Today" section is built. The open questions at the end are yours to answer.

## Today

| Data | Store | Shape | Queryable |
|---|---|---|---|
| Account data: grants, kits, hotbars, friends, blocks, prefs | Postgres `player_data` | one opaque NBT blob per (player, key) | no, by design |
| Career stats (`bedwars:career-*`, `boxing:career-*`, `tab:stats`) | Postgres `player_data` | opaque blobs, same as settings | **no**, a leaderboard can't sort them |
| Player directory | Postgres `players` | id, last name, last seen | yes |
| Matches | Postgres `matches`, `match_players` | id, code, mode, platform, outcome, winner, times, seats with UUIDs | yes, but no map |
| Punishments | Postgres `punishments` | a row each, lifted ones kept | yes |
| Replays | files: `replays/<mode>/<gameId>.shard` | gzipped NBT, whole game in one file | no index; found by scanning mode folders |
| Replay listing in the menu | `ReplayCatalog.SAMPLES` | 30 made-up games per player | n/a |
| Parties, queues, who is online, vanish, `/msg` reply targets | the server's memory | maps | gone on restart, invisible to other servers |

Replays today: 359 files, 129 MB, median 57 KB, largest 35 MB. A game spools to `<mode>/spool/<id>.spool`
every 1200 ticks, and at the end it is written as one `.shard` (temp file, fsync, atomic move). Playback reads the
whole file into memory and decodes it, so a store only needs "give me these bytes", with no random access. There is
no retention: nothing is ever deleted. A code resolves only for today and yesterday (`GameIds.derive`); a game id
never expires. The file keeps player names but no UUIDs, outcome or length. Those are in `matches`.

## Replays in the storage system

Replay bytes do not belong in Postgres. At this rate a busy network writes gigabytes a day. Bytes in Postgres bloat
backups and the WAL, and every read of a 35 MB row goes through the database's memory. The standard split:

- **Postgres `replays` table, the index:** game id (= `matches.id`), code, mode, map, played on, length in
  ticks, size in bytes, format version, where the bytes live, and what keeps them (a retention class).
- **A blob store for the bytes:** an interface with `put(id, bytes)`, `get(id)` and `delete(id)`. `FileReplays`
  now: today's directory layout, so nothing moves. `S3Replays` later: any S3-compatible bucket (MinIO self-hosted, or Cloudflare R2 or
  Backblaze B2 hosted). Every server and the API read the same bucket.
- **The spool stays on local disk.** It is the crash-safety for a game in progress. When the `.shard` is
  finished, it goes to the blob store and the index row is written; then the local file can go.

With that in place:

- **Code lookup by index:** `/replay <code>` becomes one indexed query on `(code, played_on)` instead of
  hashing two days and scanning folders. The two-day window becomes a policy on the row rather than a property of
  the hash, so it can be different for, say, a reported game.
- **A real catalog:** `ReplayCatalog` becomes a query joining `match_players`, `matches` and `replays`. The
  Replays tab shows a player's real games and `SAMPLES` goes away. `matches` gains `map`; the replay file already
  knows it.
- **Retention is a column:** for example, ordinary games for 30 days; ranked, bookmarked, reported or
  punished-player games for longer. A daily job deletes expired bytes and keeps the index row, so match history outlives the
  replay.
- **Moving old files:** existing `.shard` files get index rows from a one-time import. Their outcome and UUIDs
  come from the `matches` rows imported earlier; the files without a match row are listed by name only.

Format: the `.shard` (gzipped NBT) stays. zstd is already a dependency and would shrink files further, but that
is a format version bump for later, not part of this.

## Two APIs

Tennacle will have two APIs with different callers, so they get different contracts.

**Private API (ours):** game servers, the proxy, a staff web panel, our Discord bots.
- **Live events** (a player joined, a party changed, a ban landed, a game ended) go over Redis pub/sub between
  servers. These are the Redis seams from the storage split.
- **Requests** (look up a player's full record, lift a ban, move a party) go to an internal HTTP service, reachable
  only on the private network. Auth is a service token per caller.
- It can see everything: punishments, IPs once we store them, staff notes, raw account data.
- It changes when we change.

**Public API (players' bots):** read-mostly REST and JSON under `/v1/`.
- **Auth:** an API key per player, made in game (`/api new`), shown once, and stored only as a hash. A key
  belongs to an account and can be revoked or rotated.
- **Rate limits** per key: a token bucket in Redis, with the remaining count in response headers.
- **First endpoints:**
  - player by name or UUID: name, first and last seen, online status (which follows their privacy setting)
  - stats per mode
  - recent matches
  - one match with its seats
  - replay metadata for a game they could watch
  - leaderboards per mode and stat
  - guilds, when guilds exist
- **Privacy:** every field a player can hide in game is hidden here too, and nothing moderation-related is
  ever public.
- **Stable:** responses are their own documented types, never our internal records, so a refactor doesn't break
  anyone's bot. Breaking changes go to `/v2/`.

**Where they run:** the public API should be a separate process, not inside a game server. Bot traffic must never
cost a tick. It reads Postgres (a read replica once there is one) and Redis, and never writes game state. The game
servers write; the APIs read. Both reuse the same storage interfaces and Postgres stores (`core` and `postgres`),
so a query is written once.

## What this asks of storage now

The public API is what makes this urgent. **Stats are opaque blobs today.** A leaderboard needs to sort a million
players by Bed Wars final kills; it can't do that through `player_data`. So:

1. **Stats table:** `stats(player, mode, stat, value)`, indexed `(mode, stat, value desc)`. Career keys move
   out of the vault into it, and the code reaches it through a `Stats` interface with an add-to method, so two
   servers adding at once don't race.
2. **`matches.map`, plus the `replays` index and blob store** above.
3. **`api_keys`:** key hash, owner, created, last used, revoked.
4. **Privacy settings stay vault blobs:** the server reads them per player. The API needs them too, so it
   reads them through the same vault and decodes them with the same keys. This works because the keys live in
   `core` and `social`.
5. **Settings stay blobs.** Kits, hotbars and preferences have no reason to be queryable, and keeping them opaque
   keeps the schema small.

## Open questions

1. **Replay bytes:** an S3-compatible bucket (recommended) or Postgres? And which bucket in production: MinIO
   on our own box, or R2 or B2 hosted?
2. **Retention:** how long does an ordinary game's replay stay watchable, and what keeps one longer?
3. **Public replay downloads:** may a bot download the raw replay, or only see its metadata and a code to watch
   in game? The raw file holds every player's every move and chat line.
4. **API process:** a separate service (recommended) or inside the main server to begin with?
5. **Stats:** move career stats into a stats table now (recommended, before more stats pile up as blobs)?
6. **Which public endpoints first**, if the list above isn't the right first cut?
