package com.handsoffretail.verifone.load;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Buffered rows per table, written with JDBC batch inserts. When the buffer is full every table is
 * flushed in foreign-key order, so a child row never reaches the database before its parent.
 */
final class Batches {

    /** Rows buffered across all tables before a flush (a typical day is ~10,000 rows). */
    static final int FLUSH_AT = 5_000;

    private final JdbcTemplate jdbc;
    private final Map<Table, List<Object[]>> rows = new LinkedHashMap<>();
    private final Map<Table, Integer> written = new LinkedHashMap<>();
    private int buffered;

    Batches(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        CpjTables.FLUSH_ORDER.forEach(t -> rows.put(t, new ArrayList<>()));
    }

    void add(Table table, Object... values) {
        if (values.length != table.columns().size()) {
            throw new IllegalArgumentException(table.name() + " expects " + table.columns().size() + " values");
        }
        rows.get(table).add(values);
        if (++buffered >= FLUSH_AT) {
            flush();
        }
    }

    void flush() {
        for (Map.Entry<Table, List<Object[]>> entry : rows.entrySet()) {
            List<Object[]> pending = entry.getValue();
            if (!pending.isEmpty()) {
                Table table = entry.getKey();
                jdbc.batchUpdate(table.insertSql(), pending, table.types());
                written.merge(table, pending.size(), Integer::sum);
                pending.clear();
            }
        }
        buffered = 0;
    }

    /** Rows written per table so far (for the fetch summary). */
    Map<String, Integer> written() {
        Map<String, Integer> byName = new LinkedHashMap<>();
        written.forEach((t, n) -> byName.put(t.name(), n));
        return byName;
    }
}
