package com.handsoffretail.verifone.stores;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcMasterStores implements MasterStores, StoreDirectory {

    private static final String SELECT = """
            SELECT store_id, store_code, store_name, s3_folder, timezone, status, synced_at FROM master.stores
            """;

    private final JdbcClient jdbc;

    JdbcMasterStores(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoreRef> find(long storeId) {
        return jdbc.sql(SELECT + "WHERE store_id = :id").param("id", storeId).query(JdbcMasterStores::row).optional()
                .map(s -> new StoreRef(s.storeId(), s.storeCode(), s.storeName(), StoreRef.VERIFONE, s.s3Folder(),
                        ZoneId.of(s.timezone()), s.active()));
    }

    @Override
    public List<MasterStore> all() {
        return jdbc.sql(SELECT + "ORDER BY store_code").query(JdbcMasterStores::row).list();
    }

    @Override
    public void insert(AuthStore s, Instant now) {
        jdbc.sql("""
                INSERT INTO master.stores (store_id, store_code, store_name, s3_folder, timezone, status, synced_at)
                VALUES (:id, :code, :name, :folder, :tz, :status, :now)
                """)
                .param("id", s.storeId()).param("code", s.storeCode()).param("name", s.storeName())
                .param("folder", s.s3Folder()).param("tz", s.timezone()).param("status", status(s.active()))
                .param("now", utc(now))
                .update();
    }

    @Override
    public void update(AuthStore s, Instant now) {
        jdbc.sql("""
                UPDATE master.stores SET store_name = :name, s3_folder = :folder, timezone = :tz, status = :status,
                       synced_at = :now
                WHERE store_id = :id
                """)
                .param("id", s.storeId()).param("name", s.storeName()).param("folder", s.s3Folder())
                .param("tz", s.timezone()).param("status", status(s.active())).param("now", utc(now))
                .update();
    }

    @Override
    public void touch(Collection<Long> storeIds, Instant now) {
        if (storeIds.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE master.stores SET synced_at = :now WHERE store_id IN (:ids)")
                .param("now", utc(now)).param("ids", List.copyOf(storeIds))
                .update();
    }

    @Override
    public void deactivate(long storeId, Instant now) {
        jdbc.sql("UPDATE master.stores SET status = 'INACTIVE', synced_at = :now WHERE store_id = :id")
                .param("id", storeId).param("now", utc(now))
                .update();
    }

    private static MasterStore row(ResultSet rs, int row) throws SQLException {
        return new MasterStore(rs.getLong("store_id"), rs.getString("store_code"), rs.getString("store_name"),
                rs.getString("s3_folder"), rs.getString("timezone"), "ACTIVE".equals(rs.getString("status")),
                rs.getObject("synced_at", OffsetDateTime.class).toInstant());
    }

    private static String status(boolean active) {
        return active ? "ACTIVE" : "INACTIVE";
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
