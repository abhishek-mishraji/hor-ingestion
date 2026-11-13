package com.handsoffretail.verifone.load;

import java.time.LocalDate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLoadedDays implements LoadedDays {

    private final JdbcClient jdbc;

    JdbcLoadedDays(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isLoaded(long storeId, LocalDate businessDate) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM cpj.cpj_files WHERE store_id = :s AND business_date = :d)")
                .param("s", storeId).param("d", businessDate).query(Boolean.class).single();
    }

    @Override
    public int delete(long storeId, LocalDate businessDate) {
        return jdbc.sql("DELETE FROM cpj.cpj_files WHERE store_id = :s AND business_date = :d")
                .param("s", storeId).param("d", businessDate).update();
    }
}
