package io.github.beddeak.change;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Everything that belongs to the shared body. The controller's name, skin and permissions never move. */
record BodySnapshot(Location location, Vector velocity, float fallDistance, boolean gliding, GameMode gameMode,
                    double health, double absorption, int foodLevel, float saturation, float exhaustion,
                    int level, float exp, int totalExperience,
                    ItemStack[] inventory, int heldSlot, ItemStack[] enderChest,
                    List<PotionEffect> effects, int fireTicks, int freezeTicks, int remainingAir,
                    Location respawn) {

    static BodySnapshot capture(Player player) {
        PlayerInventory inventory = player.getInventory();
        return new BodySnapshot(player.getLocation(), player.getVelocity(), player.getFallDistance(), player.isGliding(), player.getGameMode(),
                player.getHealth(), player.getAbsorptionAmount(), player.getFoodLevel(), player.getSaturation(), player.getExhaustion(),
                player.getLevel(), player.getExp(), player.getTotalExperience(),
                copy(inventory.getContents()), inventory.getHeldItemSlot(), copy(player.getEnderChest().getContents()),
                List.copyOf(player.getActivePotionEffects()), player.getFireTicks(), player.getFreezeTicks(), player.getRemainingAir(),
                player.getRespawnLocation(false));
    }

    /** A dead controller hands over the body as it would respawn: at the respawn point with whatever keepInventory kept. */
    static BodySnapshot afterDeath(Player player) {
        Location spawn = player.getRespawnLocation(true);
        if (spawn == null) {
            spawn = Bukkit.getWorlds().getFirst().getSpawnLocation();
        }
        boolean keep = Boolean.TRUE.equals(player.getWorld().getGameRuleValue(GameRules.KEEP_INVENTORY));
        PlayerInventory inventory = player.getInventory();
        return new BodySnapshot(spawn, new Vector(), 0f, false, player.getGameMode(),
                maxHealth(player), 0, 20, 5f, 0f,
                keep ? player.getLevel() : 0, keep ? player.getExp() : 0f, keep ? player.getTotalExperience() : 0,
                copy(inventory.getContents()), inventory.getHeldItemSlot(), copy(player.getEnderChest().getContents()),
                List.of(), 0, 0, player.getMaximumAir(), player.getRespawnLocation(false));
    }

    /** Returns false when another plugin cancelled the teleport. */
    boolean applyTo(Player player, Settings settings) {
        player.setGameMode(gameMode);
        PlayerInventory inventory = player.getInventory();
        inventory.setContents(copy(this.inventory));
        inventory.setHeldItemSlot(heldSlot);
        if (settings.enderChest()) {
            player.getEnderChest().setContents(copy(enderChest));
        }
        player.clearActivePotionEffects();
        player.addPotionEffects(effects);

        boolean moved = player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
        player.setVelocity(velocity);
        player.setFallDistance(fallDistance);
        player.setGliding(gliding);

        player.setHealth(Math.min(Math.max(health, 0.5), maxHealth(player)));
        player.setAbsorptionAmount(absorption);
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setExhaustion(exhaustion);

        player.setLevel(level);
        player.setExp(exp);
        player.setTotalExperience(totalExperience);

        player.setFireTicks(fireTicks);
        player.setFreezeTicks(freezeTicks);
        player.setRemainingAir(remainingAir);
        if (settings.respawnPoint()) {
            player.setRespawnLocation(respawn, true);
        }
        return moved;
    }

    /** Leaves the player with nothing that belongs to the body, so no item can exist twice. */
    static void clear(Player player, Settings settings) {
        player.getInventory().clear();
        player.setItemOnCursor(null);
        if (settings.enderChest()) {
            player.getEnderChest().clear();
        }
        player.setLevel(0);
        player.setExp(0f);
        player.setTotalExperience(0);
        if (settings.respawnPoint()) {
            player.setRespawnLocation(null, true);
        }
        if (player.isDead()) {
            return;
        }
        player.clearActivePotionEffects();
        player.setHealth(maxHealth(player));
        player.setAbsorptionAmount(0);
        player.setFoodLevel(20);
        player.setSaturation(5f);
        player.setExhaustion(0f);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setRemainingAir(player.getMaximumAir());
        player.setFallDistance(0f);
        player.setGliding(false);
    }

    void save(ConfigurationSection section) {
        saveLocation(section, "location", location);
        section.set("fall-distance", (double) fallDistance);
        section.set("game-mode", gameMode.name());
        section.set("health", health);
        section.set("absorption", absorption);
        section.set("food", foodLevel);
        section.set("saturation", (double) saturation);
        section.set("exhaustion", (double) exhaustion);
        section.set("level", level);
        section.set("exp", (double) exp);
        section.set("total-experience", totalExperience);
        section.set("inventory", encode(inventory));
        section.set("held-slot", heldSlot);
        section.set("ender-chest", encode(enderChest));
        section.set("effects", new ArrayList<>(effects));
        section.set("fire-ticks", fireTicks);
        section.set("freeze-ticks", freezeTicks);
        section.set("air", remainingAir);
        saveLocation(section, "respawn", respawn);
    }

    static BodySnapshot load(ConfigurationSection section) {
        Location location = loadLocation(section.getConfigurationSection("location"));
        if (location == null) {
            throw new IllegalArgumentException("몸이 있던 월드를 찾을 수 없습니다");
        }
        List<PotionEffect> effects = new ArrayList<>();
        for (Object effect : section.getList("effects", List.of())) {
            if (effect instanceof PotionEffect potion) {
                effects.add(potion);
            }
        }
        return new BodySnapshot(location, new Vector(), (float) section.getDouble("fall-distance"), false,
                GameMode.valueOf(section.getString("game-mode", "SURVIVAL")),
                section.getDouble("health", 20), section.getDouble("absorption"), section.getInt("food", 20),
                (float) section.getDouble("saturation", 5), (float) section.getDouble("exhaustion"),
                section.getInt("level"), (float) section.getDouble("exp"), section.getInt("total-experience"),
                decode(section.getString("inventory", "")), section.getInt("held-slot"), decode(section.getString("ender-chest", "")),
                effects, section.getInt("fire-ticks"), section.getInt("freeze-ticks"), section.getInt("air", 300),
                loadLocation(section.getConfigurationSection("respawn")));
    }

    static double maxHealth(Player player) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        return maxHealth == null ? 20.0 : maxHealth.getValue();
    }

    private static void saveLocation(ConfigurationSection section, String path, Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        ConfigurationSection target = section.createSection(path);
        target.set("world", location.getWorld().getName());
        target.set("x", location.getX());
        target.set("y", location.getY());
        target.set("z", location.getZ());
        target.set("yaw", (double) location.getYaw());
        target.set("pitch", (double) location.getPitch());
    }

    private static Location loadLocation(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        World world = Bukkit.getWorld(section.getString("world", ""));
        if (world == null) {
            return null;
        }
        return new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }

    private static String encode(ItemStack[] items) {
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
    }

    private static ItemStack[] decode(String text) {
        return text.isEmpty() ? new ItemStack[0] : ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(text));
    }

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] == null ? null : items[i].clone();
        }
        return copy;
    }
}
