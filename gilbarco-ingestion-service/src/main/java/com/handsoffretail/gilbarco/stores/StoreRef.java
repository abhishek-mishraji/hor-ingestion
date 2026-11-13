package com.handsoffretail.gilbarco.stores;

import java.time.ZoneId;
import java.util.Objects;

/** A store as this service sees it in master.stores. */
public record StoreRef(long storeId, String storeCode, String storeName, String posVendor, String s3Folder,
        ZoneId timezone, boolean active) {

    public static final String GILBARCO = "GILBARCO";

    public StoreRef {
        Objects.requireNonNull(storeCode, "storeCode");
        Objects.requireNonNull(s3Folder, "s3Folder");
        Objects.requireNonNull(posVendor, "posVendor");
    }

    public boolean isGilbarco() {
        return GILBARCO.equals(posVendor);
    }
}
