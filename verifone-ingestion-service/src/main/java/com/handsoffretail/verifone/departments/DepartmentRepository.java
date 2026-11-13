package com.handsoffretail.verifone.departments;

import java.util.List;

/** master.departments with their name history (master.department_name_history). */
public interface DepartmentRepository {

    List<Department> list(long storeId);
}
