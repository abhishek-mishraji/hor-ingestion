package com.handsoffretail.verifone.stores;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Write side of master.stores; only the store sync uses it. */
public interface MasterStores {

    List<MasterStore> all();

    void insert(AuthStore store, Instant now);

    /** Name, folder, timezone and status from auth_db; the code never changes here. */
    void update(AuthStore store, Instant now);

    /** Marks the rows as seen by this sync (no other change). */
    void touch(Collection<Long> storeIds, Instant now);

    void deactivate(long storeId, Instant now);
}
