package com.mtagj.bptracker.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class DateTimeUtils {

    private DateTimeUtils() {
    }

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public static String formatDate(long epochMillis) {
        return DATE_FORMAT.format(new Date(epochMillis));
    }

    public static String formatTime(long epochMillis) {
        return TIME_FORMAT.format(new Date(epochMillis));
    }

    public static long combine(int year, int month, int dayOfMonth, int hourOfDay, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(year, month, dayOfMonth, hourOfDay, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Midnight (00:00:00.000) of the day containing epochMillis. */
    public static long startOfDay(long epochMillis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(epochMillis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 23:59:59.999 of the day containing epochMillis. */
    public static long endOfDay(long epochMillis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(epochMillis);
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 59);
        c.set(Calendar.MILLISECOND, 999);
        return c.getTimeInMillis();
    }
}
