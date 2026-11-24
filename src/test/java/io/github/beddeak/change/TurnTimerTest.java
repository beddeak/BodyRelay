package io.github.beddeak.change;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnTimerTest {

    @Test
    void firesOncePerInterval() {
        TurnTimer timer = new TurnTimer(60);
        timer.start();
        for (int second = 1; second < 60; second++) {
            assertFalse(timer.tick(), "second " + second);
        }
        assertTrue(timer.tick());
        assertEquals(60, timer.remaining());
    }

    @Test
    void stoppedOrPausedTimerDoesNotCount() {
        TurnTimer timer = new TurnTimer(10);
        assertFalse(timer.tick());
        timer.start();
        assertTrue(timer.pause());
        assertFalse(timer.pause());
        for (int i = 0; i < 20; i++) {
            assertFalse(timer.tick());
        }
        assertEquals(10, timer.remaining());
        assertTrue(timer.resume());
        timer.tick();
        assertEquals(9, timer.remaining());
        timer.stop();
        assertFalse(timer.resume());
    }

    @Test
    void addAndSetKeepAtLeastOneSecond() {
        TurnTimer timer = new TurnTimer(60);
        timer.start();
        timer.add(30);
        assertEquals(90, timer.remaining());
        timer.add(-500);
        assertEquals(1, timer.remaining());
        assertTrue(timer.tick());
        timer.setRemaining(15);
        assertEquals(15, timer.remaining());
    }

    @Test
    void shorterIntervalClampsCurrentCountdown() {
        TurnTimer timer = new TurnTimer(120);
        timer.start();
        timer.setInterval(30);
        assertEquals(30, timer.remaining());
        timer.setInterval(600);
        assertEquals(30, timer.remaining());
        timer.setInterval(1);
        assertEquals(TurnTimer.MIN_INTERVAL, timer.interval());
    }

    @Test
    void parsesTimeText() {
        assertEquals(OptionalInt.of(90), TurnTimer.parseSeconds("90"));
        assertEquals(OptionalInt.of(90), TurnTimer.parseSeconds("90s"));
        assertEquals(OptionalInt.of(120), TurnTimer.parseSeconds("2m"));
        assertEquals(OptionalInt.of(90), TurnTimer.parseSeconds("1m30s"));
        assertEquals(OptionalInt.of(90), TurnTimer.parseSeconds("1분30초"));
        assertEquals(OptionalInt.of(3600), TurnTimer.parseSeconds("1h"));
        assertEquals(OptionalInt.of(-10), TurnTimer.parseSeconds("-10"));
        assertEquals(OptionalInt.of(30), TurnTimer.parseSeconds("+30s"));
        assertEquals(OptionalInt.of(TurnTimer.MAX_SECONDS), TurnTimer.parseSeconds("99999999999999"));
        assertTrue(TurnTimer.parseSeconds("abc").isEmpty());
        assertTrue(TurnTimer.parseSeconds("1x").isEmpty());
        assertTrue(TurnTimer.parseSeconds("").isEmpty());
        assertTrue(TurnTimer.parseSeconds("-").isEmpty());
    }

    @Test
    void formatsTime() {
        assertEquals("1:05", TurnTimer.clock(65));
        assertEquals("0:09", TurnTimer.clock(9));
        assertEquals("45초", TurnTimer.words(45));
        assertEquals("2분", TurnTimer.words(120));
        assertEquals("1분 30초", TurnTimer.words(90));
    }
}
