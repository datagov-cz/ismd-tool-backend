package com.dia.ismdtoolbackend.utility.paging;

import com.dia.ismdtoolbackend.controller.dto.PageDto;

import java.util.List;

/** A validated {@code limit}/{@code offset} pair; same defaults and cap as the NKD and NKOD lists. */
public record PageParams(int limit, int offset) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public PageParams {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Limit musí být v rozsahu 1–" + MAX_LIMIT + ".");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("Offset nesmí být záporný.");
        }
    }

    /** Cuts this page out of a fully ordered list. */
    public <T> PageDto<T> slice(List<T> ordered) {
        int from = Math.min(offset, ordered.size());
        int to = (int) Math.min((long) from + limit, ordered.size());
        return new PageDto<>(List.copyOf(ordered.subList(from, to)), ordered.size(), limit, offset);
    }

    public <T> PageDto<T> page(List<T> items, long totalCount) {
        return new PageDto<>(items, totalCount, limit, offset);
    }

    public OffsetPageable pageable() {
        return new OffsetPageable(limit, offset);
    }
}
