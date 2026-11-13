package com.handsoffretail.verifone.load;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Takes ids in blocks from a table's identity sequence, so parent and child rows can be linked in
 * memory and written in batches without a round trip per row (rows are then inserted with
 * OVERRIDING SYSTEM VALUE). Ids from a block are unique; gaps are harmless.
 */
final class IdAllocator {

    static final int BLOCK = 1_000;

    private final JdbcTemplate jdbc;
    private final Map<String, Deque<Long>> free = new HashMap<>();

    IdAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long next(String table, String idColumn) {
        Deque<Long> ids = free.computeIfAbsent(table + "." + idColumn, k -> new ArrayDeque<>());
        if (ids.isEmpty()) {
            ids.addAll(jdbc.queryForList("SELECT nextval(pg_get_serial_sequence(?, ?)) FROM generate_series(1, ?)",
                    Long.class, table, idColumn, BLOCK));
        }
        return ids.removeFirst();
    }
}
