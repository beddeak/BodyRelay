package io.github.beddeak.change;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

final class ChangeCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "change.admin";
    private static final List<String> SUBCOMMANDS = List.of("start", "stop", "pause", "resume", "now", "switch", "next",
            "order", "join", "leave", "interval", "time", "add", "status", "reload", "help");
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("시작", "start"), Map.entry("종료", "stop"), Map.entry("정지", "stop"),
            Map.entry("일시정지", "pause"), Map.entry("재개", "resume"), Map.entry("지금", "now"),
            Map.entry("교대", "now"), Map.entry("넘기기", "switch"), Map.entry("지정", "switch"),
            Map.entry("다음", "next"), Map.entry("순서", "order"), Map.entry("참가", "join"),
            Map.entry("제외", "leave"), Map.entry("빼기", "leave"), Map.entry("주기", "interval"),
            Map.entry("시간", "time"), Map.entry("추가", "add"), Map.entry("연장", "add"),
            Map.entry("상태", "status"), Map.entry("리로드", "reload"), Map.entry("도움말", "help"));

    private final ChangePlugin plugin;

    ChangeCommand(ChangePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : canonical(args[0]);
        if (!sub.equals("status") && !sub.equals("help") && SUBCOMMANDS.contains(sub) && !sender.hasPermission(ADMIN)) {
            error(sender, "권한이 없습니다.");
            return true;
        }
        Relay relay = plugin.relay();
        TurnTimer timer = relay.timer();
        switch (sub) {
            case "start" -> start(sender, args);
            case "stop" -> {
                if (!relay.running()) {
                    error(sender, "진행 중인 릴레이가 없습니다.");
                    return true;
                }
                relay.stop();
                Relay.broadcast(Component.text("릴레이 종료! 대기실에 있던 사람들이 돌아옵니다.", NamedTextColor.YELLOW));
            }
            case "pause" -> {
                if (relay.pause()) {
                    Relay.broadcast(Component.text("일시정지 (" + TurnTimer.words(timer.remaining()) + " 남음)", NamedTextColor.YELLOW));
                } else {
                    error(sender, "진행 중인 릴레이가 없거나 이미 일시정지 상태입니다.");
                }
            }
            case "resume" -> {
                String problem = relay.resume();
                if (problem != null) {
                    error(sender, problem);
                } else {
                    Relay.broadcast(Component.text("재개! 교대까지 " + TurnTimer.words(timer.remaining()), NamedTextColor.GREEN));
                }
            }
            case "now" -> {
                if (!running(sender, label)) {
                    return true;
                }
                Player to = relay.nextPlayer(null);
                if (to == null || !relay.handOff(to)) {
                    error(sender, "지금 넘겨받을 수 있는 사람이 없습니다.");
                }
            }
            case "switch" -> switchTo(sender, args, label);
            case "next" -> next(sender, args, label);
            case "order" -> order(sender, args, label);
            case "join", "leave" -> membership(sender, args, label, sub.equals("join"));
            case "interval" -> interval(sender, args, label);
            case "time", "add" -> time(sender, args, label, sub.equals("add"));
            case "status" -> status(sender);
            case "reload" -> {
                plugin.reloadSettings();
                reply(sender, "config.yml을 다시 읽었습니다. 한 턴: " + TurnTimer.words(timer.interval()));
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void start(CommandSender sender, String[] args) {
        Player first = null;
        if (args.length >= 2) {
            first = player(sender, args[1]);
            if (first == null) {
                return;
            }
        }
        String problem = plugin.relay().start(first);
        if (problem != null) {
            error(sender, problem);
        }
    }

    private void switchTo(CommandSender sender, String[] args, String label) {
        if (!running(sender, label)) {
            return;
        }
        if (args.length < 2) {
            error(sender, "사용법: /" + label + " switch <플레이어>  지금 바로 그 사람에게 넘깁니다.");
            return;
        }
        Player to = player(sender, args[1]);
        if (to == null) {
            return;
        }
        if (to.getUniqueId().equals(plugin.relay().active())) {
            error(sender, to.getName() + "이(가) 이미 조종 중입니다.");
        } else if (to.isDead()) {
            error(sender, to.getName() + "이(가) 죽어 있습니다. 리스폰 후 다시 시도하세요.");
        } else if (!plugin.relay().handOff(to)) {
            error(sender, "몸을 가진 조종자가 접속해 있지 않아 넘길 수 없습니다.");
        }
    }

    private void next(CommandSender sender, String[] args, String label) {
        Relay relay = plugin.relay();
        if (!running(sender, label)) {
            return;
        }
        if (args.length < 2) {
            Player upcoming = relay.nextPlayer(null);
            reply(sender, "다음 차례: " + (upcoming == null ? "없음" : upcoming.getName()) + "  (바꾸려면 /" + label + " next <플레이어>)");
            return;
        }
        Player player = player(sender, args[1]);
        if (player == null) {
            return;
        }
        if (player.getUniqueId().equals(relay.active())) {
            error(sender, player.getName() + "은(는) 지금 조종 중입니다.");
            return;
        }
        relay.setNext(player);
        reply(sender, "다음 차례를 " + player.getName() + "(으)로 정했습니다.");
    }

    private void order(CommandSender sender, String[] args, String label) {
        Relay relay = plugin.relay();
        if (args.length >= 2) {
            List<UUID> first = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                UUID id = participant(args[i]);
                if (id == null) {
                    error(sender, args[i] + "은(는) 참가자가 아닙니다. 먼저 /" + label + " join " + args[i]);
                    return;
                }
                first.add(id);
            }
            relay.reorder(first);
        }
        List<UUID> players = relay.order().players();
        if (players.isEmpty()) {
            reply(sender, "순서가 비어 있습니다. /" + label + " start 하면 접속한 사람으로 정해집니다.");
            return;
        }
        Player upcoming = relay.running() ? relay.nextPlayer(null) : null;
        reply(sender, "순서 (" + players.size() + "명)");
        for (int i = 0; i < players.size(); i++) {
            UUID id = players.get(i);
            String tag = id.equals(relay.active()) ? " ◀ 조종 중"
                    : upcoming != null && id.equals(upcoming.getUniqueId()) ? " ◀ 다음" : "";
            line(sender, (i + 1) + ". " + Relay.name(id) + (Bukkit.getPlayer(id) == null ? " (오프라인)" : "") + tag);
        }
        if (plugin.settings().randomOrder()) {
            line(sender, "config의 order가 random이라 다음 사람은 매번 랜덤으로 뽑습니다.");
        }
    }

    private void membership(CommandSender sender, String[] args, String label, boolean join) {
        if (args.length < 2) {
            error(sender, "사용법: /" + label + (join ? " join" : " leave") + " <플레이어>");
            return;
        }
        Player online = Bukkit.getPlayerExact(args[1]);
        UUID id = online != null ? online.getUniqueId() : participant(args[1]);
        String name = online != null ? online.getName() : args[1];
        List<String> excluded = new ArrayList<>(plugin.getConfig().getStringList("excluded"));
        excluded.removeIf(entry -> entry.equalsIgnoreCase(name));
        if (!join) {
            excluded.add(name);
        }
        if (join) {
            plugin.saveSetting("excluded", excluded);
            if (online != null) {
                plugin.relay().join(online);
            }
            reply(sender, name + "을(를) 참가자로 넣었습니다." + (plugin.relay().running() ? " 대기실에서 차례를 기다립니다." : ""));
            return;
        }
        if (id != null) {
            String problem = plugin.relay().leave(id);
            if (problem != null) {
                error(sender, problem);
                return;
            }
        }
        plugin.saveSetting("excluded", excluded);
        reply(sender, name + "을(를) 릴레이에서 뺐습니다. 다시 넣으려면 /" + label + " join " + name);
    }

    private void interval(CommandSender sender, String[] args, String label) {
        OptionalInt seconds = args.length < 2 ? OptionalInt.empty() : TurnTimer.parseSeconds(args[1]);
        if (seconds.isEmpty() || seconds.getAsInt() <= 0) {
            error(sender, "사용법: /" + label + " interval <시간>  예: 60, 90s, 2m, 1m30s");
            return;
        }
        int interval = TurnTimer.clampInterval(seconds.getAsInt());
        plugin.saveSetting("turn-seconds", interval);
        Relay.broadcast(Component.text("한 턴 시간 변경: " + TurnTimer.words(interval) + "씩", NamedTextColor.YELLOW));
        if (interval != seconds.getAsInt()) {
            error(sender, "한 턴은 " + TurnTimer.MIN_INTERVAL + "초~24시간 사이로 맞췄습니다.");
        }
    }

    private void time(CommandSender sender, String[] args, String label, boolean add) {
        TurnTimer timer = plugin.relay().timer();
        if (!running(sender, label)) {
            return;
        }
        OptionalInt seconds = args.length < 2 ? OptionalInt.empty() : TurnTimer.parseSeconds(args[1]);
        if (seconds.isEmpty() || (!add && seconds.getAsInt() <= 0)) {
            error(sender, add ? "사용법: /" + label + " add <시간>  예: 30, 1m, -10 (음수면 줄이기)"
                    : "사용법: /" + label + " time <시간>  예: 10, 45s, 2m");
            return;
        }
        if (add) {
            timer.add(seconds.getAsInt());
        } else {
            timer.setRemaining(seconds.getAsInt());
        }
        plugin.relay().updateBars();
        String change = add ? (seconds.getAsInt() >= 0 ? "+" : "-") + TurnTimer.words(Math.abs(seconds.getAsInt())) + " → " : "";
        Relay.broadcast(Component.text("교대까지 " + change + TurnTimer.words(timer.remaining()), NamedTextColor.YELLOW));
    }

    private void status(CommandSender sender) {
        Relay relay = plugin.relay();
        TurnTimer timer = relay.timer();
        String state = !relay.running() ? "꺼짐" : relay.waitingForHolder() ? "조종할 사람 기다리는 중"
                : timer.paused() ? "일시정지" : "진행 중";
        reply(sender, "상태: " + state + " · 한 턴 " + TurnTimer.words(timer.interval())
                + (relay.running() ? " · 교대까지 " + TurnTimer.clock(timer.remaining()) : ""));
        if (!relay.running()) {
            return;
        }
        Player upcoming = relay.nextPlayer(null);
        line(sender, "조종: " + Relay.name(relay.active()) + (relay.holder() == null ? " (오프라인)" : "")
                + " · 다음: " + (upcoming == null ? "없음" : upcoming.getName())
                + " · 참가 " + relay.order().players().size() + "명");
        Player holder = relay.holder();
        boolean spoiler = sender instanceof Player player && relay.isWaiting(player);
        if (holder != null && !holder.isDead() && !spoiler && sender.hasPermission(ADMIN)) {
            line(sender, String.format(Locale.ROOT, "몸: 체력 %.1f/%.0f · 배고픔 %d · 레벨 %d · %s %d, %d, %d",
                    holder.getHealth(), BodySnapshot.maxHealth(holder), holder.getFoodLevel(), holder.getLevel(),
                    holder.getWorld().getName(), holder.getLocation().getBlockX(), holder.getLocation().getBlockY(),
                    holder.getLocation().getBlockZ()));
        }
    }

    private void help(CommandSender sender, String label) {
        reply(sender, "명령어 (/" + label + ")");
        line(sender, "start [첫 조종자]  릴레이 시작 (나머지는 대기실로)");
        line(sender, "stop · pause · resume  종료 · 일시정지 · 재개");
        line(sender, "now  지금 바로 다음 사람에게 교대");
        line(sender, "switch <이름>  지금 바로 그 사람에게 교대");
        line(sender, "next <이름>  다음 차례 지정");
        line(sender, "order [이름 이름 ...]  순서 보기 · 앞에서부터 바꾸기");
        line(sender, "join · leave <이름>  참가시키기 · 빼기");
        line(sender, "interval <시간>  한 턴 시간 (저장됨)");
        line(sender, "time <시간>  교대까지 남은 시간 직접 지정");
        line(sender, "add <시간>  남은 시간 늘리기 (-10 이면 줄이기)");
        line(sender, "status  상태 · reload  설정 다시 읽기");
        line(sender, "시간 예: 30, 90s, 2m, 1m30s, 1분30초");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(sender.hasPermission(ADMIN) ? SUBCOMMANDS : List.of("status", "help"), args[0]);
        }
        if (!sender.hasPermission(ADMIN)) {
            return List.of();
        }
        String sub = canonical(args[0]);
        String current = args[args.length - 1];
        if (sub.equals("order")) {
            return filter(plugin.relay().order().players().stream().map(Relay::name).toList(), current);
        }
        if (args.length != 2) {
            return List.of();
        }
        return switch (sub) {
            case "start", "switch", "next", "join" -> filter(onlineNames(), current);
            case "leave" -> {
                List<String> names = new ArrayList<>(plugin.relay().order().players().stream().map(Relay::name).toList());
                onlineNames().stream().filter(name -> !names.contains(name)).forEach(names::add);
                yield filter(names, current);
            }
            case "interval" -> filter(List.of("30s", "60s", "90s", "2m", "5m"), current);
            case "time" -> filter(List.of("5s", "10s", "30s", "1m"), current);
            case "add" -> filter(List.of("10s", "30s", "1m", "-10s", "-30s"), current);
            default -> List.of();
        };
    }

    private boolean running(CommandSender sender, String label) {
        if (plugin.relay().running()) {
            return true;
        }
        error(sender, "릴레이가 꺼져 있습니다. 먼저 /" + label + " start");
        return false;
    }

    private UUID participant(String name) {
        for (UUID id : plugin.relay().order().players()) {
            if (Relay.name(id).equalsIgnoreCase(name)) {
                return id;
            }
        }
        Player online = Bukkit.getPlayerExact(name);
        return online == null ? null : online.getUniqueId();
    }

    private static String canonical(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(lower, lower);
    }

    private static Player player(CommandSender sender, String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            player = Bukkit.getPlayer(name);
        }
        if (player == null) {
            error(sender, name + " 플레이어가 접속 중이 아닙니다.");
        }
        return player;
    }

    private static List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private static void reply(CommandSender sender, String message) {
        sender.sendMessage(ChangePlugin.PREFIX.append(Component.text(message, NamedTextColor.WHITE)));
    }

    private static void line(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.GRAY));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(ChangePlugin.PREFIX.append(Component.text(message, NamedTextColor.RED)));
    }
}
