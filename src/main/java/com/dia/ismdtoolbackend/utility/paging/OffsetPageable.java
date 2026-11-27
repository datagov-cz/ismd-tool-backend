package com.dia.ismdtoolbackend.utility.paging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A {@link Pageable} addressed by offset rather than page number, so an offset that is not a multiple of the
 * limit is honoured. Unsorted: the order belongs to the query, so it is stable and explicit there.
 */
public record OffsetPageable(int limit, long offset) implements Pageable {

    @Override
    public int getPageNumber() {
        return (int) (offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return Sort.unsorted();
    }

    @Override
    public Pageable next() {
        return new OffsetPageable(limit, offset + limit);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetPageable(limit, Math.max(0, offset - limit)) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetPageable(limit, 0);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPageable(limit, (long) pageNumber * limit);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
