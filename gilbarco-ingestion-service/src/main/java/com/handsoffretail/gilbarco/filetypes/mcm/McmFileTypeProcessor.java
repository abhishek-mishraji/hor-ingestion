package com.handsoffretail.gilbarco.filetypes.mcm;

import java.util.List;

import org.springframework.stereotype.Component;

import com.handsoffretail.gilbarco.filetypes.FileLoadContext;
import com.handsoffretail.gilbarco.filetypes.FileTypeProcessor;
import com.handsoffretail.gilbarco.filetypes.LoadOutcome;
import com.handsoffretail.gilbarco.filetypes.LoadedDays;
import com.handsoffretail.gilbarco.filetypes.mcm.load.JdbcMcmRepository;
import com.handsoffretail.gilbarco.filetypes.mcm.load.McmDayLoader;
import com.handsoffretail.gilbarco.filetypes.mcm.verify.McmLoadCheck;

/**
 * Gilbarco Passport MCM (Merchandise Code Movement, department totals of a close) as a {@link FileTypeProcessor}.
 * Stores the store-level file only: department names for master.departments, and the day's department totals
 * that prove a PJR load complete (report.rpt_mcm_day_check).
 */
@Component
class McmFileTypeProcessor implements FileTypeProcessor {

    static final String MCM = "MCM";

    private final McmDayLoader loader;
    private final JdbcMcmRepository days;
    private final List<McmLoadCheck> checks;

    McmFileTypeProcessor(McmDayLoader loader, JdbcMcmRepository days, List<McmLoadCheck> checks) {
        this.loader = loader;
        this.days = days;
        this.checks = List.copyOf(checks);
    }

    @Override
    public String fileType() {
        return MCM;
    }

    @Override
    public LoadedDays days() {
        return days;
    }

    @Override
    public LoadOutcome processDay(FileLoadContext context) {
        return loader.load(context);
    }

    @Override
    public List<McmLoadCheck> supportedChecks() {
        return checks;
    }
}
