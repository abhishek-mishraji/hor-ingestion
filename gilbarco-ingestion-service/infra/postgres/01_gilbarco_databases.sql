-- Development: run ONCE as superuser (postgres) on the PostgreSQL 17 that holds auth_db.
-- One login role for every HOR database in development: auth_svc (created by
-- hor-auth/infra/postgres/01_auth_database.sql). Flyway in gilbarco-ingestion-service creates
-- every schema and table; this script only creates the empty databases.
--   psql -U postgres -f infra/postgres/01_gilbarco_databases.sql
CREATE DATABASE gilbarco_db OWNER auth_svc ENCODING 'UTF8' TEMPLATE template0;
REVOKE ALL ON DATABASE gilbarco_db FROM PUBLIC;
ALTER DATABASE gilbarco_db SET timezone TO 'UTC';

CREATE DATABASE gilbarco_db_test OWNER auth_svc ENCODING 'UTF8' TEMPLATE template0;
REVOKE ALL ON DATABASE gilbarco_db_test FROM PUBLIC;
ALTER DATABASE gilbarco_db_test SET timezone TO 'UTC';
