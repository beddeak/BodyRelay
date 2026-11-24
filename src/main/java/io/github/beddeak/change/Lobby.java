package io.github.beddeak.change;

import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;

/** An empty void world with a sealed room, so waiting players see nothing of the game. */
final class Lobby {

    private static final int FLOOR_Y = 100;
    private static final int RADIUS = 6;

    private final World world;

    private Lobby(World world) {
        this.world = world;
    }

    static Lobby open(String name) {
        World world = Bukkit.getWorld(name);
        if (world == null) {
            world = new WorldCreator(name).generator(new ChunkGenerator() { }).generateStructures(false).createWorld();
        }
        if (world == null) {
            throw new IllegalStateException("대기실 월드를 만들지 못했습니다: " + name);
        }
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
        world.setSpawnLocation(0, FLOOR_Y + 1, 0);
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                boolean edge = Math.abs(x) == RADIUS || Math.abs(z) == RADIUS;
                world.getBlockAt(x, FLOOR_Y, z).setType(Material.SMOOTH_QUARTZ, false);
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 4; y++) {
                    world.getBlockAt(x, y, z).setType(edge ? Material.BARRIER : Material.AIR, false);
                }
            }
        }
        return new Lobby(world);
    }

    Location spawn() {
        return new Location(world, 0.5, FLOOR_Y + 1, 0.5);
    }

    boolean contains(Player player) {
        return world.equals(player.getWorld());
    }

    boolean contains(Location location) {
        return location != null && world.equals(location.getWorld());
    }

    boolean fellOut(Player player) {
        return contains(player) && player.getLocation().getY() < FLOOR_Y - 3;
    }
}
