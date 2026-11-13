package com.handsoffretail.gilbarco.departments;

import java.util.List;
import java.util.Optional;

/** master.departments and master.department_changes. */
public interface DepartmentRepository {

    List<Department> list(long storeId);

    Optional<Department> find(long storeId, int deptNumber);

    /** The department, row-locked until the transaction ends. */
    Optional<Department> lock(long storeId, int deptNumber);

    /** Sets the name and class and marks the department admin-managed. */
    void setByAdmin(long storeId, int deptNumber, String name, String salesClass, long adminId);

    /** Back to the POS: name = pos_name, class from that name, no longer admin-managed. */
    void resetToPos(long storeId, int deptNumber);

    void addChange(long storeId, int deptNumber, String action, Department before, Department after, Optional<String> note,
            long adminId);

    /** Newest first. */
    List<Department.Change> changes(long storeId, int deptNumber);

    /** The {@code limit} items with the most lines, and totals over all of them. */
    Department.Items items(long storeId, int deptNumber, int limit);
}
