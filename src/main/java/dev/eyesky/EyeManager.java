package dev.eyesky;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.bukkit.util.VoxelShape;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Единственный на весь сервер глаз.
 *
 * Картинка - ItemDisplay с billboard (всегда повёрнут к смотрящему). Физическое тело - невидимый куб
 * (AABB) вокруг центра: каждый тик глаз двигается по осям отдельно и упирается в коллизии блоков,
 * поэтому сквозь стены и землю не проходит, а скользит вдоль них.
 */
public final class EyeManager {

    private static final String TAG = "eyesky_eye";
    private static final String HOLDER_TAG = "eyesky_holder";

    private enum State { SKY, DESCEND, WATCH, RETURN }

    private final EyeSkyPlugin plugin;
    private final Random rnd = new Random();
    private final Map<Emotion, ItemStack> items = new EnumMap<>(Emotion.class);

    // ---- настройки ----
    private String worldName;
    private double size, half, viewRange, skyHeight, orbitRadius, skySpeed, visitSpeed, visitDistance, teleportFar;
    private int visitMin, visitMax, watchMin, watchMax, anchorSwitchSec;
    private boolean sounds;
    private boolean preload;
    private Laser laser;
    private Vector hold;      // если не null - глаз зависает в этой точке (лазер)

    // ---- состояние ----
    private BukkitTask task;
    private World world;
    private ItemDisplay display;
    private final List<ItemDisplay> holders = new java.util.ArrayList<>();   // "склад" текстур под бедроком
    private Vector pos;                       // центр глаза
    private final Vector vel = new Vector();
    private Vector target = new Vector();
    private State state = State.SKY;
    private long tick;
    private long stateSince;

    private UUID anchorId;
    private long nextAnchorSwitch;
    private double angle = Math.random() * Math.PI * 2;

    private UUID visitId;
    private long nextVisit;
    private long watchUntil;
    private double bestDist;
    private long lastProgress;

    private Emotion shown;
    private Emotion base = Emotion.NORMAL;
    private Emotion override;
    private long baseUntil, overrideUntil, blinkUntil, nextBlink, nextWatchEmotion;

    private long lastHit = -1000;
    private int hitCount;

    public EyeManager(EyeSkyPlugin plugin) {
        this.plugin = plugin;
    }

    // =====================================================================================
    //  Жизненный цикл
    // =====================================================================================

    public void start() {
        loadConfig();
        removeAllEyes();
        nextVisit = scheduleVisit();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        if (laser != null) { laser.abort(); laser = null; }
        hold = null;
        removeEntity();
        removeHolders();
    }

    public void reload() {
        loadConfig();
        removeEntity();   // пересоздастся с новыми размерами
        removeHolders();
        nextVisit = scheduleVisit();
    }

    private void loadConfig() {
        FileConfiguration c = plugin.getConfig();
        worldName = c.getString("eye.world", "");
        size = c.getDouble("eye.size", 6.0);
        half = c.getDouble("eye.collision-size", 4.0) / 2.0;
        viewRange = c.getDouble("eye.view-range", 4.0);
        skyHeight = c.getDouble("eye.sky-height", 55);
        orbitRadius = c.getDouble("eye.orbit-radius", 35);
        skySpeed = c.getDouble("eye.sky-speed", 0.5);
        visitSpeed = c.getDouble("eye.visit-speed", 0.4);
        visitDistance = c.getDouble("eye.visit-distance", 7.0);
        visitMin = c.getInt("eye.visit-min-seconds", 240);
        visitMax = Math.max(visitMin, c.getInt("eye.visit-max-seconds", 600));
        watchMin = c.getInt("eye.watch-min-seconds", 8);
        watchMax = Math.max(watchMin, c.getInt("eye.watch-max-seconds", 16));
        anchorSwitchSec = c.getInt("eye.anchor-switch-seconds", 120);
        teleportFar = c.getDouble("eye.teleport-if-farther-than", 160);
        sounds = c.getBoolean("eye.sounds", true);
        preload = c.getBoolean("eye.preload-under-bedrock", true);

        items.clear();
        for (Emotion e : Emotion.values()) {
            ItemStack item = new ItemStack(Material.PAPER);
            NamespacedKey key = Objects.requireNonNull(NamespacedKey.fromString(e.modelId()));
            item.editMeta(meta -> meta.setItemModel(key));
            items.put(e, item);
        }
    }

    private void removeAllEyes() {
        for (World w : Bukkit.getWorlds()) {
            for (Display d : w.getEntitiesByClass(Display.class)) {
                var tags = d.getScoreboardTags();
                if (tags.contains(TAG) || tags.contains(HOLDER_TAG) || tags.contains("eyesky_fx")) d.remove();
            }
        }
    }

    private void removeEntity() {
        if (display != null) display.remove();
        display = null;
        shown = null;
    }

    /** Удаляет любые лишние глаза с нашим тегом (кроме текущего). */
    private void removeDuplicateEyes() {
        for (ItemDisplay d : world.getEntitiesByClass(ItemDisplay.class)) {
            if (d != display && d.getScoreboardTags().contains(TAG)) d.remove();
        }
    }

    private void removeHolders() {
        for (ItemDisplay d : holders) if (d != null) d.remove();
        holders.clear();
    }

    /** По одному невидимому дисплею с каждой эмоцией на самом дне мира (внутри бедрока), чтобы игрок их не видел. */
    private void ensureHolders() {
        if (!preload || world == null) return;
        boolean ok = holders.size() == Emotion.values().length;
        for (ItemDisplay d : holders) if (d == null || !d.isValid()) ok = false;
        if (ok) return;
        Location spawn = world.getSpawnLocation();
        if (!world.isChunkLoaded(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4)) return;
        removeHolders();
        double y = world.getMinHeight() + 1.0;
        for (Emotion e : Emotion.values()) {
            Location loc = new Location(world, spawn.getBlockX() + 0.5, y, spawn.getBlockZ() + 0.5);
            holders.add(world.spawn(loc, ItemDisplay.class, d -> {
                d.setItemStack(items.get(e));
                d.setBillboard(Display.Billboard.CENTER);
                d.setTransformation(new Transformation(
                        new Vector3f(), new AxisAngle4f(), new Vector3f(0.1f, 0.1f, 0.1f), new AxisAngle4f()));
                d.setPersistent(false);
                d.setInvulnerable(true);
                d.setGravity(false);
                d.addScoreboardTag(HOLDER_TAG);
            }));
        }
    }

    private World resolveWorld() {
        if (worldName == null || worldName.isBlank()) return Bukkit.getWorlds().get(0);
        return Bukkit.getWorld(worldName);
    }

    // =====================================================================================
    //  Главный тик
    // =====================================================================================

    private void tick() {
        try {
            doTick();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Ошибка в тике глаза", t);
        }
    }

    private void doTick() {
        if (world == null || !Bukkit.getWorlds().contains(world)) {
            world = resolveWorld();
            if (world == null) return;
            pos = null;
            display = null;
        }
        List<Player> players = candidates();
        if (players.isEmpty()) return;      // никого нет - глаз "спит"

        tick++;
        if (tick % 100 == 1) {
            ensureHolders();
            removeDuplicateEyes();
        }
        Player anchor = pickAnchor(players);

        if (pos == null || !world.isChunkLoaded(floor(pos.getX()) >> 4, floor(pos.getZ()) >> 4)) {
            teleportToSky(anchor);
        }
        ensureEntity();
        if (display == null) return;

        behave(players, anchor);
        physics();
        emotions();
        display.teleport(new Location(world, pos.getX(), pos.getY(), pos.getZ()));
    }

    public List<Player> candidates() {
        if (world == null) return List.of();
        return world.getPlayers().stream()
                .filter(p -> p.getGameMode() != GameMode.SPECTATOR && !p.isDead())
                .toList();
    }

    // =====================================================================================
    //  Сущность
    // =====================================================================================

    private void ensureEntity() {
        if (display != null && display.isValid()) return;
        display = null;
        shown = null;
        int cx = floor(pos.getX()) >> 4, cz = floor(pos.getZ()) >> 4;
        if (!world.isChunkLoaded(cx, cz)) return;

        Location loc = new Location(world, pos.getX(), pos.getY(), pos.getZ());
        display = world.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(items.get(Emotion.NORMAL));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTransformation(new Transformation(
                    new Vector3f(), new AxisAngle4f(),
                    new Vector3f((float) size, (float) size, (float) size), new AxisAngle4f()));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange((float) viewRange);
            d.setShadowRadius(0f);
            d.setTeleportDuration(2);
            d.setPersistent(false);
            d.setInvulnerable(true);
            d.setGravity(false);
            d.addScoreboardTag(TAG);
        });
        shown = Emotion.NORMAL;
    }

    // =====================================================================================
    //  Поведение
    // =====================================================================================

    private Player pickAnchor(List<Player> players) {
        Player current = null;
        if (anchorId != null) {
            for (Player p : players) if (p.getUniqueId().equals(anchorId)) current = p;
        }
        boolean timeToSwitch = tick >= nextAnchorSwitch && state == State.SKY && laser == null;
        if (current == null || timeToSwitch) {
            Player chosen = players.get(rnd.nextInt(players.size()));
            if (players.size() > 1 && current != null) {
                while (chosen == current) chosen = players.get(rnd.nextInt(players.size()));
            }
            current = chosen;
            anchorId = chosen.getUniqueId();
            nextAnchorSwitch = tick + anchorSwitchSec * 20L + rnd.nextInt(400);
            if (pos != null && horizontalDistance(pos, chosen.getLocation()) > teleportFar) {
                teleportToSky(chosen);
            }
        }
        return current;
    }

    private void behave(List<Player> players, Player anchor) {
        if (hold != null) {            // лазер: стоим/летим в заданную точку, обычное поведение выключено
            target = hold.clone();
            return;
        }
        switch (state) {
            case SKY -> {
                angle += 0.006;
                target = skyPoint(anchor);
                if (tick >= nextVisit) startVisit(players);
            }
            case DESCEND -> {
                Player p = findPlayer(players, visitId);
                if (p == null) { startReturn(Emotion.SAD); target = skyPoint(anchor); return; }
                target = visitPoint(p);
                double dist = pos.distance(target);
                if (dist < bestDist - 0.5) { bestDist = dist; lastProgress = tick; }
                if (dist < 2.5) {
                    setState(State.WATCH);
                    watchUntil = tick + (watchMin + rnd.nextInt(watchMax - watchMin + 1)) * 20L;
                    nextWatchEmotion = tick;
                    playSound(Sound.ENTITY_PHANTOM_AMBIENT, 1.0f, 1.4f);
                } else if (tick - lastProgress > 120) {
                    startReturn(Emotion.SAD);       // не смог пробиться (крыша, стены)
                }
            }
            case WATCH -> {
                Player p = findPlayer(players, visitId);
                if (p == null || tick >= watchUntil) {
                    startReturn(Emotion.HAPPY);
                    target = skyPoint(anchor);
                    return;
                }
                target = visitPoint(p);
                if (tick >= nextWatchEmotion) {
                    int r = rnd.nextInt(100);
                    Emotion e = r < 45 ? Emotion.HAPPY : r < 75 ? Emotion.SURPRISED : r < 90 ? Emotion.NORMAL : Emotion.ANGRY;
                    setOverride(e, 50 + rnd.nextInt(40));
                    nextWatchEmotion = tick + 70 + rnd.nextInt(60);
                }
            }
            case RETURN -> {
                Vector sky = skyPoint(anchor);
                // сначала поднимаемся вертикально, чтобы не волочиться вдоль стен
                target = pos.getY() < sky.getY() - 15 ? new Vector(pos.getX(), sky.getY(), pos.getZ()) : sky;
                if (pos.distance(sky) < 10) {
                    setState(State.SKY);
                } else if (tick - stateSince > 400) {
                    teleportToSky(anchor);          // застрял под крышей - уходит "вверх" сам
                }
            }
        }
    }

    private void setState(State s) {
        state = s;
        stateSince = tick;
    }

    private void startVisit(List<Player> players) {
        Player p = players.get(rnd.nextInt(players.size()));
        beginVisit(p);
    }

    private void beginVisit(Player p) {
        visitId = p.getUniqueId();
        anchorId = visitId;
        setState(State.DESCEND);
        bestDist = Double.MAX_VALUE;
        lastProgress = tick;
        override = null;
        base = Emotion.NORMAL;
        baseUntil = tick + 600;
        nextVisit = scheduleVisit();
        playSound(Sound.ENTITY_PHANTOM_AMBIENT, 1.2f, 0.7f);
    }

    private void startReturn(Emotion e) {
        setState(State.RETURN);
        setOverride(e, 60);
        nextVisit = scheduleVisit();
    }

    private long scheduleVisit() {
        return tick + (visitMin + rnd.nextInt(visitMax - visitMin + 1)) * 20L;
    }

    private Vector skyPoint(Player a) {
        Location l = a.getLocation();
        int surface = world.getHighestBlockYAt(l);
        double y = Math.min(surface + skyHeight, world.getMaxHeight() - half - 3);
        y += Math.sin(tick * 0.03) * 2.0;
        double x = l.getX() + Math.cos(angle) * orbitRadius;
        double z = l.getZ() + Math.sin(angle) * orbitRadius;
        if (!world.isChunkLoaded(floor(x) >> 4, floor(z) >> 4)) {
            x = l.getX();
            z = l.getZ();
        }
        return new Vector(x, y, z);
    }

    private Vector visitPoint(Player p) {
        Location l = p.getLocation();
        Vector dir = l.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(1, 0, 0);
        dir.normalize();
        return new Vector(
                l.getX() + dir.getX() * visitDistance,
                l.getY() + 3.0 + Math.sin(tick * 0.08) * 0.4,
                l.getZ() + dir.getZ() * visitDistance);
    }

    private void teleportToSky(Player anchor) {
        angle = rnd.nextDouble() * Math.PI * 2;
        pos = skyPoint(anchor);
        vel.zero();
        if (state != State.SKY) setState(State.SKY);
    }

    private static Player findPlayer(List<Player> players, UUID id) {
        if (id == null) return null;
        for (Player p : players) if (p.getUniqueId().equals(id)) return p;
        return null;
    }

    // =====================================================================================
    //  Физика: движение по осям с упором в блоки
    // =====================================================================================

    private void physics() {
        // если вдруг оказались внутри блока (поставили блок на глаз) - выдавливаем вверх
        for (int i = 0; i < 8 && collides(pos.getX(), pos.getY(), pos.getZ(), false); i++) {
            pos.setY(pos.getY() + 0.5);
        }

        Vector toTarget = target.clone().subtract(pos);
        double dist = toTarget.length();
        double maxSpeed = (state == State.DESCEND || state == State.WATCH) ? visitSpeed : skySpeed;
        double speed = Math.min(maxSpeed, dist * 0.08);
        Vector desired = dist > 1e-6 ? toTarget.multiply(speed / dist) : new Vector();
        vel.add(desired.subtract(vel).multiply(0.1));

        double impact = 0;
        double[] v = {vel.getX(), vel.getY(), vel.getZ()};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(v[axis]) < 1e-7) continue;
            double moved = moveAxis(axis, v[axis]);
            if (Math.abs(moved - v[axis]) > 1e-6) {        // упёрлись
                impact = Math.max(impact, Math.abs(v[axis]));
                v[axis] = -v[axis] * 0.25;                  // чуть отскакиваем
            }
        }
        vel.setX(v[0]).setY(v[1]).setZ(v[2]);

        if (impact > 0.15 && tick - lastHit > 40) {
            hitCount = (tick - lastHit < 240) ? hitCount + 1 : 1;
            lastHit = tick;
            setOverride(hitCount >= 3 ? Emotion.ANGRY : Emotion.SURPRISED, hitCount >= 3 ? 80 : 40);
            playSound(Sound.ENTITY_PHANTOM_HURT, 1.0f, 0.8f);
        }
    }

    /** Двигает центр вдоль одной оси; если мешает блок - подъезжает вплотную. Возвращает реальный сдвиг. */
    private double moveAxis(int axis, double delta) {
        if (!collidesShifted(axis, delta)) {
            shift(axis, delta);
            return delta;
        }
        double lo = 0, hi = 1;
        for (int i = 0; i < 6; i++) {
            double mid = (lo + hi) / 2;
            if (collidesShifted(axis, delta * mid)) hi = mid; else lo = mid;
        }
        shift(axis, delta * lo);
        return delta * lo;
    }

    private void shift(int axis, double d) {
        if (axis == 0) pos.setX(pos.getX() + d);
        else if (axis == 1) pos.setY(pos.getY() + d);
        else pos.setZ(pos.getZ() + d);
    }

    private boolean collidesShifted(int axis, double d) {
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (axis == 0) x += d; else if (axis == 1) y += d; else z += d;
        return collides(x, y, z, true);
    }

    /** Пересекается ли куб с коллизией блоков. Незагруженные чанки считаются твёрдыми (если unloadedSolid). */
    private boolean collides(double cx, double cy, double cz, boolean unloadedSolid) {
        BoundingBox box = new BoundingBox(cx - half, cy - half, cz - half, cx + half, cy + half, cz + half);
        int minX = floor(box.getMinX()), maxX = floor(box.getMaxX() - 1e-9);
        int minY = floor(box.getMinY()), maxY = floor(box.getMaxY() - 1e-9);
        int minZ = floor(box.getMinZ()), maxZ = floor(box.getMaxZ() - 1e-9);

        if (minY < world.getMinHeight() || maxY >= world.getMaxHeight()) return true;   // дно мира / потолок

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    if (unloadedSolid) return true;
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.isEmpty() || b.isPassable()) continue;
                    VoxelShape shape = b.getCollisionShape();
                    if (shape.overlaps(box.clone().shift(-x, -y, -z))) return true;
                }
            }
        }
        return false;
    }

    // =====================================================================================
    //  Эмоции
    // =====================================================================================

    private void emotions() {
        if (tick >= nextBlink) {
            blinkUntil = tick + 4;
            nextBlink = tick + 50 + rnd.nextInt(90);
        }
        if (override != null && tick >= overrideUntil) override = null;
        if (tick >= baseUntil) {
            int r = rnd.nextInt(100);
            base = r < 55 ? Emotion.NORMAL : r < 75 ? Emotion.SLEEPY : r < 92 ? Emotion.HAPPY : Emotion.SAD;
            baseUntil = tick + 200 + rnd.nextInt(300);
        }

        Emotion e;
        if (override != null) e = override;
        else if (tick < blinkUntil && blinkable(base)) e = Emotion.BLINK;
        else e = base;

        if (e != shown) {
            shown = e;
            display.setItemStack(items.get(e));
        }
    }

    private static boolean blinkable(Emotion e) {
        return e != Emotion.SLEEPY && e != Emotion.HAPPY && e != Emotion.BLINK;
    }

    private void setOverride(Emotion e, int ticks) {
        override = e;
        overrideUntil = tick + ticks;
    }

    private void playSound(Sound sound, float volume, float pitch) {
        if (!sounds || pos == null) return;
        world.playSound(new Location(world, pos.getX(), pos.getY(), pos.getZ()), sound, SoundCategory.AMBIENT, volume, pitch);
    }

    // =====================================================================================
    //  API для команд
    // =====================================================================================

    public boolean forceVisit(Player p) {
        if (world == null || !p.getWorld().equals(world) || pos == null) return false;
        beginVisit(p);
        return true;
    }

    public void forceEmotion(Emotion e, int seconds) {
        setOverride(e, seconds * 20);
    }

    // ---- API для лазера ----

    public boolean startLaser(Player at) {
        return startLaser(at, null);
    }

    /** at - подлететь к игроку, point - подлететь к точке; оба null - бить вниз оттуда, где глаз сейчас. */
    public boolean startLaser(Player at, Location point) {
        if (laser != null || pos == null || world == null) return false;
        if (point != null && !point.getWorld().equals(world)) return false;
        Laser l = new Laser(plugin, this, at, point);
        laser = l;
        if (!l.start()) { laser = null; hold = null; return false; }
        return true;
    }

    public boolean laserBusy() { return laser != null; }

    void laserEnded(Laser l) { if (laser == l) laser = null; }

    World world() { return world; }

    Vector position() { return pos == null ? null : pos.clone(); }

    void setHold(Vector v) { hold = v; }

    double eyeSize() { return size; }

    void forceEmotionTicks(Emotion e, int ticks) { setOverride(e, ticks); }

    /** Масштаб (множитель) и дрожь картинки глаза. */
    void visual(double mult, double shake) {
        if (display == null || !display.isValid()) return;
        float s = (float) (size * mult);
        Vector3f tr = new Vector3f((float) ((rnd.nextDouble() - 0.5) * shake), (float) ((rnd.nextDouble() - 0.5) * shake), 0f);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTransformation(new Transformation(tr, new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f()));
    }

    public boolean summon(Player p) {
        if (world == null || !p.getWorld().equals(world)) return false;
        anchorId = p.getUniqueId();
        teleportToSky(p);
        return true;
    }

    public String describe() {
        if (pos == null) return "глаз ещё не появился (на сервере нет подходящих игроков)";
        return String.format("состояние=%s, эмоция=%s, позиция=%.0f %.0f %.0f (%s)",
                state, shown, pos.getX(), pos.getY(), pos.getZ(), world.getName());
    }

    // =====================================================================================

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    private static double horizontalDistance(Vector v, Location l) {
        double dx = v.getX() - l.getX(), dz = v.getZ() - l.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
