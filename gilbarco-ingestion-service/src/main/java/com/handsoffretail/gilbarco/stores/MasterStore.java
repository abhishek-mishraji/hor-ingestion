package com.handsoffretail.gilbarco.stores;

import java.time.Instant;
import java.util.Objects;

/** One master.stores row. {@code active} = status ACTIVE. */
public record MasterStore(long storeId, String storeCode, String storeName, String s3Folder, String timezone,
        boolean active, Instant syncedAt) {

    public MasterStore {
        Objects.requireNonNull(storeCode, "storeCode");
        Objects.requireNonNull(storeName, "storeName");
        Objects.requireNonNull(s3Folder, "s3Folder");
        Objects.requireNonNull(timezone, "timezone");
        Objects.requireNonNull(syncedAt, "syncedAt");
    }

    /** True when the replica already holds what auth_db says (the code is compared by the caller). */
    boolean sameAs(AuthStore source) {
        return storeName.equals(source.storeName()) && s3Folder.equals(source.s3Folder())
                && timezone.equals(source.timezone()) && active == source.active();
    }
}
