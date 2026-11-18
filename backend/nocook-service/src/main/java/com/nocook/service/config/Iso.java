package com.nocook.service.config;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** ISO-8601 UTC with exactly three fraction digits (API.md: every timestamp we emit). */
public final class Iso {

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Iso() {}

    public static String format(Instant instant) {
        return ISO_MILLIS.format(instant);
    }
}
