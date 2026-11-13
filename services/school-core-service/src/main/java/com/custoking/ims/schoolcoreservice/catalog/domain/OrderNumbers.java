package com.custoking.ims.schoolcoreservice.catalog.domain;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * Order numbers (design D10, D11): {@code <ItemCode>-<SchoolOrderCode>-<YY-MM-DD>-<NNNN>}, for example
 * {@code NB-DPS_GUR-26-11-13-0042}. The date is the placement date in the school's time zone; NNNN is a running counter per
 * item code per calendar year of that date, which widens past 9999 and stays unique. Catalog V37 backfilled with the same
 * format. The CK id stays the key everywhere; this is what people see.
 */
public final class OrderNumbers {
    public static final Pattern ITEM_CODE = Pattern.compile("^[A-Z]{2,4}$");
    /** Characters an order number can contain, for lookups by a pasted number. */
    public static final Pattern LOOKUP = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
    public static final int MAX_LENGTH = 40;
    private static final String FALLBACK_ZONE = "Asia/Kolkata";

    private OrderNumbers() { }

    /** The calendar day of the placement in the school's time zone: 00:30 IST on the 13th is the 13th, not the UTC 12th. */
    public static LocalDate placementDate(Instant placedAt, String timeZone) {
        return LocalDate.ofInstant(placedAt, zone(timeZone));
    }

    /** The counter's year: the calendar year of the placement date. */
    public static int counterYear(LocalDate placementDate) {
        return placementDate.getYear();
    }

    public static String format(String itemCode, String schoolOrderCode, LocalDate placementDate, long counter) {
        if (itemCode == null || !ITEM_CODE.matcher(itemCode).matches()) throw new IllegalArgumentException("Invalid item code");
        if (schoolOrderCode == null || !SchoolOrderCode.FORMAT.matcher(schoolOrderCode).matches()) {
            throw new IllegalArgumentException("Invalid school order code");
        }
        if (counter < 1) throw new IllegalArgumentException("A running number starts at 1");
        String number = "%s-%s-%02d-%02d-%02d-%04d".formatted(itemCode, schoolOrderCode, placementDate.getYear() % 100,
                placementDate.getMonthValue(), placementDate.getDayOfMonth(), counter);
        if (number.length() > MAX_LENGTH) throw new IllegalArgumentException("Order number is too long");
        return number;
    }

    private static ZoneId zone(String timeZone) {
        try {
            return ZoneId.of(timeZone == null || timeZone.isBlank() ? FALLBACK_ZONE : timeZone.strip());
        } catch (DateTimeException invalid) {
            return ZoneId.of(FALLBACK_ZONE);
        }
    }
}
