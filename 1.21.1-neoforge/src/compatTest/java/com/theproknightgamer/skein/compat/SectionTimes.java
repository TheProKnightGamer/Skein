package com.theproknightgamer.skein.compat;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Wall time per profiler section, taken from the push/pop markers vanilla
 * already places, on the one thread whose sections are being recorded.
 */
public final class SectionTimes {
    private SectionTimes() {}

    public static volatile boolean enabled;
    private static final Map<String, long[]> totals = new HashMap<>();
    private static final ThreadLocal<ArrayDeque<Object[]>> stacks = ThreadLocal.withInitial(ArrayDeque::new);

    public static void push(String scope, String label) {
        if (!enabled) return;
        ArrayDeque<Object[]> stack = stacks.get();
        String path = stack.isEmpty() ? scope + "/" + label : stack.peek()[0] + "/" + label;
        stack.push(new Object[] {path, System.nanoTime()});
    }

    public static void pop() {
        if (!enabled) return;
        ArrayDeque<Object[]> stack = stacks.get();
        if (stack.isEmpty()) return;
        Object[] frame = stack.pop();
        long spent = System.nanoTime() - (long) frame[1];
        synchronized (totals) {
            totals.computeIfAbsent((String) frame[0], k -> new long[1])[0] += spent;
        }
    }

    public static void popPush(String scope, String label) {
        pop();
        push(scope, label);
    }

    public static String report(int ticks) {
        synchronized (totals) {
            return totals.entrySet().stream()
                    .filter(e -> e.getValue()[0] / 1e6 / ticks >= 0.02)
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> String.format(Locale.ROOT, "  %-60s %7.3f ms", e.getKey(), e.getValue()[0] / 1e6 / ticks))
                    .collect(Collectors.joining("\n"));
        }
    }
}
