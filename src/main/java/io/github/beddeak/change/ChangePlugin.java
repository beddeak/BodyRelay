package io.github.beddeak.change;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

public final class ChangePlugin extends JavaPlugin {

    static final Component PREFIX = Component.text("[체인지] ", NamedTextColor.GOLD);

    private Settings settings;
    private Relay relay;
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig(), getLogger());
        dataFile = new File(getDataFolder(), "data.yml");
        relay = new Relay(this, Lobby.open(settings.lobbyWorld()));
        loadState();
        getServer().getPluginManager().registerEvents(new RelayListener(this), this);
        ChangeCommand command = new ChangeCommand(this);
        PluginCommand pluginCommand = getCommand("change");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }
        for (Player player : getServer().getOnlinePlayers()) {
            relay.onJoin(player);
        }
        getServer().getScheduler().runTaskTimer(this, relay::tick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (relay != null) {
            relay.shutdown();
            saveState();
        }
    }

    Settings settings() {
        return settings;
    }

    Relay relay() {
        return relay;
    }

    void reloadSettings() {
        reloadConfig();
        settings = Settings.load(getConfig(), getLogger());
        relay.timer().setInterval(settings.turnSeconds());
        relay.updateBars();
    }

    void saveSetting(String path, Object value) {
        getConfig().set(path, value);
        saveConfig();
        reloadSettings();
    }

    void saveState() {
        YamlConfiguration yaml = new YamlConfiguration();
        relay.save(yaml);
        try {
            yaml.save(dataFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "릴레이 상태를 저장하지 못했습니다: " + dataFile, e);
        }
    }

    private void loadState() {
        if (!dataFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(dataFile);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().log(Level.SEVERE, "릴레이 상태를 읽지 못했습니다: " + dataFile, e);
            return;
        }
        relay.load(yaml);
    }
}
