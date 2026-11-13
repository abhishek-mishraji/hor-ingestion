package com.handsoffretail.gilbarco.stores;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.handsoffretail.gilbarco.config.DataSourceConfig;

@Repository
class JdbcAuthStores implements AuthStores {

    private final JdbcClient auth;

    JdbcAuthStores(@Qualifier(DataSourceConfig.AUTH) JdbcClient auth) {
        this.auth = auth;
    }

    @Override
    public List<AuthStore> gilbarcoStores() {
        return auth.sql("""
                SELECT store_id, store_code, store_name, s3_folder, timezone, status
                FROM core.stores WHERE pos_vendor = 'GILBARCO' ORDER BY store_id
                """)
                .query((rs, row) -> new AuthStore(rs.getLong("store_id"), rs.getString("store_code"),
                        rs.getString("store_name"), rs.getString("s3_folder"), rs.getString("timezone"),
                        "ACTIVE".equals(rs.getString("status"))))
                .list();
    }
}
