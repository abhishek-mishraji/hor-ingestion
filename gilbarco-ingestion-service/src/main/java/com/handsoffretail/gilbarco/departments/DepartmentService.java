package com.handsoffretail.gilbarco.departments;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.handsoffretail.auth.client.CommonErrorCode;
import com.handsoffretail.auth.client.DomainException;
import com.handsoffretail.gilbarco.common.GilbarcoErrorCode;

/**
 * Use cases: list a store's departments; a platform admin sets a department's name and sales class (it then stays,
 * whatever the POS files say) or returns it to the POS name. Every change is recorded in master.department_changes.
 * The report views read master.departments, so a change shows on every report at once, past days included.
 */
@Service
public class DepartmentService {

    static final String SET = "SET";
    static final String RESET = "RESET";
    static final int DEFAULT_ITEMS = 25;
    static final int MAX_ITEMS = 100;

    private final DepartmentRepository departments;

    public DepartmentService(DepartmentRepository departments) {
        this.departments = departments;
    }

    @Transactional(readOnly = true)
    public List<Department> list(long storeId) {
        return departments.list(storeId);
    }

    /** Sets name and class; a request that changes nothing on an admin-managed department records nothing. */
    @Transactional
    public Department set(long storeId, int deptNumber, String name, String salesClass, Optional<String> note, long adminId) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > Department.MAX_NAME) {
            throw GilbarcoErrorCode.BAD_DEPARTMENT.exception("name must be 1 to " + Department.MAX_NAME + " characters");
        }
        if (salesClass == null || !Department.SETTABLE_CLASSES.contains(salesClass)) {
            throw GilbarcoErrorCode.BAD_DEPARTMENT.exception("salesClass must be merchandise, lottery_sale or lottery_payout");
        }
        Optional<String> cleanNote = note.map(String::trim).filter(n -> !n.isEmpty());
        if (cleanNote.filter(n -> n.length() > Department.MAX_NOTE).isPresent()) {
            throw GilbarcoErrorCode.BAD_DEPARTMENT.exception("note must be at most " + Department.MAX_NOTE + " characters");
        }

        Department before = require(storeId, deptNumber);
        if (before.adminManaged() && before.deptName().equals(Optional.of(cleanName))
                && before.salesClass().equals(salesClass)) {
            return before;
        }
        departments.setByAdmin(storeId, deptNumber, cleanName, salesClass, adminId);
        Department after = require(storeId, deptNumber);
        departments.addChange(storeId, deptNumber, SET, before, after, cleanNote, adminId);
        return after;
    }

    /** Back to the POS name and the class it gives; nothing to do if the admin never set this department. */
    @Transactional
    public Department reset(long storeId, int deptNumber, long adminId) {
        Department before = require(storeId, deptNumber);
        if (!before.adminManaged()) {
            return before;
        }
        departments.resetToPos(storeId, deptNumber);
        Department after = require(storeId, deptNumber);
        departments.addChange(storeId, deptNumber, RESET, before, after, Optional.empty(), adminId);
        return after;
    }

    @Transactional(readOnly = true)
    public List<Department.Change> changes(long storeId, int deptNumber) {
        departments.find(storeId, deptNumber).orElseThrow(() -> notFound(deptNumber));
        return departments.changes(storeId, deptNumber);
    }

    /** What was sold in the department: the {@code limit} (1..100) items with the most lines, and totals. */
    @Transactional(readOnly = true)
    public Department.Items items(long storeId, int deptNumber, int limit) {
        if (limit < 1 || limit > MAX_ITEMS) {
            throw GilbarcoErrorCode.BAD_LIMIT.exception("limit must be between 1 and " + MAX_ITEMS);
        }
        departments.find(storeId, deptNumber).orElseThrow(() -> notFound(deptNumber));
        return departments.items(storeId, deptNumber, limit);
    }

    /** The department, locked so two admins editing it at once apply one after the other. */
    private Department require(long storeId, int deptNumber) {
        return departments.lock(storeId, deptNumber).orElseThrow(() -> notFound(deptNumber));
    }

    private static DomainException notFound(int deptNumber) {
        return new DomainException(CommonErrorCode.NOT_FOUND, "department " + deptNumber + " not found");
    }
}
