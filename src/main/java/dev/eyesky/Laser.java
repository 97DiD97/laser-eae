package dev.eyesky;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.logging.Level;

/**
 * Лазер глаза: (необязательно) подлёт к игроку -> зарядка с отсчётом -> луч вниз и выжигание воронки -> затухание.
 * Всё выполняется одним таймером в основном потоке сервера.
 */
public final class Laser {

    private enum Phase { APPROACH, CHARGE, FIRE, FADE }

    private static final String TAG = "eyesky_fx";    // метка эффектов лазера (чистятся при старте плагина)

    private final EyeSkyPlugin plugin;
    private final EyeManager eye;
    private final Player target;                       // подлететь к игроку (может быть null)
    private final Location point;                      // подлететь к точке на земле (может быть null)
    private final Random rnd = new Random();

    // настройки
    private final int chargeTicks, fireTicks;
    private final double radius, damage, beamWidth, liningThickness;
    private final List<Material> lining = new ArrayList<>();
    private final boolean breakBlocks;
    private static final int FADE_TICKS = 50;

    private BukkitTask task;
    private World world;
    private Phase phase = Phase.APPROACH;
    private int t;

    private Vector eyePos = new Vector();
    private Location impact;                           // верхняя точка земли под глазом
    private double craterDone;                         // до какого радиуса воронка уже выжжена
    private int cx, cy, cz;                            // центр воронки (блок)

    private BlockDisplay core, mid, outer;
    private TextDisplay counter;
    private ItemDisplay eyeGlow, impactGlow;
    private final List<Entity> spawned = new ArrayList<>();

    public Laser(EyeSkyPlugin plugin, EyeManager eye, Player target, Location point) {
        this.plugin = plugin;
        this.eye = eye;
        this.target = target;
        this.point = point;
        FileConfiguration c = plugin.getConfig();
        chargeTicks = Math.max(20, c.getInt("laser.charge-seconds", 5) * 20);
        fireTicks = Math.max(20, c.getInt("laser.fire-seconds", 4) * 20);
        radius = Math.max(2.0, c.getDouble("laser.radius", 7.0));
        damage = c.getDouble("laser.damage", 10.0);
        beamWidth = Math.max(0.5, c.getDouble("laser.beam-width", 1.8));
        breakBlocks = c.getBoolean("laser.break-blocks", true);
        liningThickness = Math.max(0.5, c.getDouble("laser.lining-thickness", 3.0));
        List<String> names = c.getStringList("laser.crater-blocks");
        if (names.isEmpty()) {
            names = List.of("BLACKSTONE", "BLACKSTONE", "MAGMA_BLOCK", "MAGMA_BLOCK", "COAL_BLOCK",
                    "SMOOTH_BASALT", "OBSIDIAN", "CRYING_OBSIDIAN", "NETHERRACK");
        }
        for (String n : names) {
            Material m = Material.matchMaterial(n.trim());
            if (m != null && m.isBlock()) lining.add(m);
            else plugin.getLogger().warning("laser.crater-blocks: неизвестный блок " + n);
        }
        if (lining.isEmpty()) lining.add(Material.BLACKSTONE);
    }

    // =====================================================================================

    public boolean start() {
        world = eye.world();
        Vector p = eye.position();
        if (world == null || p == null) return false;
        eyePos = p;
        if (target == null && point == null) {
            if (!computeImpact()) return false;
            beginCharge();
            t = 0;
        } else {
            phase = Phase.APPROACH;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        return true;
    }

    /** Досрочная остановка (выключение плагина). */
    public void abort() {
        if (task != null) task.cancel();
        task = null;
        cleanup();
    }

    private void finish() {
        if (task != null) task.cancel();
        task = null;
        cleanup();
        eye.laserEnded(this);
    }

    private void cleanup() {
        for (Entity e : spawned) if (e != null) e.remove();
        spawned.clear();
        eye.setHold(null);
        eye.visual(1.0, 0.0);
    }

    public String phaseName() {
        return phase + " t=" + t;
    }

    private void log(String s) {
        plugin.getLogger().info("[лазер] " + s);
    }

    private int totalTicks;

    private void tick() {
        try {
            if (++totalTicks > 400 + chargeTicks + fireTicks + FADE_TICKS + 200) {   // страховка от зависания
                log("таймаут, лазер остановлен");
                finish();
                return;
            }
            Vector p = eye.position();
            if (p == null || eye.world() != world) { finish(); return; }
            eyePos = p;
            switch (phase) {
                case APPROACH -> tickApproach();
                case CHARGE -> tickCharge();
                case FIRE -> tickFire();
                case FADE -> tickFade();
            }
            t++;
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Ошибка в лазере глаза", ex);
            finish();
        }
    }

    // =====================================================================================
    //  1. Подлёт к игроку
    // =====================================================================================

    private void tickApproach() {
        Location l;
        if (point != null) {
            l = point;
        } else {
            if (target == null || !target.isOnline() || !target.getWorld().equals(world)) { finish(); return; }
            l = target.getLocation();
        }
        int surface = world.getHighestBlockYAt(l);
        double y = Math.max(eyePos.getY(), surface + 40);
        y = Math.min(y, world.getMaxHeight() - 8);
        eye.setHold(new Vector(l.getX(), y, l.getZ()));
        eye.forceEmotionTicks(Emotion.NORMAL, 3);
        double dx = l.getX() - eyePos.getX(), dz = l.getZ() - eyePos.getZ();
        double reach = point != null ? 0.7 : 2.5;
        if ((Math.hypot(dx, dz) < reach && Math.abs(y - eyePos.getY()) < 3) || t > 400) {
            if (!computeImpact()) { finish(); return; }
            beginCharge();
        }
    }

    // =====================================================================================
    //  2. Зарядка с отсчётом
    // =====================================================================================

    private void beginCharge() {
        phase = Phase.CHARGE;
        log(String.format("заряд, глаз в %.0f %.0f %.0f, земля на y=%.1f", eyePos.getX(), eyePos.getY(), eyePos.getZ(), impact.getY()));
        t = -1;                                   // tick() сразу прибавит единицу
        eye.setHold(point != null ? new Vector(point.getX(), eyePos.getY(), point.getZ()) : eyePos.clone());   // зависает на месте

        counter = world.spawn(at(eyePos.getX(), eyePos.getY() - eye.eyeSize() * 0.5 - 1.0, eyePos.getZ()),
                TextDisplay.class, d -> {
                    d.text(Component.text(String.valueOf((chargeTicks + 19) / 20), NamedTextColor.YELLOW));
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    d.setSeeThrough(true);
                    d.setShadowed(true);
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.setViewRange(6f);
                    d.setPersistent(false);
                    d.setInvulnerable(true);
                    d.setGravity(false);
                    d.setTransformation(scaleOnly(10f));
                    d.addScoreboardTag(TAG);
                });
        spawned.add(counter);

        eyeGlow = glowDisplay(eyePos.getX(), eyePos.getY(), eyePos.getZ());
        eyeGlow.setTransformation(scaleOnly(0.01f));

        sound(eyeLoc(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 8f, 1.0f);
        sound(eyeLoc(), Sound.BLOCK_BEACON_POWER_SELECT, 8f, 0.6f);
    }

    private void tickCharge() {
        if (t >= chargeTicks) { beginFire(); return; }
        double p = (double) t / chargeTicks;                       // 0..1
        int left = chargeTicks - t;
        boolean lastSecond = left <= 20;

        // --- глаз: эмоция, пульсация и дрожь
        eye.forceEmotionTicks(lastSecond ? Emotion.SURPRISED : Emotion.ANGRY, 3);
        double pulse = 1.0 + 0.12 * p + 0.05 * Math.sin(t * (0.35 + p * 1.4));
        eye.visual(pulse, 0.05 + 0.35 * p);

        // --- свечение вокруг зрачка
        double gs = eye.eyeSize() * (0.5 + 1.7 * p) * (1.0 + 0.08 * Math.sin(t * 0.9));
        eyeGlow.teleport(eyeLoc());
        eyeGlow.setInterpolationDelay(0);
        eyeGlow.setInterpolationDuration(1);
        eyeGlow.setTransformation(scaleOnly((float) gs));

        // --- отсчёт
        counter.teleport(at(eyePos.getX(), eyePos.getY() - eye.eyeSize() * 0.5 - 1.0, eyePos.getZ()));
        if (t % 20 == 0) {
            int sec = (left + 19) / 20;
            NamedTextColor col = sec <= 1 ? NamedTextColor.RED : sec <= 3 ? NamedTextColor.GOLD : NamedTextColor.YELLOW;
            counter.text(Component.text(String.valueOf(sec), col));
            sound(eyeLoc(), Sound.BLOCK_NOTE_BLOCK_PLING, 6f, (float) (0.6 + 1.2 * p));
            sound(eyeLoc(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 6f, 0.7f);
        }
        if (lastSecond && t % 5 == 0) sound(eyeLoc(), Sound.BLOCK_NOTE_BLOCK_PLING, 6f, 2.0f);
        if (t % 10 == 0) actionBar(Component.text("⚠ ЛАЗЕР ЧЕРЕЗ " + ((left + 19) / 20) + " ⚠", TextColor.color(0xFF3030)));
        if (t % 10 == 0) computeImpact();

        // --- частицы: энергия стягивается в глаз
        int n = 4 + (int) (26 * p);
        for (int i = 0; i < n; i++) {
            Vector u = randomUnit();
            double dist = 4 + rnd.nextDouble() * 5;
            double x = eyePos.getX() + u.getX() * dist, y = eyePos.getY() + u.getY() * dist, z = eyePos.getZ() + u.getZ() * dist;
            world.spawnParticle(Particle.END_ROD, x, y, z, 0, -u.getX(), -u.getY(), -u.getZ(), 0.35 + rnd.nextDouble() * 0.2, null, true);
        }
        if (t % 3 == 0) {
            for (int i = 0; i < 3; i++) {
                Vector u = randomUnit();
                world.spawnParticle(Particle.ELECTRIC_SPARK, eyePos.getX() + u.getX() * 3, eyePos.getY() + u.getY() * 3,
                        eyePos.getZ() + u.getZ() * 3, 0, u.getX(), u.getY(), u.getZ(), 0.6, null, true);
            }
        }

        // --- предупреждение на земле: два кольца (внешнее и сжимающееся) и тонкая линия вниз
        if (t % 2 == 0) {
            ring(radius, 0.25, new Particle.DustOptions(Color.fromRGB(255, 40, 20), 2.2f), 56);
            ring(radius * (1.0 - p), 0.25, new Particle.DustOptions(Color.fromRGB(255, 200, 40), 1.6f), 40);
        }
        if (t % 4 == 0) {
            double top = eyePos.getY(), bottom = impact.getY();
            for (double y = bottom; y < top; y += 2.0) {
                world.spawnParticle(Particle.DUST, impact.getX(), y, impact.getZ(), 1, 0.1, 0.1, 0.1, 0,
                        new Particle.DustOptions(Color.fromRGB(255, 120, 30), 1.4f), true);
            }
        }
    }

    // =====================================================================================
    //  3. Выстрел
    // =====================================================================================

    private void beginFire() {
        phase = Phase.FIRE;
        log("выстрел");
        t = -1;
        computeImpact();
        cx = impact.getBlockX();
        cy = impact.getBlockY() - 1;              // блок, на который упал луч
        cz = impact.getBlockZ();
        craterDone = 0;

        core = beamBlock(Material.WHITE_CONCRETE);
        mid = beamBlock(Material.YELLOW_STAINED_GLASS);
        outer = beamBlock(Material.RED_STAINED_GLASS);

        impactGlow = glowDisplay(impact.getX(), impact.getY() + 1.0, impact.getZ());
        impactGlow.setTransformation(scaleOnly(0.01f));

        Location eL = eyeLoc();
        sound(eL, Sound.ENTITY_WARDEN_SONIC_BOOM, 10f, 0.8f);
        sound(eL, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 10f, 0.6f);
        sound(impact, Sound.ENTITY_GENERIC_EXPLODE, 10f, 0.5f);
        sound(impact, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 8f, 0.6f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, impact, 3, 1.5, 0.5, 1.5, 0, null, true);
        world.spawnParticle(Particle.SONIC_BOOM, eL, 1, 0, 0, 0, 0, null, true);
        eye.visual(1.25, 0.0);
    }

    private void tickFire() {
        if (t >= fireTicks) { beginFade(); return; }
        double p = (double) t / fireTicks;

        eye.forceEmotionTicks(Emotion.SURPRISED, 3);
        eye.visual(1.2 + 0.05 * Math.sin(t * 1.3), 0.12);

        // --- луч
        double grow = Math.min(1.0, (t + 1) / 4.0);                   // быстро "выстреливает"
        double flick = 1.0 + 0.12 * Math.sin(t * 1.7) + (rnd.nextDouble() - 0.5) * 0.1;
        updateBeam(grow * flick);

        // --- свечения
        double gs = eye.eyeSize() * 2.1 * (1.0 + 0.1 * Math.sin(t * 1.1));
        eyeGlow.teleport(eyeLoc());
        eyeGlow.setInterpolationDelay(0);
        eyeGlow.setInterpolationDuration(1);
        eyeGlow.setTransformation(scaleOnly((float) gs));

        double is = radius * 2.0 * Math.min(1.0, (t + 1) / 6.0) * (1.0 + 0.1 * Math.sin(t * 1.4));
        impactGlow.setInterpolationDelay(0);
        impactGlow.setInterpolationDuration(1);
        impactGlow.setTransformation(scaleOnly((float) is));

        // --- воронка растёт: быстро в начале, медленнее к концу
        double r = radius * (1.0 - Math.pow(1.0 - p, 2.2));
        if (breakBlocks) carve(craterDone, r);
        craterDone = Math.max(craterDone, r);

        // --- урон
        if (t % 10 == 0) hurt(Math.max(r, 2.0));

        // --- эффекты вдоль луча и в точке попадания
        double top = eyePos.getY(), bottom = impact.getY();
        for (int i = 0; i < 14; i++) {
            double y = bottom + rnd.nextDouble() * (top - bottom);
            double a = rnd.nextDouble() * Math.PI * 2, rr = beamWidth * (0.6 + rnd.nextDouble() * 0.6);
            world.spawnParticle(Particle.ELECTRIC_SPARK, impact.getX() + Math.cos(a) * rr, y, impact.getZ() + Math.sin(a) * rr,
                    0, Math.cos(a), -0.2, Math.sin(a), 0.5, null, true);
        }
        for (int i = 0; i < 4; i++) {
            double y = bottom + rnd.nextDouble() * (top - bottom);
            world.spawnParticle(Particle.END_ROD, impact.getX(), y, impact.getZ(), 0, rnd.nextGaussian() * 0.2, -1, rnd.nextGaussian() * 0.2, 1.2, null, true);
        }
        Location ig = impact.clone().add(0, 0.5, 0);
        world.spawnParticle(Particle.FLAME, ig, 18, r * 0.35 + 0.5, 0.3, r * 0.35 + 0.5, 0.25, null, true);
        world.spawnParticle(Particle.LAVA, ig, 6, r * 0.3 + 0.5, 0.3, r * 0.3 + 0.5, 0, null, true);
        world.spawnParticle(Particle.LARGE_SMOKE, ig, 8, r * 0.4 + 0.5, 0.4, r * 0.4 + 0.5, 0.12, null, true);
        if (rnd.nextInt(4) == 0) {
            Location e = ig.clone().add((rnd.nextDouble() - 0.5) * r * 1.6, 0, (rnd.nextDouble() - 0.5) * r * 1.6);
            world.spawnParticle(Particle.EXPLOSION, e, 1, 0, 0, 0, 0, null, true);
            sound(e, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.7f + rnd.nextFloat() * 0.4f);
        }
        // расходящееся ударное кольцо
        if (t % 4 == 0) {
            double sw = (t % 24) / 24.0 * radius * 1.3;
            ring(sw, 0.4, new Particle.DustOptions(Color.fromRGB(255, 160, 60), 2.5f), 50);
        }
        if (t % 8 == 0) sound(impact, Sound.BLOCK_BEACON_AMBIENT, 8f, 0.5f);
        if (t % 5 == 0) sound(eyeLoc(), Sound.ENTITY_BLAZE_SHOOT, 6f, 0.5f + rnd.nextFloat() * 0.3f);
        if (t % 10 == 0) actionBar(Component.text("☠ ЛАЗЕР ☠", TextColor.color(0xFF2020)));
    }

    // =====================================================================================
    //  4. Затухание
    // =====================================================================================

    private void beginFade() {
        phase = Phase.FADE;
        log("затухание");
        t = -1;
        if (breakBlocks) scorchRim();
        sound(impact, Sound.BLOCK_FIRE_EXTINGUISH, 8f, 0.6f);
        eye.forceEmotionTicks(Emotion.SLEEPY, 100);       // устал после выстрела
    }

    private void tickFade() {
        if (t >= FADE_TICKS) { finish(); return; }
        double p = (double) t / FADE_TICKS;
        double k = Math.max(0.0, 1.0 - t / 10.0);          // луч гаснет за 10 тиков
        if (core != null) updateBeam(k);
        if (k <= 0 && core != null) {
            core.remove(); mid.remove(); outer.remove();
            spawned.remove(core); spawned.remove(mid); spawned.remove(outer);
            core = null;
        }
        eye.visual(1.0 + 0.2 * (1 - p), 0.0);

        double gs = eye.eyeSize() * 2.1 * (1 - p);
        eyeGlow.teleport(eyeLoc());
        eyeGlow.setInterpolationDelay(0);
        eyeGlow.setInterpolationDuration(1);
        eyeGlow.setTransformation(scaleOnly((float) Math.max(0.01, gs)));
        impactGlow.setInterpolationDelay(0);
        impactGlow.setInterpolationDuration(1);
        impactGlow.setTransformation(scaleOnly((float) Math.max(0.01, radius * 2.0 * (1 - p))));

        // дымящаяся воронка
        for (int i = 0; i < 3; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, d = rnd.nextDouble() * radius * 0.9;
            Location l = impact.clone().add(Math.cos(a) * d, 0.3 - rnd.nextDouble() * 2.5, Math.sin(a) * d);
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, l, 0, 0, 1, 0, 0.06, null, true);
        }
        if (t % 4 == 0) {
            Location l = impact.clone().add((rnd.nextDouble() - 0.5) * radius, 0.2, (rnd.nextDouble() - 0.5) * radius);
            world.spawnParticle(Particle.LAVA, l, 2, 0.3, 0.1, 0.3, 0, null, true);
        }
    }

    // =====================================================================================
    //  Луч
    // =====================================================================================

    private BlockDisplay beamBlock(Material m) {
        BlockDisplay d = world.spawn(at(impact.getX(), impact.getY(), impact.getZ()), BlockDisplay.class, b -> {
            b.setBlock(m.createBlockData());
            b.setBrightness(new Display.Brightness(15, 15));
            b.setViewRange(8f);
            b.setShadowRadius(0f);
            b.setDisplayWidth(6f);
            b.setDisplayHeight((float) Math.max(10, eyePos.getY() - impact.getY() + 10));
            b.setPersistent(false);
            b.setInvulnerable(true);
            b.setGravity(false);
            b.addScoreboardTag(TAG);
        });
        spawned.add(d);
        return d;
    }

    private void updateBeam(double k) {
        double len = eyePos.getY() - impact.getY();
        if (len < 1) len = 1;
        setBeam(core, beamWidth * 0.35 * k, len);
        setBeam(mid, beamWidth * 0.65 * k, len);
        setBeam(outer, beamWidth * 1.0 * k, len);
    }

    private void setBeam(BlockDisplay d, double w, double len) {
        if (d == null) return;
        float fw = (float) Math.max(0.01, w);
        d.setDisplayHeight((float) len + 2f);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(1);
        d.setTransformation(new Transformation(
                new Vector3f(-fw / 2f, 0f, -fw / 2f), new AxisAngle4f(),
                new Vector3f(fw, (float) len, fw), new AxisAngle4f()));
    }

    // =====================================================================================
    //  Воронка
    // =====================================================================================

    /** Выжигает шаровой слой между радиусами from и to (сплюснутый по вертикали - получается чаша). */
    private void carve(double from, double to) {
        if (to <= from) return;
        int R = (int) Math.ceil(to);
        final double squash = 1.35;
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                for (int dy = -R; dy <= R; dy++) {
                    double sy = dy * squash;
                    double d = Math.sqrt(dx * dx + dz * dz + sy * sy);
                    if (d > to || d <= from) continue;
                    Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!burnable(b)) continue;
                    if (rnd.nextInt(7) == 0) {
                        world.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 3, 0.3, 0.3, 0.3, 0.05,
                                b.getBlockData(), true);
                    }
                    b.setType(Material.AIR, false);
                }
            }
        }
    }

    /** Стенки и дно воронки заменяются обугленными блоками (магма, чёрный камень и т.д. - список в config.yml). */
    private void scorchRim() {
        int R = (int) Math.ceil(radius + liningThickness + 1);
        final double squash = 1.35;
        for (int dx = -R; dx <= R; dx++) {
            for (int dz = -R; dz <= R; dz++) {
                for (int dy = -R; dy <= 2; dy++) {
                    double sy = dy * squash;
                    double d = Math.sqrt(dx * dx + dz * dz + sy * sy);
                    if (d <= radius || d > radius + liningThickness) continue;
                    // чем дальше от ямы, тем реже заменяем - край получается рваным
                    double chance = 0.95 - 0.55 * ((d - radius) / liningThickness);
                    if (rnd.nextDouble() > chance) continue;
                    Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!burnable(b) || !b.getType().isSolid()) continue;
                    b.setType(lining.get(rnd.nextInt(lining.size())), false);
                }
            }
        }
    }

    private boolean burnable(Block b) {
        Material m = b.getType();
        if (m.isAir()) return false;
        if (m.getHardness() < 0) return false;           // бедрок, барьеры, командные блоки
        if (m == Material.WATER || m == Material.LAVA) return false;
        return !(b.getState() instanceof TileState);      // сундуки, таблички, спавнеры - не трогаем
    }

    private void hurt(double r) {
        if (damage <= 0) return;
        Location c = impact.clone();
        double top = eyePos.getY();
        for (Entity e : world.getNearbyEntities(c, radius + 2, top - impact.getY() + 2, radius + 2)) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le instanceof Player pl && (pl.getGameMode() == GameMode.SPECTATOR || pl.getGameMode() == GameMode.CREATIVE)) continue;
            Location l = le.getLocation();
            double h = Math.hypot(l.getX() - impact.getX(), l.getZ() - impact.getZ());
            boolean inBeam = h <= beamWidth + 0.6 && l.getY() >= impact.getY() - 3;
            boolean inCrater = h <= r + 0.5 && Math.abs(l.getY() - impact.getY()) <= r;
            if (!inBeam && !inCrater) continue;
            le.damage(damage);
            le.setFireTicks(120);
            Vector push = new Vector(l.getX() - impact.getX(), 0, l.getZ() - impact.getZ());
            push = push.lengthSquared() < 1e-4 ? new Vector(0, 0, 0) : push.normalize().multiply(0.6);
            le.setVelocity(le.getVelocity().add(push.setY(0.5)));
        }
    }

    // =====================================================================================
    //  Вспомогательное
    // =====================================================================================

    /** Куда упадёт луч: прямо вниз от глаза до первого блока. */
    private boolean computeImpact() {
        double rx = point != null ? point.getX() : eyePos.getX();
        double rz = point != null ? point.getZ() : eyePos.getZ();
        Location from = new Location(world, rx, eyePos.getY(), rz);
        RayTraceResult res = world.rayTraceBlocks(from, new Vector(0, -1, 0), 700, FluidCollisionMode.NEVER, true);
        if (res == null || res.getHitPosition() == null) return false;
        Vector h = res.getHitPosition();
        impact = new Location(world, rx, h.getY(), rz);
        return true;
    }

    private Location eyeLoc() {
        return new Location(world, eyePos.getX(), eyePos.getY(), eyePos.getZ());
    }

    private Location at(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    private static Transformation scaleOnly(float s) {
        return new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f());
    }

    private ItemDisplay glowDisplay(double x, double y, double z) {
        ItemDisplay d = world.spawn(at(x, y, z), ItemDisplay.class, g -> {
            ItemStack item = new ItemStack(Material.PAPER);
            NamespacedKey key = Objects.requireNonNull(NamespacedKey.fromString("eyesky:7"));   // кадр 8 листа - свечение
            item.editMeta(meta -> meta.setItemModel(key));
            g.setItemStack(item);
            g.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            g.setBillboard(Display.Billboard.CENTER);
            g.setBrightness(new Display.Brightness(15, 15));
            g.setViewRange(8f);
            g.setShadowRadius(0f);
            g.setPersistent(false);
            g.setInvulnerable(true);
            g.setGravity(false);
            g.addScoreboardTag(TAG);
        });
        spawned.add(d);
        return d;
    }

    /** Кольцо частиц на земле. */
    private void ring(double r, double lift, Particle.DustOptions dust, int points) {
        if (r < 0.3) return;
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            world.spawnParticle(Particle.DUST, impact.getX() + Math.cos(a) * r, impact.getY() + lift, impact.getZ() + Math.sin(a) * r,
                    1, 0, 0, 0, 0, dust, true);
        }
    }

    private Vector randomUnit() {
        Vector v;
        do {
            v = new Vector(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian());
        } while (v.lengthSquared() < 1e-6);
        return v.normalize();
    }

    private void sound(Location l, Sound s, float volume, float pitch) {
        world.playSound(l, s, SoundCategory.AMBIENT, volume, pitch);
    }

    private void actionBar(Component c) {
        for (Player p : world.getPlayers()) {
            double dx = p.getLocation().getX() - eyePos.getX(), dz = p.getLocation().getZ() - eyePos.getZ();
            if (dx * dx + dz * dz < 150 * 150) p.sendActionBar(c);
        }
    }
}
