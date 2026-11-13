package com.handsoffretail.verifone.departments;

import java.time.LocalDate;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.handsoffretail.auth.client.HorPrincipal;
import com.handsoffretail.verifone.stores.StoreDirectory;

@RestController
@RequestMapping("/api/ingestion/verifone/stores/{storeId}/departments")
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

    record NameResponse(String name, String deptType, LocalDate firstSeen, LocalDate lastSeen) {
    }

    record DepartmentResponse(int deptNumber, String currentName, List<NameResponse> names,
            Department.LotteryClass lotteryClass) {

        static DepartmentResponse from(Department d) {
            return new DepartmentResponse(d.deptNumber(), d.currentName(), d.names().stream()
                    .map(n -> new NameResponse(n.name(), n.deptType(), n.firstSeen().orElse(null), n.lastSeen().orElse(null)))
                    .toList(), d.lotteryClass().orElse(null));
        }
    }
}
