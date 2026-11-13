package com.handsoffretail.gilbarco.stores;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.handsoffretail.gilbarco.IngestionIntegrationTest;
import com.handsoffretail.gilbarco.TestStores;

/** The sync rules (GILBARCO_SERVICE_PLAN.md D5), on store ids 10/11 so other tests keep their stores. */
class StoreSyncTest extends IngestionIntegrationTest {

    @AfterEach
    void removeTestStores() {
        jdbc.update("DELETE FROM core.stores WHERE store_id IN (10, 11)");
    }

    @Test
    void insertsUpdatesDeactivatesAndReportsConflicts() {
        TestStores.insert(jdbc, 10, "SYNC_10", "Sync Ten", "GILBARCO", "SYNC_10/", "ACTIVE");
        StoreSyncService.Result first = storeSync.sync(TestStores.ADMIN_ID);
        assertThat(first.inserted()).isEqualTo(1);
        assertThat(status(10)).isEqualTo("ACTIVE");

        assertThat(storeSync.sync(TestStores.ADMIN_ID).updated()).isZero();

        jdbc.update("UPDATE core.stores SET store_name = 'Sync Ten B' WHERE store_id = 10");
        assertThat(storeSync.sync(TestStores.ADMIN_ID).updated()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT store_name FROM master.stores WHERE store_id = 10", String.class))
                .isEqualTo("Sync Ten B");

        jdbc.update("UPDATE core.stores SET store_code = 'SYNC_10X', s3_folder = 'SYNC_10X/' WHERE store_id = 10");
        StoreSyncService.Result mismatch = storeSync.sync(TestStores.ADMIN_ID);
        assertThat(mismatch.conflicts()).singleElement().satisfies(c -> assertThat(c.storeId()).isEqualTo(10));
        assertThat(jdbc.queryForObject("SELECT store_code FROM master.stores WHERE store_id = 10", String.class))
                .isEqualTo("SYNC_10");

        TestStores.insert(jdbc, 11, "SYNC_10", "Steals a code", "GILBARCO", "SYNC_11/", "ACTIVE");
        assertThat(storeSync.sync(TestStores.ADMIN_ID).conflicts()).extracting(StoreSyncService.Conflict::storeId)
                .contains(10L, 11L);

        jdbc.update("DELETE FROM core.stores WHERE store_id IN (10, 11)");
        assertThat(storeSync.sync(TestStores.ADMIN_ID).deactivated()).isEqualTo(1);
        assertThat(status(10)).isEqualTo("INACTIVE");
    }

    @Test
    void onlyGilbarcoStoresAreCopied() {
        assertThat(count("SELECT count(*) FROM master.stores WHERE store_id = ?", TestStores.VERIFONE_STORE)).isZero();
        assertThat(status(TestStores.WOLF)).isEqualTo("ACTIVE");
        assertThat(status(TestStores.INACTIVE_STORE)).isEqualTo("INACTIVE");
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM master.stores WHERE store_id = ?", String.class, id);
    }
}
