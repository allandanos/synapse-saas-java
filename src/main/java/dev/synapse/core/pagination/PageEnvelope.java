package dev.synapse.core.pagination;

import java.util.List;

/** Consistent list envelope: {@code {data, meta: {total, limit, offset}}}. */
public record PageEnvelope<T>(List<T> data, PageMeta meta) {

    public static <T> PageEnvelope<T> of(List<T> items, long total, int limit, int offset) {
        return new PageEnvelope<>(List.copyOf(items), new PageMeta(total, limit, offset));
    }
}
