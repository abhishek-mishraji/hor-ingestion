package com.handsoffretail.verifone.stores;

import java.time.ZoneId;
import java.util.Objects;

/** A store as this service sees it in platform_db (the columns verifone_ingest_svc may read). */
public record StoreRef(long storeId, String storeCode, String storeName, String posVendor, String s3Folder,
        ZoneId timezone, boolean active) {

    public static final String VERIFONE = "VERIFONE";

    public StoreRef {
        Objects.requireNonNull(storeCode, "storeCode");
        Objects.requireNonNull(s3Folder, "s3Folder");
        Objects.requireNonNull(posVendor, "posVendor");
    }

    public boolean isVerifone() {
        return VERIFONE.equals(posVendor);
    }
}
