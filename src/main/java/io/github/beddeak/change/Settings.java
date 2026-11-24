package io.github.beddeak.change;

import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

record Settings(int turnSeconds, int countdownSeconds, boolean bossBar, boolean randomOrder, boolean autoJoin,
                boolean hideGameMessages, Set<GameMode> gameModes, Set<String> excluded,
                boolean enderChest, boolean respawnPoint, String lobbyWorld) {

    static Settings load(FileConfiguration config, Logger logger) {
        int turn = config.getInt("turn-seconds", 60);
        if (turn != TurnTimer.clampInterval(turn)) {
            logger.warning("turn-seconds는 " + TurnTimer.MIN_INTERVAL + "~" + TurnTimer.MAX_SECONDS + " 사이여야 합니다: " + turn);
            turn = TurnTimer.clampInterval(turn);
        }
        int countdown = Math.clamp(config.getInt("countdown-seconds", 5), 0, 60);
        String order = config.getString("order", "rotation").trim().toLowerCase(Locale.ROOT);
        if (!order.equals("rotation") && !order.equals("random")) {
            logger.warning("order는 rotation 또는 random이어야 합니다: " + order);
            order = "rotation";
        }
        Set<GameMode> modes = EnumSet.noneOf(GameMode.class);
        for (String name : config.getStringList("game-modes")) {
            try {
                modes.add(GameMode.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                logger.warning("game-modes에 알 수 없는 게임 모드가 있습니다: " + name);
            }
        }
        if (modes.isEmpty()) {
            logger.warning("game-modes가 비어 있어 SURVIVAL, ADVENTURE를 사용합니다.");
            modes = EnumSet.of(GameMode.SURVIVAL, GameMode.ADVENTURE);
        }
        Set<String> excluded = new LinkedHashSet<>();
        for (String name : config.getStringList("excluded")) {
            excluded.add(name.trim().toLowerCase(Locale.ROOT));
        }
        String lobby = config.getString("lobby-world", "change_lobby").trim();
        return new Settings(turn, countdown,
                config.getBoolean("bossbar", true),
                order.equals("random"),
                config.getBoolean("auto-join", true),
                config.getBoolean("hide-game-messages", true),
                Set.copyOf(modes), Set.copyOf(excluded),
                config.getBoolean("body.ender-chest", true),
                config.getBoolean("body.respawn-point", true),
                lobby.isEmpty() ? "change_lobby" : lobby);
    }

    boolean isExcluded(String name) {
        return excluded.contains(name.toLowerCase(Locale.ROOT));
    }
}
