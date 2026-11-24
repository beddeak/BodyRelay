package io.github.beddeak.change;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.util.Set;

final class RelayListener implements Listener {

    private static final Set<TeleportCause> BLOCKED = Set.of(TeleportCause.ENDER_PEARL, TeleportCause.SPECTATE,
            TeleportCause.NETHER_PORTAL, TeleportCause.END_PORTAL, TeleportCause.END_GATEWAY, TeleportCause.CONSUMABLE_EFFECT);

    private final ChangePlugin plugin;

    RelayListener(ChangePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.relay().onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        plugin.relay().onQuit(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && plugin.relay().isWaiting(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && plugin.relay().isWaiting(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (BLOCKED.contains(event.getCause()) && plugin.relay().isWaiting(event.getPlayer())
                && !plugin.relay().lobby().contains(event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (plugin.relay().isWaiting(event.getPlayer())) {
            event.setRespawnLocation(plugin.relay().lobby().spawn());
        }
    }

    @EventHandler
    public void onPostRespawn(PlayerPostRespawnEvent event) {
        plugin.relay().onRespawned(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (hiding()) {
            Component message = event.deathMessage();
            event.deathMessage(null);
            plugin.relay().deliverHidden(message);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (hiding()) {
            Component message = event.message();
            event.message(null);
            plugin.relay().deliverHidden(message);
        }
    }

    private boolean hiding() {
        return plugin.relay().running() && plugin.settings().hideGameMessages();
    }
}
