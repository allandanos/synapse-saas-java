package dev.synapse.audit.dto;

import java.util.List;

/** The audit list envelope: {@code {data, next_cursor}} — cursor paging is not wired yet, so it is always null. */
public record AuditPage(List<AuditEntryRead> data, String nextCursor) {

    public static AuditPage of(List<AuditEntryRead> data) {
        return new AuditPage(List.copyOf(data), null);
    }
}
