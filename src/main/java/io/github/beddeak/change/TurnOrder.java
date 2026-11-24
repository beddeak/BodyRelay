package io.github.beddeak.change;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;

final class TurnOrder {

    private final List<UUID> players = new ArrayList<>();

    List<UUID> players() {
        return List.copyOf(players);
    }

    boolean contains(UUID player) {
        return players.contains(player);
    }

    void reset(List<UUID> order) {
        players.clear();
        order.forEach(this::add);
    }

    boolean add(UUID player) {
        if (players.contains(player)) {
            return false;
        }
        players.add(player);
        return true;
    }

    boolean remove(UUID player) {
        return players.remove(player);
    }

    /** Puts the given players first in that order; everyone else keeps their relative order. */
    void putFirst(List<UUID> first) {
        List<UUID> rest = new ArrayList<>(players);
        rest.removeAll(first);
        players.clear();
        first.forEach(this::add);
        rest.forEach(this::add);
    }

    UUID after(UUID current, Predicate<UUID> available) {
        int start = players.indexOf(current);
        for (int step = 1; step <= players.size(); step++) {
            UUID candidate = players.get(Math.floorMod(start + step, players.size()));
            if (!candidate.equals(current) && available.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    UUID random(UUID current, Predicate<UUID> available, Random random) {
        List<UUID> options = players.stream().filter(id -> !id.equals(current) && available.test(id)).toList();
        return options.isEmpty() ? null : options.get(random.nextInt(options.size()));
    }
}
