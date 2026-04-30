package com.dia.ismdtoolbackend.models.rpp;

import java.util.Comparator;

public final class RppCodeComparator implements Comparator<String> {

    public static final RppCodeComparator INSTANCE = new RppCodeComparator();

    private RppCodeComparator() {}

    @Override
    public int compare(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (NumberFormatException nfe) {
            return a.compareTo(b);
        }
    }
}
