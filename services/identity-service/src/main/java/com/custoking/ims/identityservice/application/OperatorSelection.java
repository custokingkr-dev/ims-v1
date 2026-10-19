package com.custoking.ims.identityservice.application;

import java.util.List;
import java.util.TreeSet;

/**
 * Chooses the operator who receives a new order's steps (spec v4): the item-specific operator for the school and
 * catalogue item; failing that, the school-wide operator. When several operators match at a level, none is picked and
 * superadmin assigns manually.
 */
public final class OperatorSelection {
    public enum Outcome { ITEM_SPECIFIC, SCHOOL_WIDE, AMBIGUOUS, NONE }

    /** {@code userId} is set only for ITEM_SPECIFIC and SCHOOL_WIDE; {@code candidates} lists who matched when ambiguous. */
    public record Result(Outcome outcome, Long userId, List<Long> candidates) { }

    private OperatorSelection() { }

    public static Result choose(List<Long> itemSpecific, List<Long> schoolWide) {
        List<Long> item = distinct(itemSpecific);
        if (item.size() == 1) return new Result(Outcome.ITEM_SPECIFIC, item.get(0), item);
        if (item.size() > 1) return new Result(Outcome.AMBIGUOUS, null, item);
        List<Long> wide = distinct(schoolWide);
        if (wide.size() == 1) return new Result(Outcome.SCHOOL_WIDE, wide.get(0), wide);
        if (wide.size() > 1) return new Result(Outcome.AMBIGUOUS, null, wide);
        return new Result(Outcome.NONE, null, List.of());
    }

    private static List<Long> distinct(List<Long> ids) {
        return ids == null ? List.of() : List.copyOf(new TreeSet<>(ids));
    }
}
