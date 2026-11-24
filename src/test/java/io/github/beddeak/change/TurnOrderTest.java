package io.github.beddeak.change;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TurnOrderTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();

    @Test
    void rotatesAndWraps() {
        TurnOrder order = order();
        assertEquals(b, order.after(a, id -> true));
        assertEquals(c, order.after(b, id -> true));
        assertEquals(a, order.after(d, id -> true));
    }

    @Test
    void skipsUnavailablePlayers() {
        TurnOrder order = order();
        assertEquals(d, order.after(b, id -> !id.equals(c)));
        assertEquals(a, order.after(c, id -> !id.equals(d)));
    }

    @Test
    void neverPicksCurrentOrNobody() {
        TurnOrder order = order();
        assertNull(order.after(a, id -> id.equals(a)));
        assertNull(order.random(a, id -> id.equals(a), new Random(1)));
    }

    @Test
    void unknownCurrentStartsFromTheTop() {
        assertEquals(a, order().after(UUID.randomUUID(), id -> true));
    }

    @Test
    void randomCoversEveryoneElse() {
        TurnOrder order = order();
        Random random = new Random(3);
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            UUID picked = order.random(b, id -> true, random);
            assertNotEquals(b, picked);
            seen.add(picked);
        }
        assertEquals(Set.of(a, c, d), seen);
    }

    @Test
    void putFirstKeepsTheRestInOrder() {
        TurnOrder order = order();
        order.putFirst(List.of(c, a));
        assertEquals(List.of(c, a, b, d), order.players());
    }

    @Test
    void addIgnoresDuplicates() {
        TurnOrder order = order();
        assertFalse(order.add(b));
        order.remove(b);
        assertEquals(c, order.after(a, id -> true));
        assertEquals(List.of(a, c, d), order.players());
    }

    private TurnOrder order() {
        TurnOrder order = new TurnOrder();
        order.reset(List.of(a, b, c, d));
        return order;
    }
}
