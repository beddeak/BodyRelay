package io.github.beddeak.change;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TurnTimer {

    static final int MIN_INTERVAL = 3;
    static final int MAX_SECONDS = 24 * 60 * 60;
    private static final Pattern PART = Pattern.compile("(\\d+)([hms]?)");

    private int interval;
    private int remaining;
    private boolean running;
    private boolean paused;

    TurnTimer(int interval) {
        this.interval = clampInterval(interval);
        this.remaining = this.interval;
    }

    void start() {
        running = true;
        paused = false;
        remaining = interval;
    }

    void stop() {
        running = false;
        paused = false;
        remaining = interval;
    }

    boolean pause() {
        if (!running || paused) {
            return false;
        }
        paused = true;
        return true;
    }

    boolean resume() {
        if (!running || !paused) {
            return false;
        }
        paused = false;
        return true;
    }

    /** Advances one second and returns true when the turn is over. */
    boolean tick() {
        if (!running || paused) {
            return false;
        }
        remaining--;
        if (remaining <= 0) {
            remaining = interval;
            return true;
        }
        return false;
    }

    void restartCycle() {
        remaining = interval;
    }

    void setInterval(int seconds) {
        interval = clampInterval(seconds);
        if (remaining > interval) {
            remaining = interval;
        }
    }

    void setRemaining(int seconds) {
        remaining = Math.clamp(seconds, 1, MAX_SECONDS);
    }

    void add(int seconds) {
        setRemaining(remaining + seconds);
    }

    int interval() {
        return interval;
    }

    int remaining() {
        return remaining;
    }

    boolean running() {
        return running;
    }

    boolean paused() {
        return paused;
    }

    static int clampInterval(int seconds) {
        return Math.clamp(seconds, MIN_INTERVAL, MAX_SECONDS);
    }

    /** Accepts 90, 90s, 2m, 1m30s, 1h and a leading minus sign. */
    static OptionalInt parseSeconds(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT)
                .replace("시간", "h").replace("분", "m").replace("초", "s");
        boolean negative = value.startsWith("-");
        if (negative || value.startsWith("+")) {
            value = value.substring(1);
        }
        if (value.isEmpty()) {
            return OptionalInt.empty();
        }
        Matcher matcher = PART.matcher(value);
        long total = 0;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) {
                return OptionalInt.empty();
            }
            String digits = matcher.group(1);
            long amount = digits.length() > 9 ? MAX_SECONDS : Long.parseLong(digits);
            total += switch (matcher.group(2)) {
                case "h" -> amount * 3600;
                case "m" -> amount * 60;
                default -> amount;
            };
            end = matcher.end();
            if (total > MAX_SECONDS) {
                total = MAX_SECONDS;
            }
        }
        if (end != value.length()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) (negative ? -total : total));
    }

    static String clock(int seconds) {
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    static String words(int seconds) {
        int minutes = seconds / 60;
        int rest = seconds % 60;
        if (minutes == 0) {
            return rest + "초";
        }
        return rest == 0 ? minutes + "분" : minutes + "분 " + rest + "초";
    }
}
