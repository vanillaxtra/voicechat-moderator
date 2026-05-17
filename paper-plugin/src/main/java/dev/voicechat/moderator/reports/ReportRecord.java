package dev.voicechat.moderator.reports;

import java.time.Instant;
import java.util.UUID;

/** Immutable snapshot of a vcm_reports row. */
public final class ReportRecord {

    public final int     id;
    public final UUID    reporterUuid;
    public final String  reporterName;
    public final UUID    targetUuid;
    public final String  targetName;
    public final String  category;
    public final String  transcript;
    public final long    timestamp;  // epoch millis
    public final boolean reviewed;

    public ReportRecord(int id,
                        UUID reporterUuid, String reporterName,
                        UUID targetUuid,   String targetName,
                        String category, String transcript,
                        long timestamp, boolean reviewed) {
        this.id           = id;
        this.reporterUuid = reporterUuid;
        this.reporterName = reporterName;
        this.targetUuid   = targetUuid;
        this.targetName   = targetName;
        this.category     = category;
        this.transcript   = transcript;
        this.timestamp    = timestamp;
        this.reviewed     = reviewed;
    }

    public Instant getInstant() {
        return Instant.ofEpochMilli(timestamp);
    }
}
