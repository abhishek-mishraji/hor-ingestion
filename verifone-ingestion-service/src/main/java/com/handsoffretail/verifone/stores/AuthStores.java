package com.handsoffretail.verifone.stores;

import java.util.List;

/** Read side of auth_db core.stores (the master of store data). */
public interface AuthStores {

    /** Every store whose pos_vendor is VERIFONE, active or not, ordered by id. */
    List<AuthStore> verifoneStores();
}
