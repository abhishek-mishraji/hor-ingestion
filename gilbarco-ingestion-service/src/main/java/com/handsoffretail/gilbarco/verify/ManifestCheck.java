package com.handsoffretail.gilbarco.verify;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Hard: the zip's manifest matched the key and every listed file's size and SHA-256 matched (verified before loading). */
@Component
@Order(10)
class ManifestCheck implements LoadCheck.CommonLoadCheck {

    @Override
    public List<CheckResult> run(CheckContext c) {
        int listed = c.zip().manifest().fileCount();
        return List.of(CheckResult.counted("manifest_and_sha256", listed, c.zip().files().size(), true,
                "type, business date, version, store, paths and sha256 of " + listed + " file(s) verified"));
    }
}
