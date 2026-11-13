package com.handsoffretail.gilbarco.stores;

import java.time.Instant;
import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.handsoffretail.auth.client.HorPrincipal;

@RestController
@RequestMapping("/api/ingestion/gilbarco/stores")
class StoreController {

    private final MasterStores stores;
    private final StoreSyncService sync;

    StoreController(MasterStores stores, StoreSyncService sync) {
        this.stores = stores;
        this.sync = sync;
    }

    /** The Gilbarco stores known to gilbarco_db, filtered to the caller's stores. */
    @GetMapping
    List<StoreResponse> list(@AuthenticationPrincipal Jwt jwt) {
        HorPrincipal caller = HorPrincipal.from(jwt);
        return stores.all().stream()
                .filter(s -> caller.allStores() || caller.storeIds().contains(s.storeId()))
                .map(StoreResponse::from).toList();
    }

    /** Copies the GILBARCO stores of auth_db into master.stores. */
    @PostMapping("/sync")
    StoreSyncService.Result sync(@AuthenticationPrincipal Jwt jwt) {
        return sync.sync(HorPrincipal.from(jwt).id());
    }

    record StoreResponse(long storeId, String storeCode, String storeName, String s3Folder, String timezone,
            String status, Instant syncedAt) {

        static StoreResponse from(MasterStore s) {
            return new StoreResponse(s.storeId(), s.storeCode(), s.storeName(), s.s3Folder(), s.timezone(),
                    s.active() ? "ACTIVE" : "INACTIVE", s.syncedAt());
        }
    }
}
