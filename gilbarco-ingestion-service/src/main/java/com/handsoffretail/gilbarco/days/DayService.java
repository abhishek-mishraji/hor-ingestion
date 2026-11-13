package com.handsoffretail.gilbarco.days;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.handsoffretail.gilbarco.common.GilbarcoErrorCode;
import com.handsoffretail.gilbarco.filetypes.FileTypeProcessor;
import com.handsoffretail.gilbarco.filetypes.FileTypeRegistry;
import com.handsoffretail.gilbarco.filetypes.LoadedDays;
import com.handsoffretail.gilbarco.source.ZipSource;
import com.handsoffretail.gilbarco.stores.StoreDirectory;
import com.handsoffretail.gilbarco.stores.StoreRef;

/** Use cases: the days of a month that have a zip of a file type in S3, and the days of that type already loaded. */
@Service
public class DayService {

    /** The loaded-days calendar covers at most this many days per request. */
    static final int MAX_RANGE_DAYS = 400;

    private final StoreDirectory stores;
    private final ZipSource source;
    private final FileTypeRegistry fileTypes;

    public DayService(StoreDirectory stores, ZipSource source, FileTypeRegistry fileTypes) {
        this.stores = stores;
        this.source = source;
        this.fileTypes = fileTypes;
    }

    public List<LocalDate> availableDays(long storeId, YearMonth month, String fileType) {
        FileTypeProcessor processor = fileTypes.require(fileType);
        StoreRef store = stores.find(storeId).orElseThrow(() -> GilbarcoErrorCode.notFound("store", storeId));
        return source.availableDays(store.s3Folder(), month, processor.fileType());
    }

    @Transactional(readOnly = true)
    public List<LoadedDays.LoadedDay> loadedDays(long storeId, LocalDate from, LocalDate to, String fileType) {
        FileTypeProcessor processor = fileTypes.require(fileType);
        if (to.isBefore(from) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw GilbarcoErrorCode.BAD_RANGE.exception("from must be before to, at most " + MAX_RANGE_DAYS + " days apart");
        }
        return processor.days().loadedDays(storeId, from, to);
    }
}
