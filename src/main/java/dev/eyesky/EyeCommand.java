package dev.eyesky;

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

public final class EyeCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB = List.of("visit", "laser", "shoot", "emotion", "summon", "pack", "status", "reload");
    private final EyeSkyPlugin plugin;

    public EyeCommand(EyeSkyPlugin plugin) {
        this.plugin = plugin;
    }

    private static void msg(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(text, color));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        EyeManager eye = plugin.eye();
        if (args.length == 0) {
            msg(sender, "/eye <visit [игрок]|laser [игрок]|shoot|emotion <имя> [сек]|summon|pack|status|reload>", NamedTextColor.GRAY);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "visit" -> {
                Player target;
                if (args.length > 1) {
                    target = Bukkit.getPlayerExact(args[1]);
                } else {
                    List<Player> list = eye.candidates();
                    target = list.isEmpty() ? null : list.get(new java.util.Random().nextInt(list.size()));
                }
                if (target == null || !eye.forceVisit(target)) {
                    msg(sender, "Не получилось: игрок не найден, не в мире глаза, или глаз ещё не появился.", NamedTextColor.RED);
                } else {
                    msg(sender, "Глаз летит к " + target.getName(), NamedTextColor.GREEN);
                }
            }
            case "laser" -> {
                Player at = null;
                if (args.length > 1) {
                    at = Bukkit.getPlayerExact(args[1]);
                    if (at == null) {
                        msg(sender, "Игрок не найден.", NamedTextColor.RED);
                        return true;
                    }
                }
                if (eye.laserBusy()) {
                    msg(sender, "Лазер уже работает.", NamedTextColor.RED);
                } else if (!eye.startLaser(at)) {
                    msg(sender, "Не получилось: глаз ещё не появился или под ним нет земли.", NamedTextColor.RED);
                } else {
                    msg(sender, at == null ? "Лазер заряжается!" : "Глаз летит к " + at.getName() + " и заряжает лазер!",
                            NamedTextColor.GREEN);
                }
            }
            case "shoot" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "Только для игроков: глаз бьёт туда, куда ты смотришь.", NamedTextColor.RED);
                    return true;
                }
                if (eye.laserBusy()) {
                    msg(sender, "Лазер уже работает.", NamedTextColor.RED);
                    return true;
                }
                var hit = p.rayTraceBlocks(400);
                if (hit == null || hit.getHitPosition() == null) {
                    msg(sender, "Смотри на землю или на блок (не дальше 400 блоков).", NamedTextColor.RED);
                    return true;
                }
                org.bukkit.util.Vector v = hit.getHitPosition();
                org.bukkit.Location point = new org.bukkit.Location(p.getWorld(), v.getX(), v.getY(), v.getZ());
                if (!eye.startLaser(null, point)) {
                    msg(sender, "Не получилось: ты не в мире глаза или глаз ещё не появился.", NamedTextColor.RED);
                } else {
                    msg(sender, String.format("Глаз летит к точке %.0f %.0f %.0f и заряжает лазер!", v.getX(), v.getY(), v.getZ()),
                            NamedTextColor.GREEN);
                }
            }
            case "emotion" -> {
                Emotion e = args.length > 1 ? Emotion.parse(args[1]) : null;
                if (e == null) {
                    msg(sender, "Эмоции: " + String.join(", ", emotionNames()), NamedTextColor.RED);
                    return true;
                }
                int sec = 10;
                if (args.length > 2) {
                    try { sec = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) { }
                }
                eye.forceEmotion(e, sec);
                msg(sender, "Эмоция " + e + " на " + sec + " сек.", NamedTextColor.GREEN);
            }
            case "summon" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "Только для игроков.", NamedTextColor.RED);
                    return true;
                }
                msg(sender, eye.summon(p) ? "Глаз перенесён в небо над тобой." : "Ты не в мире глаза.",
                        NamedTextColor.GREEN);
            }
            case "pack" -> {
                int n = 0;
                for (Player p : Bukkit.getOnlinePlayers()) if (plugin.sendPack(p)) n++;
                msg(sender, "Ресурспак отправлен игрокам: " + n, NamedTextColor.GREEN);
            }
            case "status" -> msg(sender, eye.describe(), NamedTextColor.AQUA);
            case "reload" -> {
                plugin.reloadConfig();
                eye.reload();
                msg(sender, "Настройки глаза перечитаны. (Порт/адрес пака применяются только после рестарта.)",
                        NamedTextColor.GREEN);
            }
            default -> msg(sender, "Неизвестная подкоманда.", NamedTextColor.RED);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUB) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("emotion")) {
            for (String s : emotionNames()) if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("visit") || args[0].equalsIgnoreCase("laser"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        }
        return out;
    }

    private static List<String> emotionNames() {
        List<String> l = new ArrayList<>();
        for (Emotion e : Emotion.values()) l.add(e.name().toLowerCase(Locale.ROOT));
        return l;
    }
}
