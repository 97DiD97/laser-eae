package dev.eyesky;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.logging.Level;

public final class EyeSkyPlugin extends JavaPlugin implements Listener {

    private PackServer packServer;
    private ResourcePackInfo packInfo;
    private EyeManager eye;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (getConfig().getBoolean("pack.enabled", true)) {
            setupPack();
        }

        eye = new EyeManager(this);
        eye.start();

        getServer().getPluginManager().registerEvents(this, this);

        EyeCommand command = new EyeCommand(this);
        var cmd = getCommand("eye");
        if (cmd != null) {
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }
    }

    @Override
    public void onDisable() {
        if (eye != null) eye.stop();
        if (packServer != null) packServer.stop();
    }

    private void setupPack() {
        try (InputStream in = getResource("eyesky-pack.zip")) {
            if (in == null) {
                getLogger().severe("В jar нет eyesky-pack.zip - ресурспак не будет выдан!");
                return;
            }
            byte[] bytes = in.readAllBytes();

            byte[] sha1 = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : sha1) hex.append(String.format("%02x", b));
            // id зависит от содержимого: изменился пак - клиент скачает заново
            UUID id = UUID.nameUUIDFromBytes(bytes);

            String url = getConfig().getString("pack.url", "").trim();
            if (url.isEmpty()) {
                if (!getConfig().getBoolean("pack.serve-from-plugin", false)) {
                    getLogger().warning("В config.yml не указана pack.url - ресурспак не будет выдан, "
                            + "глаз у игроков будет фиолетово-чёрным кубом. Вставь ссылку на eyesky-pack.zip.");
                    return;
                }
                String bind = getConfig().getString("pack.bind-address", "0.0.0.0");
                int port = getConfig().getInt("pack.port", 8765);
                String host = getConfig().getString("pack.public-host", "127.0.0.1");
                packServer = new PackServer(bind, port, bytes);
                packServer.start();
                url = "http://" + host + ":" + port + "/pack.zip";
                getLogger().info("Раздаю ресурспак на порту " + port + ", ссылка для игроков: " + url);
            } else {
                getLogger().info("Ресурспак берётся по ссылке: " + url);
            }

            packInfo = ResourcePackInfo.resourcePackInfo(id, URI.create(url), hex.toString());
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Не удалось запустить раздачу ресурспака (порт занят?)", e);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Ошибка подготовки ресурспака", e);
        }
    }

    public boolean sendPack(Player player) {
        if (packInfo == null) return false;
        ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                .packs(packInfo)
                .prompt(Component.text(getConfig().getString("pack.prompt", "")))
                .required(getConfig().getBoolean("pack.required", true))
                .replace(true)
                .build();
        player.sendResourcePacks(request);
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sendPack(event.getPlayer());
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        if (event.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
            getLogger().warning(event.getPlayer().getName() + " не смог скачать ресурспак. Проверь pack.public-host "
                    + "и что порт " + getConfig().getInt("pack.port") + " открыт снаружи.");
        }
    }

    public EyeManager eye() {
        return eye;
    }
}
