-- public API keys, hashed; a revoked one stays for the record
CREATE TABLE api_keys (
    key_hash   text   PRIMARY KEY,
    owner      uuid   NOT NULL,
    created_at bigint NOT NULL,
    revoked_at bigint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX api_keys_live ON api_keys (owner) WHERE revoked_at = 0;
