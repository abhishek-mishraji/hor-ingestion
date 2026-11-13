package com.handsoffretail.verifone;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A minimal auth_db {@code core.stores} inside verifone_db_test (the test profile points the auth pool there),
 * holding only the columns the sync reads. Ids are fixed so tests can name them.
 * The sync copies the VERIFONE rows into master.stores (IngestionIntegrationTest#syncStores).
 */
public final class TestStores {

    public static final long ADMIN_ID = 1;
    public static final long TIKI = 1;
    /** GILBARCO in auth_db: never copied, so 404 here. */
    public static final long GILBARCO_STORE = 2;
    public static final long INACTIVE_STORE = 3;

    private TestStores() {
    }

    public static void reset(JdbcTemplate jdbc) {
        jdbc.execute("DROP SCHEMA IF EXISTS core CASCADE");
        jdbc.execute("CREATE SCHEMA core");
        jdbc.execute("""
                CREATE TABLE core.stores (
                    store_id   bigint PRIMARY KEY,
                    store_code text   NOT NULL UNIQUE,
                    store_name text   NOT NULL,
                    pos_vendor text   NOT NULL,
                    s3_folder  text   NOT NULL UNIQUE,
                    timezone   text   NOT NULL,
                    status     text   NOT NULL)
                """);
        insert(jdbc, TIKI, "8730_IN-47872", "Tiki Hut", "VERIFONE", "Tiki_8730_IN-47872/Tiki_8730_IN-47872/", "ACTIVE");
        insert(jdbc, GILBARCO_STORE, "800_NY-13208", "Wolf", "GILBARCO", "wolf_800_NY-13208/800_NY-13208/", "ACTIVE");
        insert(jdbc, INACTIVE_STORE, "CLOSED_1", "Closed Store", "VERIFONE", "CLOSED_1/", "INACTIVE");
    }

    public static void insert(JdbcTemplate jdbc, long id, String code, String name, String vendor, String folder,
            String status) {
        jdbc.update("INSERT INTO core.stores VALUES (?, ?, ?, ?, ?, 'America/Indiana/Indianapolis', ?)",
                id, code, name, vendor, folder, status);
    }
}
