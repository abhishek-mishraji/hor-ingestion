package com.handsoffretail.verifone.stores;

import java.util.Objects;

/** A VERIFONE row of auth_db core.stores, as the sync reads it. */
public record AuthStore(long storeId, String storeCode, String storeName, String s3Folder, String timezone,
        boolean active) {

    public AuthStore {
        Objects.requireNonNull(storeCode, "storeCode");
        Objects.requireNonNull(storeName, "storeName");
        Objects.requireNonNull(s3Folder, "s3Folder");
        Objects.requireNonNull(timezone, "timezone");
    }
}
