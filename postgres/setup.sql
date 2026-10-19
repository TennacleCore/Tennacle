-- Once, as the postgres superuser:  psql -U postgres -f postgres/setup.sql
-- It asks for the tennacle role's new password; the same one goes in tennacle.properties as
-- tennacle.postgres.password. The server makes its own tables on first boot.

CREATE ROLE tennacle LOGIN;
\password tennacle
CREATE DATABASE tennacle OWNER tennacle;
