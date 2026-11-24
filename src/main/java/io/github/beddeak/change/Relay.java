package io.github.beddeak.change;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

/** One shared body; the players in {@link TurnOrder} take turns controlling it while the rest wait in the lobby. */
final class Relay {

    private static final Title.Times COUNTDOWN_TIMES = Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200));
    private static final Title.Times TURN_TIMES = Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(500));
    private static final Sound TICK_SOUND = Sound.sound(Key.key("block.note_block.hat"), Sound.Source.MASTER, 1f, 1.4f);
    private static final Sound TURN_SOUND = Sound.sound(Key.key("entity.enderman.teleport"), Sound.Source.MASTER, 1f, 1f);

    /** A body taken from its controller, with what was attached to it in the world. */
    private record Taken(BodySnapshot body, Entity vehicle, List<Mob> chasers, List<Player> watchers) {
        static Taken stored(BodySnapshot body) {
            return new Taken(body, null, List.of(), List.of());
        }
    }

    private final ChangePlugin plugin;
    private final Lobby lobby;
    private final TurnTimer timer;
    private final TurnOrder order = new TurnOrder();
    private final Random random = new Random();
    private final BossBar holderBar = bar();
    private final BossBar nextBar = bar();
    private final BossBar waitingBar = bar();
    private final BossBar watcherBar = bar();
    private UUID active;
    private UUID next;
    private BodySnapshot stored;
    private boolean waitingForHolder;

    Relay(ChangePlugin plugin, Lobby lobby) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.timer = new TurnTimer(plugin.settings().turnSeconds());
    }

    TurnTimer timer() {
        return timer;
    }

    TurnOrder order() {
        return order;
    }

    Lobby lobby() {
        return lobby;
    }

    UUID active() {
        return active;
    }

    boolean running() {
        return timer.running();
    }

    boolean waitingForHolder() {
        return waitingForHolder;
    }

    Player holder() {
        return active == null ? null : Bukkit.getPlayer(active);
    }

    boolean isWaiting(Player player) {
        return timer.running() && order.contains(player.getUniqueId()) && !player.getUniqueId().equals(active);
    }

    boolean eligible(Player player) {
        return !player.isDead() && plugin.settings().gameModes().contains(player.getGameMode())
                && !plugin.settings().isExcluded(player.getName());
    }

    /** Returns an error message, or null when the relay started. */
    String start(Player first) {
        if (timer.running()) {
            return "이미 진행 중입니다. 처음부터 하려면 stop 후 다시 start 하세요.";
        }
        if (first != null && first.isDead()) {
            return first.getName() + "이(가) 죽어 있습니다. 리스폰 후 다시 시도하세요.";
        }
        List<UUID> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (eligible(player)) {
                players.add(player.getUniqueId());
            }
        }
        Collections.shuffle(players, random);
        if (first != null) {
            players.remove(first.getUniqueId());
            players.addFirst(first.getUniqueId());
        }
        if (players.size() < 2) {
            return "참가자가 2명 이상 필요합니다. (지금 " + players.size() + "명) 관전자·크리에이티브와 제외된 사람은 빠집니다.";
        }
        order.reset(players);
        Player holder = Bukkit.getPlayer(players.getFirst());
        active = holder.getUniqueId();
        next = null;
        waitingForHolder = false;
        if (stored != null) {
            giveBody(holder, Taken.stored(stored), null);
            stored = null;
            broadcast(Component.text("지난번에 저장된 몸을 이어받아 시작합니다.", NamedTextColor.GRAY));
        }
        for (UUID id : players) {
            if (!id.equals(active)) {
                sendToLobby(Bukkit.getPlayer(id));
            }
        }
        timer.start();
        plugin.saveState();
        broadcast(Component.text("릴레이 시작! 첫 조종자는 " + holder.getName() + ", 한 사람당 "
                + TurnTimer.words(timer.interval()) + "씩 이어서 플레이합니다.", NamedTextColor.GREEN));
        holder.showTitle(Title.title(Component.text("내 차례!", NamedTextColor.GOLD),
                Component.text("첫 번째 조종자", NamedTextColor.YELLOW), TURN_TIMES));
        updateBars();
        return null;
    }

    /** Ends the relay. Whoever holds the body keeps it; everyone in the lobby comes back. */
    void stop() {
        Player holder = holder();
        if (holder == null && stored != null) {
            for (UUID id : order.players()) {
                Player player = Bukkit.getPlayer(id);
                if (player != null && !player.isDead()) {
                    giveBody(player, Taken.stored(stored), active);
                    stored = null;
                    holder = player;
                    break;
                }
            }
        }
        timer.stop();
        waitingForHolder = false;
        active = null;
        next = null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (lobby.contains(player)) {
                release(player, holder);
            }
        }
        plugin.saveState();
        updateBars();
    }

    /** Ends the current turn now and gives the body to {@code to}. */
    boolean handOff(Player to) {
        Player from = holder();
        if (to.equals(from) || to.isDead()) {
            return false;
        }
        Taken taken;
        if (from != null) {
            taken = take(from);
            sendToLobby(from);
        } else if (stored != null) {
            taken = Taken.stored(stored);
        } else {
            return false;
        }
        giveTurn(to, taken, from);
        return true;
    }

    String resume() {
        if (!timer.running() || !timer.paused()) {
            return "일시정지된 게임이 없습니다.";
        }
        if (holder() == null) {
            if (stored == null) {
                return name(active) + "에게 몸이 남아 있습니다. 그 사람이 접속하면 재개할 수 있습니다.";
            }
            Player to = nextPlayer(null);
            if (to == null) {
                return "몸을 이어받을 참가자가 접속해 있지 않습니다.";
            }
            giveTurn(to, Taken.stored(stored), null);
        }
        timer.resume();
        waitingForHolder = false;
        plugin.saveState();
        updateBars();
        return null;
    }

    boolean pause() {
        if (!timer.pause()) {
            return false;
        }
        plugin.saveState();
        updateBars();
        return true;
    }

    void setNext(Player player) {
        join(player);
        next = player.getUniqueId();
        updateBars();
    }

    void join(Player player) {
        if (!timer.running() || !order.add(player.getUniqueId())) {
            return;
        }
        if (!player.getUniqueId().equals(active)) {
            sendToLobby(player);
        }
        plugin.saveState();
        updateBars();
    }

    /** Removes a player from the rotation; the controller hands the body to the next player first. */
    String leave(UUID player) {
        if (!timer.running()) {
            return null;
        }
        if (player.equals(active)) {
            Player to = nextPlayer(Bukkit.getPlayer(player));
            if (to == null || !handOff(to)) {
                return "몸을 넘겨받을 사람이 없어 지금 조종자를 뺄 수 없습니다.";
            }
        }
        order.remove(player);
        if (player.equals(next)) {
            next = null;
        }
        Player online = Bukkit.getPlayer(player);
        if (online != null && lobby.contains(online)) {
            release(online, holder());
        }
        plugin.saveState();
        updateBars();
        return null;
    }

    void reorder(List<UUID> first) {
        order.putFirst(first);
        next = null;
        plugin.saveState();
        updateBars();
    }

    void tick() {
        if (timer.running()) {
            if (!timer.paused()) {
                if (timer.tick()) {
                    switchTurn();
                } else {
                    countdown();
                }
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (isWaiting(player) && !player.isDead() && (!lobby.contains(player) || lobby.fellOut(player))) {
                    sendToLobby(player);
                }
            }
        }
        updateBars();
    }

    void onJoin(Player player) {
        if (!timer.running()) {
            if (lobby.contains(player)) {
                release(player, null);
            }
            return;
        }
        UUID id = player.getUniqueId();
        if (id.equals(active)) {
            if (stored != null && !player.isDead()) {
                restoreHolder(player);
            }
            return;
        }
        if (!order.contains(id)) {
            if (!plugin.settings().autoJoin() || !eligible(player)) {
                if (lobby.contains(player)) {
                    release(player, holder());
                }
                return;
            }
            order.add(id);
            broadcast(Component.text(player.getName() + " 님이 릴레이에 참가했습니다.", NamedTextColor.GRAY));
        }
        sendToLobby(player);
        if (holder() == null && stored != null && !player.isDead()) {
            giveTurn(player, Taken.stored(stored), null);
        } else {
            plugin.saveState();
        }
    }

    /** The controller left the server: the body stays and goes to the next player right away. */
    void onQuit(Player player) {
        if (!timer.running() || !player.getUniqueId().equals(active)) {
            return;
        }
        Taken taken = take(player);
        stored = taken.body();
        Player to = nextPlayer(player);
        if (to != null) {
            broadcast(Component.text(player.getName() + " 님이 나가서 바로 다음 사람에게 넘깁니다.", NamedTextColor.GRAY));
            giveTurn(to, taken, null);
            return;
        }
        if (!timer.paused()) {
            timer.pause();
            waitingForHolder = true;
        }
        plugin.saveState();
        broadcast(Component.text(player.getName() + " 님이 나갔고 이어받을 사람이 없어 일시정지합니다. 누군가 들어오면 이어집니다.", NamedTextColor.GRAY));
    }

    void onRespawned(Player player) {
        if (isWaiting(player)) {
            sendToLobby(player);
        } else if (player.getUniqueId().equals(active) && stored != null) {
            restoreHolder(player);
        }
    }

    /** Server stop or reload: the body is saved to disk so any participant can continue after restart. */
    void shutdown() {
        Player holder = holder();
        if (timer.running() && holder != null) {
            stored = take(holder).body();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (BossBar bar : List.of(holderBar, nextBar, waitingBar, watcherBar)) {
                player.hideBossBar(bar);
            }
        }
    }

    void deliverHidden(Component message) {
        if (message == null) {
            return;
        }
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isWaiting(player)) {
                player.sendMessage(message);
            }
        }
    }

    Player nextPlayer(Player leaving) {
        Predicate<UUID> available = id -> {
            Player player = Bukkit.getPlayer(id);
            return player != null && !player.isDead() && !player.equals(leaving) && !id.equals(active);
        };
        if (next != null && order.contains(next) && available.test(next)) {
            return Bukkit.getPlayer(next);
        }
        next = plugin.settings().randomOrder() ? order.random(active, available, random) : order.after(active, available);
        return next == null ? null : Bukkit.getPlayer(next);
    }

    void save(ConfigurationSection yaml) {
        yaml.set("running", timer.running());
        yaml.set("remaining", timer.remaining());
        yaml.set("active", active == null ? null : active.toString());
        yaml.set("next", next == null ? null : next.toString());
        yaml.set("order", order.players().stream().map(UUID::toString).toList());
        if (stored != null) {
            stored.save(yaml.createSection("body"));
        }
    }

    void load(ConfigurationSection yaml) {
        List<UUID> players = new ArrayList<>();
        for (String id : yaml.getStringList("order")) {
            UUID uuid = uuid(id);
            if (uuid != null) {
                players.add(uuid);
            }
        }
        order.reset(players);
        active = uuid(yaml.getString("active"));
        next = uuid(yaml.getString("next"));
        ConfigurationSection body = yaml.getConfigurationSection("body");
        if (body != null) {
            try {
                stored = BodySnapshot.load(body);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "저장된 몸을 불러오지 못했습니다. data.yml을 지우지 말고 확인하세요.", e);
            }
        }
        if (yaml.getBoolean("running") && active != null) {
            timer.start();
            timer.setRemaining(yaml.getInt("remaining", timer.interval()));
            timer.pause();
            plugin.getLogger().info("진행 중이던 릴레이를 불러왔습니다. 모두 접속하면 /change resume 으로 이어서 하세요.");
        }
    }

    private void switchTurn() {
        Player to = nextPlayer(null);
        if (to == null) {
            broadcast(Component.text("교대할 사람이 없어 " + name(active) + " 님이 계속합니다.", NamedTextColor.GRAY));
            return;
        }
        if (!handOff(to)) {
            timer.pause();
            waitingForHolder = true;
            broadcast(Component.text(name(active) + " 님이 몸을 가진 채 접속해 있지 않아 일시정지합니다.", NamedTextColor.GRAY));
        }
    }

    private void giveTurn(Player to, Taken taken, Player from) {
        UUID previous = active;
        stored = null;
        order.add(to.getUniqueId());
        giveBody(to, taken, previous);
        active = to.getUniqueId();
        next = null;
        timer.restartCycle();
        if (waitingForHolder) {
            waitingForHolder = false;
            timer.resume();
        }
        plugin.saveState();

        to.showTitle(Title.title(Component.text("내 차례!", NamedTextColor.GOLD),
                Component.text("그대로 이어서 플레이하세요", NamedTextColor.YELLOW), TURN_TIMES));
        to.playSound(TURN_SOUND, Sound.Emitter.self());
        if (from != null && from.isOnline()) {
            from.showTitle(Title.title(Component.text("교대!", NamedTextColor.AQUA),
                    Component.text("대기실에서 다음 차례를 기다리세요", NamedTextColor.GRAY), TURN_TIMES));
        }
        if (previous != null && !previous.equals(active)) {
            broadcast(Component.text(name(previous) + " ➜ " + to.getName() + " 교대", NamedTextColor.YELLOW));
        }
        updateBars();
    }

    private void restoreHolder(Player player) {
        giveBody(player, Taken.stored(stored), null);
        stored = null;
        if (waitingForHolder) {
            waitingForHolder = false;
            timer.resume();
        }
        plugin.saveState();
        player.sendMessage(ChangePlugin.PREFIX.append(Component.text("아직 내 차례입니다. 이어서 플레이하세요.", NamedTextColor.GOLD)));
    }

    /** Removes the body from its controller and leaves them empty; the controller is not moved. */
    private Taken take(Player holder) {
        holder.closeInventory();
        if (holder.isSleeping()) {
            holder.wakeup(false);
        }
        boolean dead = holder.isDead();
        BodySnapshot body = dead ? BodySnapshot.afterDeath(holder) : BodySnapshot.capture(holder);
        Entity vehicle = dead ? null : holder.getVehicle();
        if (vehicle != null) {
            holder.leaveVehicle();
        }
        List<Mob> chasers = new ArrayList<>();
        if (!dead) {
            for (Entity entity : holder.getNearbyEntities(64, 32, 64)) {
                if (entity instanceof Mob mob && holder.equals(mob.getTarget())) {
                    chasers.add(mob);
                }
            }
        }
        List<Player> watchers = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR && holder.equals(player.getSpectatorTarget())) {
                player.setSpectatorTarget(null);
                watchers.add(player);
            }
        }
        BodySnapshot.clear(holder, plugin.settings());
        return new Taken(body, vehicle, chasers, watchers);
    }

    private void giveBody(Player to, Taken taken, UUID previous) {
        to.closeInventory();
        if (to.isInsideVehicle()) {
            to.leaveVehicle();
        }
        try {
            if (!taken.body().applyTo(to, plugin.settings())) {
                plugin.getLogger().warning(to.getName() + " 텔레포트가 다른 플러그인에 의해 취소됐습니다.");
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, to.getName() + "에게 몸을 옮기지 못했습니다", e);
        }
        if (taken.vehicle() != null && taken.vehicle().isValid()) {
            taken.vehicle().addPassenger(to);
        }
        for (Mob mob : taken.chasers()) {
            if (mob.isValid()) {
                mob.setTarget(to);
            }
        }
        for (Player watcher : taken.watchers()) {
            if (watcher.isOnline() && watcher.getGameMode() == GameMode.SPECTATOR) {
                watcher.setSpectatorTarget(to);
            }
        }
        if (previous != null && !previous.equals(to.getUniqueId())) {
            transferOwnership(previous, to);
        }
    }

    /** Pets and projectiles of the previous controller follow the body, so a landing ender pearl cannot pull them back. */
    private static void transferOwnership(UUID previous, Player to) {
        for (World world : Bukkit.getWorlds()) {
            for (Tameable pet : world.getEntitiesByClass(Tameable.class)) {
                if (previous.equals(pet.getOwnerUniqueId())) {
                    pet.setOwner(to);
                }
            }
            for (Projectile projectile : world.getEntitiesByClass(Projectile.class)) {
                if (projectile.getShooter() instanceof Player shooter && shooter.getUniqueId().equals(previous)) {
                    projectile.setShooter(to);
                }
            }
        }
    }

    void sendToLobby(Player player) {
        player.closeInventory();
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        BodySnapshot.clear(player, plugin.settings());
        if (player.isDead()) {
            return;
        }
        player.setGameMode(GameMode.ADVENTURE);
        player.teleport(lobby.spawn(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        player.setVelocity(new Vector());
        player.setFallDistance(0f);
    }

    private void release(Player player, Player holder) {
        Location target = holder != null && !holder.isDead() && !lobby.contains(holder)
                ? holder.getLocation() : Bukkit.getWorlds().getFirst().getSpawnLocation();
        player.setGameMode(Bukkit.getDefaultGameMode());
        player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    private void countdown() {
        int remaining = timer.remaining();
        if (remaining > plugin.settings().countdownSeconds()) {
            return;
        }
        Player holder = holder();
        Player upcoming = nextPlayer(null);
        if (holder != null) {
            holder.showTitle(Title.title(Component.text(remaining, NamedTextColor.RED),
                    Component.text("곧 교대!", NamedTextColor.YELLOW), COUNTDOWN_TIMES));
            holder.playSound(TICK_SOUND, Sound.Emitter.self());
        }
        if (upcoming != null) {
            upcoming.showTitle(Title.title(Component.text(remaining, NamedTextColor.RED),
                    Component.text("곧 내 차례!", NamedTextColor.YELLOW), COUNTDOWN_TIMES));
            upcoming.playSound(TICK_SOUND, Sound.Emitter.self());
        }
    }

    void updateBars() {
        boolean visible = timer.running() && plugin.settings().bossBar();
        Player holder = holder();
        Player upcoming = visible ? nextPlayer(null) : null;
        if (visible) {
            int remaining = timer.remaining();
            String state = waitingForHolder ? "조종할 사람을 기다리는 중" : timer.paused() ? "일시정지" : TurnTimer.clock(remaining);
            String nextName = upcoming == null ? "없음" : upcoming.getName();
            holderBar.name(Component.text("조종 중 · " + (timer.paused() ? state : "교대까지 " + state), NamedTextColor.WHITE));
            nextBar.name(Component.text("다음 차례는 나! · " + state, NamedTextColor.WHITE));
            waitingBar.name(Component.text("대기 중 · 다음 차례 " + nextName + " · " + state, NamedTextColor.WHITE));
            watcherBar.name(Component.text("조종 " + (active == null ? "없음" : name(active)) + " · 다음 " + nextName + " · " + state, NamedTextColor.WHITE));
            BossBar.Color color = timer.paused() ? BossBar.Color.WHITE
                    : remaining <= Math.max(plugin.settings().countdownSeconds(), 1) ? BossBar.Color.RED
                    : remaining <= 10 ? BossBar.Color.YELLOW : BossBar.Color.GREEN;
            float progress = Math.clamp((float) remaining / timer.interval(), 0f, 1f);
            for (BossBar bar : List.of(holderBar, nextBar, waitingBar, watcherBar)) {
                bar.color(color);
                bar.progress(progress);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            BossBar wanted = !visible ? null
                    : player.equals(holder) ? holderBar
                    : player.equals(upcoming) ? nextBar
                    : order.contains(player.getUniqueId()) ? waitingBar : watcherBar;
            for (BossBar bar : List.of(holderBar, nextBar, waitingBar, watcherBar)) {
                if (bar == wanted) {
                    player.showBossBar(bar);
                } else {
                    player.hideBossBar(bar);
                }
            }
        }
    }

    static String name(UUID id) {
        if (id == null) {
            return "없음";
        }
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? id.toString().substring(0, 8) : name;
    }

    static void broadcast(Component message) {
        Bukkit.getServer().sendMessage(ChangePlugin.PREFIX.append(message));
    }

    private static BossBar bar() {
        return BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
    }

    private static UUID uuid(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
