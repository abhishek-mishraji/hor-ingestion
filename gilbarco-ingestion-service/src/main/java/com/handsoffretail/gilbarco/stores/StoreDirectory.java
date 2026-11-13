package com.handsoffretail.gilbarco.stores;

import java.util.Optional;

import com.handsoffretail.auth.client.HorPrincipal;
import com.handsoffretail.auth.client.StoreAccess;
import com.handsoffretail.gilbarco.common.GilbarcoErrorCode;

/** The Gilbarco stores this service knows: the master.stores replica (filled by the store sync). */
public interface StoreDirectory {

    Optional<StoreRef> find(long storeId);

    /**
     * The store, if it exists and the caller may see it; otherwise 404 (never 403, so ids cannot be probed).
     */
    default StoreRef require(HorPrincipal caller, long storeId) {
        StoreAccess.requireStore(caller, storeId);
        return find(storeId).orElseThrow(() -> GilbarcoErrorCode.notFound("store", storeId));
    }
}
