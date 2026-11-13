package com.handsoffretail.gilbarco.filetypes.pjr;

import java.util.List;

import org.springframework.stereotype.Component;

import com.handsoffretail.gilbarco.filetypes.FileLoadContext;
import com.handsoffretail.gilbarco.filetypes.FileTypeProcessor;
import com.handsoffretail.gilbarco.filetypes.LoadOutcome;
import com.handsoffretail.gilbarco.filetypes.LoadedDays;
import com.handsoffretail.gilbarco.filetypes.pjr.load.JdbcPjrRepository;
import com.handsoffretail.gilbarco.filetypes.pjr.load.PjrDayLoader;
import com.handsoffretail.gilbarco.filetypes.pjr.verify.PjrLoadCheck;

/** Gilbarco Passport PJR (POS journal, one event per file) as a {@link FileTypeProcessor}. */
@Component
class PjrFileTypeProcessor implements FileTypeProcessor {

    static final String PJR = "PJR";

    private final PjrDayLoader loader;
    private final JdbcPjrRepository days;
    private final List<PjrLoadCheck> checks;

    PjrFileTypeProcessor(PjrDayLoader loader, JdbcPjrRepository days, List<PjrLoadCheck> checks) {
        this.loader = loader;
        this.days = days;
        this.checks = List.copyOf(checks);
    }

    @Override
    public String fileType() {
        return PJR;
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
    public List<PjrLoadCheck> supportedChecks() {
        return checks;
    }
}
