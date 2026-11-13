package com.handsoffretail.verifone.load;

import java.util.List;

/**
 * An insert target: table, columns and their SQL types (explicit types avoid one metadata round
 * trip per statement and let NULLs be typed). {@code overridingId} is set when the first column is
 * an identity id taken in advance from the sequence.
 */
record Table(String name, List<Column> columns, boolean overridingId) {

    Table {
        columns = List.copyOf(columns);
    }

    String insertSql() {
        String names = String.join(", ", columns.stream().map(Column::name).toList());
        String marks = String.join(", ", columns.stream().map(c -> "?").toList());
        return "INSERT INTO " + name + " (" + names + ")" + (overridingId ? " OVERRIDING SYSTEM VALUE" : "")
                + " VALUES (" + marks + ")";
    }

    int[] types() {
        return columns.stream().mapToInt(Column::sqlType).toArray();
    }

    record Column(String name, int sqlType) {
    }
}
