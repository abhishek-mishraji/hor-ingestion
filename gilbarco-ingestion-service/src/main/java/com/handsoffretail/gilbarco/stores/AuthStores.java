package com.handsoffretail.gilbarco.stores;

import java.util.List;

/** Read side of auth_db core.stores (the master of store data). */
public interface AuthStores {

    /** Every store whose pos_vendor is GILBARCO, active or not, ordered by id. */
    List<AuthStore> gilbarcoStores();
}
