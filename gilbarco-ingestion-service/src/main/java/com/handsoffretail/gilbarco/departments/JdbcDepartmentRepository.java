package com.handsoffretail.gilbarco.departments;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDepartmentRepository implements DepartmentRepository {

    // lastSold: the newest loaded day with a line in the department (fuel lines carry no department).
    private static final String SELECT = """
            SELECT d.dept_number, d.dept_name, d.pos_name, d.sales_class, d.admin_set_by, d.admin_set_at, d.updated_at,
                   s.last_sold
            FROM master.departments d
            LEFT JOIN (SELECT l.dept_number, max(l.business_date) AS last_sold
                       FROM pjr.event_line l
                       WHERE l.store_id = :store AND l.dept_number IS NOT NULL
                       GROUP BY l.dept_number) s USING (dept_number)
            WHERE d.store_id = :store
            """;

    private final JdbcClient jdbc;

    JdbcDepartmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Department> list(long storeId) {
        return jdbc.sql(SELECT + " ORDER BY d.dept_number")
                .param("store", storeId)
                .query((rs, n) -> department(rs))
                .list();
    }

    @Override
    public Optional<Department> find(long storeId, int deptNumber) {
        return jdbc.sql(SELECT + " AND d.dept_number = :dept")
                .param("store", storeId).param("dept", deptNumber)
                .query((rs, n) -> department(rs))
                .optional();
    }

    @Override
    public Optional<Department> lock(long storeId, int deptNumber) {
        // FOR UPDATE cannot sit on the aggregated SELECT, so lock the row first, then read it.
        jdbc.sql("SELECT 1 FROM master.departments WHERE store_id = :store AND dept_number = :dept FOR UPDATE")
                .param("store", storeId).param("dept", deptNumber).query().singleColumn();
        return find(storeId, deptNumber);
    }

    @Override
    public void setByAdmin(long storeId, int deptNumber, String name, String salesClass, long adminId) {
        jdbc.sql("""
                UPDATE master.departments
                SET dept_name = :name, sales_class = :cls, admin_set_by = :admin, admin_set_at = now(), updated_at = now()
                WHERE store_id = :store AND dept_number = :dept
                """)
                .param("name", name).param("cls", salesClass).param("admin", adminId)
                .param("store", storeId).param("dept", deptNumber)
                .update();
    }

    @Override
    public void resetToPos(long storeId, int deptNumber) {
        jdbc.sql("""
                UPDATE master.departments
                SET dept_name = pos_name, sales_class = master.sales_class_for(pos_name),
                    admin_set_by = NULL, admin_set_at = NULL, updated_at = now()
                WHERE store_id = :store AND dept_number = :dept
                """)
                .param("store", storeId).param("dept", deptNumber)
                .update();
    }

    @Override
    public void addChange(long storeId, int deptNumber, String action, Department before, Department after,
            Optional<String> note, long adminId) {
        jdbc.sql("""
                INSERT INTO master.department_changes (store_id, dept_number, action, old_name, old_sales_class, new_name,
                  new_sales_class, note, changed_by)
                VALUES (:store, :dept, :action, :oldName, :oldClass, :newName, :newClass, :note, :admin)
                """)
                .param("store", storeId).param("dept", deptNumber).param("action", action)
                .param("oldName", before.deptName().orElse(null)).param("oldClass", before.salesClass())
                .param("newName", after.deptName().orElse(null)).param("newClass", after.salesClass())
                .param("note", note.orElse(null)).param("admin", adminId)
                .update();
    }

    @Override
    public List<Department.Change> changes(long storeId, int deptNumber) {
        return jdbc.sql("""
                SELECT change_id, action, old_name, old_sales_class, new_name, new_sales_class, note, changed_by, changed_at
                FROM master.department_changes
                WHERE store_id = :store AND dept_number = :dept
                ORDER BY changed_at DESC, change_id DESC
                """)
                .param("store", storeId).param("dept", deptNumber)
                .query((rs, n) -> new Department.Change(rs.getLong("change_id"), rs.getString("action"),
                        Optional.ofNullable(rs.getString("old_name")), rs.getString("old_sales_class"),
                        Optional.ofNullable(rs.getString("new_name")), rs.getString("new_sales_class"),
                        Optional.ofNullable(rs.getString("note")), rs.getLong("changed_by"),
                        rs.getObject("changed_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    // Lines not cancelled, over every loaded day (ix_pjr_line_dept). One item = code + description + line type.
    private static final String ITEM_LINES = """
            FROM pjr.event_line l
            WHERE l.store_id = :store AND l.dept_number = :dept AND l.status = 'normal'
            """;

    @Override
    public Department.Items items(long storeId, int deptNumber, int limit) {
        List<Department.Item> items = jdbc.sql("""
                SELECT l.pos_code, l.description, l.line_type, coalesce(sum(l.quantity), 0) AS units,
                       sum(l.sales_amount) AS amount, count(*) AS lines, max(l.business_date) AS last_sold
                """ + ITEM_LINES + """
                GROUP BY l.pos_code, l.description, l.line_type
                ORDER BY count(*) DESC, sum(l.sales_amount) DESC, l.description
                LIMIT :limit
                """)
                .param("store", storeId).param("dept", deptNumber).param("limit", limit)
                .query((rs, n) -> new Department.Item(Optional.ofNullable(rs.getString("pos_code")),
                        Optional.ofNullable(rs.getString("description")), rs.getString("line_type"),
                        rs.getBigDecimal("units"), rs.getBigDecimal("amount"), rs.getLong("lines"),
                        rs.getObject("last_sold", LocalDate.class)))
                .list();
        return jdbc.sql("""
                SELECT count(DISTINCT (l.pos_code, l.description, l.line_type)) AS items, count(*) AS lines,
                       coalesce(sum(l.sales_amount), 0) AS amount
                """ + ITEM_LINES)
                .param("store", storeId).param("dept", deptNumber)
                .query((rs, n) -> new Department.Items(items, rs.getLong("items"), rs.getLong("lines"),
                        rs.getBigDecimal("amount")))
                .single();
    }

    private static Department department(ResultSet rs) throws SQLException {
        long setBy = rs.getLong("admin_set_by");
        Optional<Long> adminSetBy = rs.wasNull() ? Optional.empty() : Optional.of(setBy);
        return new Department(rs.getInt("dept_number"), Optional.ofNullable(rs.getString("dept_name")),
                Optional.ofNullable(rs.getString("pos_name")), rs.getString("sales_class"), adminSetBy,
                Optional.ofNullable(rs.getObject("admin_set_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant),
                Optional.ofNullable(rs.getObject("last_sold", LocalDate.class)),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
