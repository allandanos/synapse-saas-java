package dev.synapse.core.pagination;

import java.util.List;

/** Limits shared by every {@code ?limit=&offset=} list route. */
public final class Pagination {

    public static final int DEFAULT_PAGE_LIMIT = 50;
    public static final int MAX_PAGE_LIMIT = 100;
    public static final String TOTAL_COUNT_HEADER = "X-Total-Count";

    private Pagination() {}

    /** For small, already-loaded collections: the slice the caller asked for. */
    public static <T> List<T> sliceInMemory(List<T> items, int limit, int offset) {
        int from = Math.min(offset, items.size());
        int to = Math.min(from + limit, items.size());
        return List.copyOf(items.subList(from, to));
    }
}
