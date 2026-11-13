package com.handsoffretail.gilbarco.departments;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.handsoffretail.auth.client.HorPrincipal;
import com.handsoffretail.gilbarco.stores.StoreDirectory;

/** Departments of a store. Writes are PLATFORM_ADMIN only, like the whole service (SecurityConfig). */
@RestController
@RequestMapping("/api/ingestion/gilbarco/stores/{storeId}/departments")
class DepartmentController {

    private final DepartmentService departments;
    private final StoreDirectory stores;

    DepartmentController(DepartmentService departments, StoreDirectory stores) {
        this.departments = departments;
        this.stores = stores;
    }

    @GetMapping
    List<DepartmentResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long storeId) {
        stores.require(HorPrincipal.from(jwt), storeId);
        return departments.list(storeId).stream().map(DepartmentResponse::from).toList();
    }

    /** Sets the name and sales class; they then stay whatever the POS files say. */
    @PutMapping("/{deptNumber}")
    DepartmentResponse set(@AuthenticationPrincipal Jwt jwt, @PathVariable long storeId, @PathVariable int deptNumber,
            @Valid @RequestBody SetDepartmentRequest request) {
        HorPrincipal caller = HorPrincipal.from(jwt);
        stores.require(caller, storeId);
        return DepartmentResponse.from(departments.set(storeId, deptNumber, request.name(), request.salesClass(),
                Optional.ofNullable(request.note()), caller.id()));
    }

    /** Returns the department to the POS name and the class that name gives. */
    @DeleteMapping("/{deptNumber}/override")
    DepartmentResponse reset(@AuthenticationPrincipal Jwt jwt, @PathVariable long storeId, @PathVariable int deptNumber) {
        HorPrincipal caller = HorPrincipal.from(jwt);
        stores.require(caller, storeId);
        return DepartmentResponse.from(departments.reset(storeId, deptNumber, caller.id()));
    }

    /** The admin changes of one department, newest first. */
    @GetMapping("/{deptNumber}/changes")
    List<ChangeResponse> changes(@AuthenticationPrincipal Jwt jwt, @PathVariable long storeId, @PathVariable int deptNumber) {
        stores.require(HorPrincipal.from(jwt), storeId);
        return departments.changes(storeId, deptNumber).stream().map(ChangeResponse::from).toList();
    }

    /** What was sold in the department (lines not cancelled, all loaded days): top items by lines, and totals. */
    @GetMapping("/{deptNumber}/items")
    ItemsResponse items(@AuthenticationPrincipal Jwt jwt, @PathVariable long storeId, @PathVariable int deptNumber,
            @RequestParam(required = false) Integer limit) {
        stores.require(HorPrincipal.from(jwt), storeId);
        return ItemsResponse.from(departments.items(storeId, deptNumber,
                limit == null ? DepartmentService.DEFAULT_ITEMS : limit));
    }

    record ItemsResponse(List<ItemResponse> items, long distinctItems, long lines, BigDecimal amount) {

        static ItemsResponse from(Department.Items i) {
            return new ItemsResponse(i.items().stream().map(ItemResponse::from).toList(), i.distinctItems(), i.lines(),
                    i.amount());
        }
    }

    record ItemResponse(String posCode, String description, String lineType, BigDecimal units, BigDecimal amount,
            long lines, LocalDate lastSold) {

        static ItemResponse from(Department.Item i) {
            return new ItemResponse(i.posCode().orElse(null), i.description().orElse(null), i.lineType(), i.units(),
                    i.amount(), i.lines(), i.lastSold());
        }
    }

    record SetDepartmentRequest(@NotNull String name, @NotNull String salesClass, String note) {
    }

    record DepartmentResponse(int deptNumber, String deptName, String posName, String salesClass, boolean adminManaged,
            Long adminSetBy, Instant adminSetAt, LocalDate lastSold, Instant updatedAt) {

        static DepartmentResponse from(Department d) {
            return new DepartmentResponse(d.deptNumber(), d.deptName().orElse(null), d.posName().orElse(null),
                    d.salesClass(), d.adminManaged(), d.adminSetBy().orElse(null), d.adminSetAt().orElse(null),
                    d.lastSold().orElse(null), d.updatedAt());
        }
    }

    record ChangeResponse(long changeId, String action, String oldName, String oldSalesClass, String newName,
            String newSalesClass, String note, long changedBy, Instant changedAt) {

        static ChangeResponse from(Department.Change c) {
            return new ChangeResponse(c.changeId(), c.action(), c.oldName().orElse(null), c.oldSalesClass(),
                    c.newName().orElse(null), c.newSalesClass(), c.note().orElse(null), c.changedBy(), c.changedAt());
        }
    }
}
