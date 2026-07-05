package com.custoking.ims.schoolcoreservice.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Pure helpers for uniform A/B/C… section letters. No database access. */
public final class SchoolStructureDelta {

    private SchoolStructureDelta() {}

    public static String sectionLetter(int index) {
        return String.valueOf((char) ('A' + index));
    }

    public static int letterIndex(String name) {
        if (name == null || name.length() != 1) return -1;
        char c = Character.toUpperCase(name.charAt(0));
        return (c >= 'A' && c <= 'Z') ? c - 'A' : -1;
    }

    public static List<String> activeLetters(int sectionCount) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < sectionCount; i++) out.add(sectionLetter(i));
        return out;
    }

    public static List<String> droppedLetters(Collection<String> existingNames, int newSectionCount) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String name : existingNames) {
            int idx = letterIndex(name);
            if (idx >= newSectionCount) out.add(sectionLetter(idx));
        }
        List<String> sorted = new ArrayList<>(out);
        sorted.sort(java.util.Comparator.comparingInt(SchoolStructureDelta::letterIndex));
        return sorted;
    }
}
