package com.handsoffretail.verifone.days;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.handsoffretail.auth.client.HorPrincipal;
import com.handsoffretail.verifone.stores.StoreDirectory;

@RestController
@RequestMapping("/api/ingestion/verifone/stores/{storeId}")
class DayController {

    private static final int FIRST_YEAR = 2000;
    private static final int LAST_YEAR = 2100;

    private final DayService days;
    private final StoreDirectory stores;

    DayController(DayService days, StoreDirectory stores) {
        this.days = days;
        this.stores = stores;
    }

    @GetMapping("/available-days")
    List<LocalDate> available(@AuthenticationPrincipal Jwt jwt, @PathVariable long storeId,
            @RequestParam @Min(FIRST_YEAR) @Max(LAST_YEAR) int year, @RequestParam @Min(1) @Max(12) int month) {
        stores.require(HorPrincipal.from(jwt), storeId);
        return days.availableDays(storeId, YearMonth.of(year, month));
    }

    @GetMapping("/loaded-days")
    List<DayQueries.LoadedDay> loaded(@AuthenticationPrincipal Jwt jwt, @PathVariable long storeId,
            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        stores.require(HorPrincipal.from(jwt), storeId);
        return days.loadedDays(storeId, from, to);
    }
}
