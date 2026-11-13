package com.handsoffretail.verifone.days;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.handsoffretail.verifone.common.VerifoneErrorCode;
import com.handsoffretail.verifone.source.ZipSource;
import com.handsoffretail.verifone.stores.StoreDirectory;
import com.handsoffretail.verifone.stores.StoreRef;

/** Use cases: the days of a month that have a CPJ zip in S3, and the days already loaded. */
@Service
public class DayService {

    /** The loaded-days calendar covers at most this many days per request. */
    static final int MAX_RANGE_DAYS = 400;

    private final StoreDirectory stores;
    private final ZipSource source;
    private final DayQueries days;

    public DayService(StoreDirectory stores, ZipSource source, DayQueries days) {
        this.stores = stores;
        this.source = source;
        this.days = days;
    }

    public List<LocalDate> availableDays(long storeId, YearMonth month) {
        StoreRef store = stores.find(storeId).orElseThrow(() -> VerifoneErrorCode.notFound("store", storeId));
        return source.availableCpjDays(store.s3Folder(), month);
    }

    @Transactional(readOnly = true)
    public List<DayQueries.LoadedDay> loadedDays(long storeId, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw VerifoneErrorCode.BAD_RANGE.exception("from must be before to, at most " + MAX_RANGE_DAYS + " days apart");
        }
        return days.loadedDays(storeId, from, to);
    }
}
