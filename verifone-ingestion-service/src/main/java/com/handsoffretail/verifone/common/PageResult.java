package com.handsoffretail.verifone.common;

import java.util.List;
import java.util.function.Function;

/** One page of a list, with the total number of rows that match. */
public record PageResult<T>(List<T> items, long total, int limit, long offset) {

    public PageResult {
        items = List.copyOf(items);
    }

    public static <T> PageResult<T> of(List<T> items, long total, Paging paging) {
        return new PageResult<>(items, total, paging.limit(), paging.offset());
    }

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(items.stream().map(mapper).toList(), total, limit, offset);
    }
}
