package com.handsoffretail.gilbarco.common;

/** A {@code limit} / {@code offset} page request (limit 1..500, default 50). */
public record Paging(int limit, long offset) {

    public static final int MAX_LIMIT = 500;
    public static final int DEFAULT_LIMIT = 50;

    public Paging {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw GilbarcoErrorCode.BAD_LIMIT.exception("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw GilbarcoErrorCode.BAD_OFFSET.exception("offset must not be negative");
        }
    }

    /** From optional query parameters; missing values take the defaults. */
    public static Paging of(Integer limit, Long offset) {
        return new Paging(limit == null ? DEFAULT_LIMIT : limit, offset == null ? 0 : offset);
    }
}
