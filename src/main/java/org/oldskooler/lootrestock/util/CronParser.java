package org.oldskooler.lootrestock.util;

import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.util.*;
import java.util.stream.Collectors;

public class CronParser {
    private final String expression;
    private final Set<Integer> minutes;
    private final Set<Integer> hours;
    private final Set<Integer> daysOfMonth;
    private final Set<Integer> months;
    private final Set<Integer> daysOfWeek;

    public CronParser(String cronExpression) {
        this.expression = cronExpression.trim();
        String[] parts = expression.split("\\s+");

        if (parts.length != 5) {
            throw new IllegalArgumentException(
                    "Cron expression must have 5 fields: minute hour day month day-of-week");
        }

        this.minutes = parseField(parts[0], 0, 59);
        this.hours = parseField(parts[1], 0, 23);
        this.daysOfMonth = parseField(parts[2], 1, 31);
        this.months = parseField(parts[3], 1, 12);
        this.daysOfWeek = parseField(parts[4], 0, 6); // 0=Sunday
    }

    private Set<Integer> parseField(String field, int min, int max) {
        Set<Integer> values = new TreeSet<>();

        if (field.equals("*")) {
            for (int i = min; i <= max; i++) {
                values.add(i);
            }
            return values;
        }

        // Handle comma-separated values
        String[] parts = field.split(",");
        for (String part : parts) {
            if (part.contains("/")) {
                // Step values (e.g., */5 or 0-30/5)
                String[] stepParts = part.split("/");
                int step = Integer.parseInt(stepParts[1]);

                Set<Integer> range;
                if (stepParts[0].equals("*")) {
                    range = new TreeSet<>();
                    for (int i = min; i <= max; i++) {
                        range.add(i);
                    }
                } else if (stepParts[0].contains("-")) {
                    range = parseRange(stepParts[0], min, max);
                } else {
                    int start = Integer.parseInt(stepParts[0]);
                    range = new TreeSet<>();
                    for (int i = start; i <= max; i++) {
                        range.add(i);
                    }
                }

                for (int val : range) {
                    if ((val - min) % step == 0) {
                        values.add(val);
                    }
                }
            } else if (part.contains("-")) {
                // Range (e.g., 1-5)
                values.addAll(parseRange(part, min, max));
            } else {
                // Single value
                int value = Integer.parseInt(part);
                if (value < min || value > max) {
                    throw new IllegalArgumentException(
                            "Value " + value + " out of range [" + min + "-" + max + "]");
                }
                values.add(value);
            }
        }

        return values;
    }

    private Set<Integer> parseRange(String range, int min, int max) {
        String[] rangeParts = range.split("-");
        int start = Integer.parseInt(rangeParts[0]);
        int end = Integer.parseInt(rangeParts[1]);

        if (start < min || end > max || start > end) {
            throw new IllegalArgumentException("Invalid range: " + range);
        }

        Set<Integer> values = new TreeSet<>();
        for (int i = start; i <= end; i++) {
            values.add(i);
        }
        return values;
    }

    public boolean matches(LocalDateTime dateTime) {
        int minute = dateTime.getMinute();
        int hour = dateTime.getHour();
        int day = dateTime.getDayOfMonth();
        int month = dateTime.getMonthValue();
        int dow = dateTime.getDayOfWeek().getValue() % 7; // Convert to 0=Sunday

        return minutes.contains(minute) &&
                hours.contains(hour) &&
                daysOfMonth.contains(day) &&
                months.contains(month) &&
                daysOfWeek.contains(dow);
    }

    public LocalDateTime getNextExecution(LocalDateTime from) {
        LocalDateTime current = from.withSecond(0).withNano(0).plusMinutes(1);

        // Limit search to avoid infinite loops
        for (int i = 0; i < 366 * 24 * 60; i++) {
            if (matches(current)) {
                return current;
            }
            current = current.plusMinutes(1);
        }

        throw new IllegalStateException("No matching date found within next year");
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Cron: ").append(expression).append("\n");
        sb.append("Minutes: ").append(formatSet(minutes)).append("\n");
        sb.append("Hours: ").append(formatSet(hours)).append("\n");
        sb.append("Days of Month: ").append(formatSet(daysOfMonth)).append("\n");
        sb.append("Months: ").append(formatSet(months)).append("\n");
        sb.append("Days of Week: ").append(formatDaysOfWeek(daysOfWeek));
        return sb.toString();
    }

    private String formatSet(Set<Integer> set) {
        if (set.size() > 10) {
            return set.size() + " values";
        }
        return set.toString();
    }

    private String formatDaysOfWeek(Set<Integer> days) {
        String[] dayNames = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        return days.stream()
                .map(d -> dayNames[d])
                .collect(Collectors.joining(", "));
    }

    // Example usage
    public static void main(String[] args) {
        // Examples of cron expressions
        String[] expressions = {
                "0 9 * * 1-5",      // 9 AM on weekdays
                "*/15 * * * *",     // Every 15 minutes
                "0 0 1 * *",        // Midnight on the 1st of every month
                "30 14 * * 1",      // 2:30 PM every Monday
                "0 */6 * * *"       // Every 6 hours
        };

        LocalDateTime now = LocalDateTime.now();

        for (String expr : expressions) {
            CronParser parser = new CronParser(expr);
            System.out.println(parser.describe());
            System.out.println("Next execution: " + parser.getNextExecution(now));
            System.out.println("Matches now: " + parser.matches(now));
            System.out.println("---");
        }
    }
}