package com.handsoffretail.verifone.departments;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDepartmentRepository implements DepartmentRepository {

    private final JdbcClient jdbc;

    JdbcDepartmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Department> list(long storeId) {
        Map<Integer, List<Department.Name>> names = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT dept_number, name, dept_type, first_seen, last_seen FROM master.department_name_history
                WHERE store_id = :store ORDER BY dept_number, last_seen DESC NULLS LAST, department_version_id DESC
                """)
                .param("store", storeId)
                .query(rs -> {
                    names.computeIfAbsent(rs.getInt("dept_number"), k -> new ArrayList<>()).add(new Department.Name(
                            rs.getString("name"), rs.getString("dept_type"),
                            Optional.ofNullable(rs.getObject("first_seen", LocalDate.class)),
                            Optional.ofNullable(rs.getObject("last_seen", LocalDate.class))));
                });
        return jdbc.sql("""
                SELECT dept_number, lottery_class FROM master.departments WHERE store_id = :store ORDER BY dept_number
                """)
                .param("store", storeId)
                .query((rs, n) -> {
                    int number = rs.getInt("dept_number");
                    List<Department.Name> history = names.getOrDefault(number, List.of());
                    return new Department(number, history.isEmpty() ? "" : history.get(0).name(), history,
                            Optional.ofNullable(rs.getString("lottery_class")).map(Department.LotteryClass::valueOf));
                })
                .list();
    }
}
