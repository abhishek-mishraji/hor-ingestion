package com.handsoffretail.verifone.departments;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use case: list a store's departments with their name history and lottery class (fixed by name). */
@Service
public class DepartmentService {

    private final DepartmentRepository departments;

    public DepartmentService(DepartmentRepository departments) {
        this.departments = departments;
    }

    @Transactional(readOnly = true)
    public List<Department> list(long storeId) {
        return departments.list(storeId);
    }
}
