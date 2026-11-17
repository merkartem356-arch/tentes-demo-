package com.tentnets.net;

import com.tentnets.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Натянутая сеть. Одна сущность = одна сеть, проходящая через опорные точки (касается каждого блока,
 * по которому кликнули). Форма синхронизируется клиенту строкой (vanilla SynchedEntityData).
 *
 * Прочность {@link #MAX_HITS}. Если убрать блок, на котором держится сеть, она падает (выпадает предметом).
 * Антидроновая сеть ловит дроны/боеприпасы: они застревают в ней и через несколько секунд детонируют.
 * Через сеть нельзя пройти (коллизия), пули пролетают.
 */
public class NetEntity extends Entity {
    public enum Kind {
        ANTI("anti_drone_net"),
        MASK("mask_net");

        public final String id;

        Kind(String id) {
            this.id = id;
        }
    }

    /** Вариант сети: id совпадает с id предмета и именем текстуры. Порядок не менять (ordinal пишется в мир). */
    public enum Variant {
        ANTI_STEEL("anti_drone_net", Kind.ANTI),
        ANTI_BLACK("anti_drone_net_black", Kind.ANTI),
        ANTI_KHAKI("anti_drone_net_khaki", Kind.ANTI),
        MASK_FOREST("mask_net", Kind.MASK),
        MASK_DESERT("mask_net_desert", Kind.MASK),
        MASK_WINTER("mask_net_winter", Kind.MASK),
        MASK_URBAN("mask_net_urban", Kind.MASK),
        MASK_MARSH("mask_net_marsh", Kind.MASK),
        MASK_NIGHT("mask_net_night", Kind.MASK),
        MASK_AUTUMN("mask_net_autumn", Kind.MASK),
        MASK_DIGITAL("mask_net_digital", Kind.MASK);

        public final String id;
        public final Kind kind;

        Variant(String id, Kind kind) {
            this.id = id;
            this.kind = kind;
        }

        public static Variant byOrdinal(int i) {
            Variant[] all = values();
            return i >= 0 && i < all.length ? all[i] : ANTI_STEEL;
        }
    }

    /** Суммарный «вес» сущностей на сети, выше которого она начинает рваться (игрок ≈ 1, корова ≈ 1.3, голем ≈ 3.8). */
    public static final double LOAD_LIMIT = 7.0;
    /** Сколько единиц прочности снимает каждая секунда перегруза (сверх этого — по 1 за каждые 3 единицы лишнего веса). */
    private static final double LOAD_STEP = 3.0;
    /** Скорость падения (блоков/тик), при которой удар о сеть рвёт её (≈ падение с 12+ блоков). */
    private static final double HEAVY_FALL = -0.9;
    /** Единиц прочности за каждый блок/тик скорости транспорта (машина 0.5 б/тик = разрыв за один проезд). */
    private static final double VEHICLE_RIP = 30.0;
    /** Транспорт медленнее этого (блоков/тик) сеть не рвёт. */
    private static final double VEHICLE_MIN_SPEED = 0.03;

    /** Прочность сети (сколько попаданий выдерживает). */
    public static final int MAX_HITS = 10;
    /** Зазор между сетью и гранью блока (чтобы не мерцало), в блоках. */
    public static final double FLUSH = 0.012;
    /** Через сколько тиков застрявший взрывоопасный объект детонирует. */
    private static final int FUSE_TICKS = 60;
    /** В каком радиусе от сети ищем летящие объекты (блоков). Покрывает скорости до ~6 блоков/тик. */
    private static final double SCAN_RANGE = 6.0;

    // Мягкая сеть: прогиб под сущностями (пружина).
    private static final double DENT_K = 0.25;
    private static final double DENT_C = 0.18;
    /** Во сколько раз скорость падения превращается в начальную скорость прогиба (прыжок сверху = глубже). */
    private static final double DENT_IMPACT = 1.5;
    /** Доля скорости распрямления сети, которая подбрасывает сущность обратно. */
    private static final double DENT_BOUNCE = 0.55;

    private static final EntityDataAccessor<String> DATA_SHAPE =
            SynchedEntityData.defineId(NetEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_VARIANT =
            SynchedEntityData.defineId(NetEntity.class, EntityDataSerializers.INT);

    /** Вмятина от одной сущности (и клиент, и сервер считают её сами по позиции сущности). */
    private static final class DentState {
        Vec3 center;
        Vec3 dir;
        double depth;
        double vel;
        double radius = 1.0;
        boolean inContact;
    }

    /** Объект, застрявший в сети. */
    private static final class Stuck {
        final Vec3 pos;
        final boolean hadNoGravity;
        final NetRules.Action action;
        int timer;

        Stuck(Vec3 pos, boolean hadNoGravity, NetRules.Action action, int timer) {
            this.pos = pos;
            this.hadNoGravity = hadNoGravity;
            this.action = action;
            this.timer = timer;
        }
    }

    // ВАЖНО: shape и bounds без инициализаторов — makeBoundingBox() вызывается из конструктора Entity.
    private NetShape shape;
    private AABB bounds;
    private int hits = MAX_HITS;
    private int age;
    private final Map<Integer, Integer> recent = new HashMap<>();
    private final Map<Integer, Stuck> stuck = new HashMap<>();
    private final Map<Integer, DentState> dents = new HashMap<>();
    private NetShape.Mesh liveMesh;
    /** Суммарный вес сущностей, стоящих на сети (считает сервер). */
    private double load;

    public NetEntity(EntityType<? extends NetEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    // ---------------------------------------------------------------- данные

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SHAPE, "");
        builder.define(DATA_VARIANT, 0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_SHAPE.equals(key)) {
            rebuildShape();
        }
    }

    private void rebuildShape() {
        this.shape = NetShape.decode(this.entityData.get(DATA_SHAPE));
        if (this.shape != null) {
            // Запас под болтики и провисание, иначе сеть может пропасть из кадра раньше времени.
            this.bounds = this.shape.bounds.inflate(0.15).expandTowards(0.0, -this.shape.sagDepth, 0.0)
                    .inflate(NetShape.MAX_DENT);
            this.setBoundingBox(this.bounds);
        }
    }

    @Override
    protected AABB makeBoundingBox() {
        return this.bounds != null ? this.bounds : super.makeBoundingBox();
    }

    public void setNet(Variant variant, NetShape newShape) {
        this.entityData.set(DATA_VARIANT, variant.ordinal());
        this.entityData.set(DATA_SHAPE, newShape.encode());
        this.setPos(newShape.center);
    }

    public Variant getVariant() {
        return Variant.byOrdinal(this.entityData.get(DATA_VARIANT));
    }

    public Kind getKind() {
        return getVariant().kind;
    }

    public NetShape getShape() {
        return this.shape;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        int variant = tag.contains("Variant") ? tag.getInt("Variant")
                : (tag.getInt("Kind") == 1 ? Variant.MASK_FOREST.ordinal() : Variant.ANTI_STEEL.ordinal());
        this.entityData.set(DATA_VARIANT, variant);
        this.entityData.set(DATA_SHAPE, tag.getString("Shape"));
        this.hits = tag.contains("Hits") ? Math.min(tag.getInt("Hits"), MAX_HITS) : MAX_HITS;
        if (this.shape != null) {
            this.setPos(this.shape.center);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Variant", this.entityData.get(DATA_VARIANT));
        tag.putString("Shape", this.entityData.get(DATA_SHAPE));
        tag.putInt("Hits", this.hits);
    }

    // ---------------------------------------------------------------- логика

    @Override
    public void tick() {
        super.tick();

        Level level = this.level();
        if (this.shape == null) {
            if (!level.isClientSide) this.discard();
            return;
        }

        updateDents(level);

        if (level.isClientSide) {
            // Коллизия для самого игрока считается на клиенте (иначе будут рывки). Класс грузится только на клиенте.
            com.tentnets.client.ClientEvents.collideLocalPlayer(this);
            return;
        }
        if (!(level instanceof ServerLevel server)) return;
        this.age++;

        // «Физика»: сеть держится на блоках. Убрали опору — сеть падает.
        if (this.age % 10 == 0 && !isAnchored(server)) {
            collapse(server);
            return;
        }

        if (this.isRemoved()) return;

        // Перегруз: слишком много игроков/мобов на сети — она рвётся.
        if (this.age % 20 == 0 && this.load > LOAD_LIMIT) {
            Vec3 c = this.shape.center;
            server.playSound(null, c.x, c.y, c.z, SoundEvents.WOOL_HIT, SoundSource.BLOCKS, 1.2F, 0.5F);
            damageNet(c, 1 + (int) ((this.load - LOAD_LIMIT) / LOAD_STEP));
            if (this.isRemoved()) return;
        }

        // Коллизия для мобов (игроков двигает клиент).
        collideMobs(server);

        tickStuck(server);

        boolean anti = getKind() == Kind.ANTI;
        if (this.age % 100 == 0) {
            this.recent.values().removeIf(t -> this.age - t > 40);
        }

        List<Entity> candidates = level.getEntities(
                this,
                this.getBoundingBox().inflate(SCAN_RANGE),
                e -> !(e instanceof NetEntity) && !e.isRemoved() && !this.stuck.containsKey(e.getId())
                        && NetRules.classify(e) != NetRules.Action.PASS
        );

        for (Entity e : candidates) {
            if (e.isRemoved()) continue;

            NetRules.Action action = NetRules.classify(e);
            if (action == NetRules.Action.VEHICLE) {
                onVehicle(server, e);
                if (this.isRemoved()) break;
                continue;
            }
            // Отрезок движения за последний тик (от xo/yo/zo до текущей позиции), от центра хитбокса.
            Vec3 delta = e.position().subtract(e.xo, e.yo, e.zo);
            Vec3 cur = e.getBoundingBox().getCenter();
            Vec3 prev = cur.subtract(delta);
            double radius = Math.max(0.1, Math.min(e.getBbWidth(), e.getBbHeight()) * 0.5);

            Vec3 hit = this.shape.segmentHit(prev, cur, radius);
            if (hit == null) continue;

            if (anti) {
                onCatch(server, e, action, hit);
            } else {
                // Маскировочная сеть ничего не ловит, но рвётся от пролетающих боеприпасов/дронов.
                Integer last = this.recent.get(e.getId());
                if (last != null && this.age - last < 20) continue;
                this.recent.put(e.getId(), this.age);
                damageNet(hit);
            }
            if (this.isRemoved()) break;
        }
    }

    /** Транспорт проехал сквозь сеть: рвёт её тем сильнее, чем быстрее едет. */
    private void onVehicle(ServerLevel server, Entity v) {
        Integer last = this.recent.get(v.getId());
        if (last != null && this.age - last < 10) return;

        Vec3 delta = v.position().subtract(v.xo, v.yo, v.zo);
        double speed = delta.length();
        if (speed < VEHICLE_MIN_SPEED) return;

        // Проверяем несколько точек кузова (5 высот x центр и 4 угла), чтобы сеть на любой высоте задевалась.
        AABB box = v.getBoundingBox();
        Vec3 mid = box.getCenter();
        double hx = box.getXsize() * 0.5;
        double hz = box.getZsize() * 0.5;
        double[][] off = {{0.0, 0.0}, {hx, hz}, {hx, -hz}, {-hx, hz}, {-hx, -hz}};
        Vec3 hit = null;
        for (int row = 0; row <= 4 && hit == null; row++) {
            double y = box.minY + box.getYsize() * (row / 4.0);
            for (double[] o : off) {
                Vec3 cur = new Vec3(mid.x + o[0], y, mid.z + o[1]);
                hit = this.shape.segmentHit(cur.subtract(delta), cur, 0.12);
                if (hit != null) break;
            }
        }
        if (hit == null) return;

        this.recent.put(v.getId(), this.age);
        server.playSound(null, hit.x, hit.y, hit.z, SoundEvents.WOOL_BREAK, SoundSource.BLOCKS, 1.3F, 0.6F);
        damageNet(hit, Math.max(1, (int) Math.ceil(speed * VEHICLE_RIP)));
    }

    /** Объект застревает в сети. Взрывоопасный детонирует через FUSE_TICKS, прочий висит, пока цела сеть. */
    private void onCatch(ServerLevel server, Entity victim, NetRules.Action action, Vec3 p) {
        Vec3 at = p.subtract(0.0, victim.getBbHeight() * 0.5, 0.0);
        boolean explosive = action == NetRules.Action.EXPLODE;
        Stuck s = new Stuck(at, victim.isNoGravity(), action, explosive ? FUSE_TICKS : Integer.MAX_VALUE);

        victim.setNoGravity(true);
        victim.setDeltaMovement(Vec3.ZERO);
        victim.setPos(at.x, at.y, at.z);
        this.stuck.put(victim.getId(), s);

        server.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 10, 0.2, 0.2, 0.2, 0.05);
        server.playSound(null, p.x, p.y, p.z, SoundEvents.WOOL_HIT, SoundSource.BLOCKS, 1.0F, 0.8F);
        if (explosive) {
            server.playSound(null, p.x, p.y, p.z, SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 0.8F, 1.0F);
        }
        damageNet(p);
    }

    private void tickStuck(ServerLevel server) {
        Iterator<Map.Entry<Integer, Stuck>> it = this.stuck.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Stuck> en = it.next();
            Stuck s = en.getValue();
            Entity e = server.getEntity(en.getKey());
            if (e == null || e.isRemoved()) {
                it.remove();
                continue;
            }
            // Держим объект на месте: он «запутался».
            e.setPos(s.pos.x, s.pos.y, s.pos.z);
            e.setDeltaMovement(Vec3.ZERO);

            if (s.action == NetRules.Action.EXPLODE) {
                s.timer--;
                Vec3 c = e.getBoundingBox().getCenter();
                if (s.timer % 8 == 0) {
                    server.sendParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 4, 0.2, 0.2, 0.2, 0.1);
                }
                if (s.timer <= 0) {
                    it.remove();
                    server.explode(e, c.x, c.y, c.z, NetRules.power(e), Level.ExplosionInteraction.NONE);
                    e.discard();
                }
            }
        }
    }

    /** Отпускаем всё, что застряло (сеть порвалась или упала). */
    private void releaseAll() {
        if (this.level() instanceof ServerLevel server) {
            for (Map.Entry<Integer, Stuck> en : this.stuck.entrySet()) {
                Entity e = server.getEntity(en.getKey());
                if (e != null && !e.isRemoved()) {
                    e.setNoGravity(en.getValue().hadNoGravity);
                }
            }
        }
        this.stuck.clear();
    }

    /** Минус одна единица прочности; при нуле сеть разрушается. */
    private void damageNet(Vec3 p) {
        damageNet(p, 1);
    }

    /** Минус amount единиц прочности; при нуле сеть рвётся (предмет не выпадает). */
    private void damageNet(Vec3 p, int amount) {
        if (!(this.level() instanceof ServerLevel server)) return;

        this.hits -= Math.max(1, amount);
        if (this.hits <= 0) {
            releaseAll();
            server.sendParticles(ParticleTypes.POOF, this.shape.center.x, this.shape.center.y, this.shape.center.z,
                    20, 0.5, 0.5, 0.5, 0.02);
            server.playSound(null, p.x, p.y, p.z, SoundEvents.WOOL_BREAK, SoundSource.BLOCKS, 1.2F, 0.8F);
            this.discard();
        } else {
            server.sendParticles(ParticleTypes.POOF, p.x, p.y, p.z, 3, 0.1, 0.1, 0.1, 0.01);
        }
    }

    // ---------------------------------------------------------------- коллизия

    /** Текущая сетка с учётом вмятин (для отрисовки и коллизии). */
    public NetShape.Mesh liveMesh() {
        NetShape.Mesh m = this.liveMesh;
        return m != null ? m : (this.shape != null ? this.shape.mesh() : null);
    }

    /**
     * Сеть мягкая. Кто стоит сверху — продавливает её вниз, кто упёрся снизу (головой, телом) — приподнимает.
     * Сеть всегда плотно облегает сущность, без зазоров. Прыжок/падение сверху даёт сильный прогиб,
     * после чего сеть пружинит и слегка подбрасывает.
     */
    private void updateDents(Level level) {
        boolean client = level.isClientSide;
        Map<Integer, NetShape.Contact> contacts = new HashMap<>();
        Map<Integer, LivingEntity> bodies = new HashMap<>();
        Map<Integer, Double> radii = new HashMap<>();

        List<LivingEntity> list = level.getEntitiesOfClass(
                LivingEntity.class,
                this.getBoundingBox().inflate(1.5),
                e -> !e.isSpectator() && !e.isPassenger() && !e.isRemoved()
                        && NetRules.classify(e) == NetRules.Action.PASS
                        && !(e instanceof Player p && p.getAbilities().flying)
        );
        for (LivingEntity le : list) {
            double hw = le.getBbWidth() * 0.5;
            NetShape.Contact c = this.shape.contact(
                    le.position(), new Vec3(le.xo, le.yo, le.zo), hw, le.getBbHeight());
            if (c != null) {
                contacts.put(le.getId(), c);
                bodies.put(le.getId(), le);
                radii.put(le.getId(), hw);
            }
        }

        if (contacts.isEmpty() && this.dents.isEmpty()) {
            this.liveMesh = null;
            this.load = 0.0;
            return;
        }

        // Вес всех, кто стоит/лежит на сети сверху.
        double total = 0.0;
        for (Map.Entry<Integer, NetShape.Contact> en : contacts.entrySet()) {
            if (en.getValue().dir().y > 0.5) total += mass(bodies.get(en.getKey()));
        }
        this.load = total;
        Vec3 heavyAt = null;

        for (Map.Entry<Integer, NetShape.Contact> en : contacts.entrySet()) {
            this.dents.computeIfAbsent(en.getKey(), k -> new DentState());
        }

        Iterator<Map.Entry<Integer, DentState>> it = this.dents.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, DentState> en = it.next();
            DentState d = en.getValue();
            NetShape.Contact c = contacts.get(en.getKey());
            LivingEntity body = bodies.get(en.getKey());

            double target = 0.0;
            if (c != null) {
                target = Math.min(c.depth(), NetShape.MAX_DENT);
                // Прыжок/падение сверху: сильный удар — глубокий прогиб.
                double vy = body.getY() - body.yo;
                if (!d.inContact && c.dir().y > 0.5 && vy < -0.12) {
                    d.vel += -vy * DENT_IMPACT;
                    if (vy < HEAVY_FALL) heavyAt = c.point();
                }
                d.center = d.center == null ? c.point() : d.center.lerp(c.point(), 0.5);
                d.dir = c.dir();
                d.radius = radii.get(en.getKey()) + 0.8 + 0.7 * Math.max(d.depth, target);
            }
            d.inContact = c != null;

            d.vel += DENT_K * (target - d.depth) - DENT_C * d.vel;
            d.depth += d.vel;
            if (c != null && d.depth < target) {
                // Сеть не должна уходить из-под сущности — иначе появится зазор.
                d.depth = target;
                d.vel = Math.max(d.vel, 0.0);
            }
            if (d.depth > NetShape.MAX_DENT) {
                d.depth = NetShape.MAX_DENT;
                d.vel = Math.min(d.vel, 0.0);
            }
            if (d.depth < -0.2) {
                d.depth = -0.2;
                d.vel = Math.max(d.vel, 0.0);
            }

            if (c != null && c.dir().y > 0.5) {
                body.fallDistance = 0.0F;
                // Сеть распрямляется — подбрасывает того, кто в неё упал.
                boolean mine = client ? body == localPlayerOrNull() : !(body instanceof Player);
                if (mine && d.vel < -0.05) {
                    Vec3 v = body.getDeltaMovement();
                    double up = Math.min(-d.vel * DENT_BOUNCE, 1.0);
                    if (v.y < up) {
                        body.setDeltaMovement(v.x, up, v.z);
                        body.setOnGround(false);
                    }
                }
            }

            if (c == null && Math.abs(d.depth) < 0.02 && Math.abs(d.vel) < 0.02) {
                it.remove();
            }
        }

        if (this.dents.isEmpty()) {
            this.liveMesh = null;
            return;
        }
        List<NetShape.Dent> out = new ArrayList<>(this.dents.size());
        for (DentState d : this.dents.values()) {
            if (d.center != null && d.dir != null && Math.abs(d.depth) > 0.003) {
                out.add(new NetShape.Dent(d.center, d.dir, d.depth, d.radius));
            }
        }
        this.liveMesh = out.isEmpty() ? null : this.shape.deform(out);

        // Падение с большой высоты рвёт сеть (только сервер).
        if (heavyAt != null && !client) {
            damageNet(heavyAt, 1);
        }
    }

    private static double mass(LivingEntity e) {
        return Mth.clamp(e.getBbWidth() * e.getBbHeight(), 0.4, 5.0);
    }

    /** Держит ли сеть эту сущность сверху (для отмены урона от падения). */
    public boolean supports(LivingEntity e) {
        if (this.shape == null) return false;
        Vec3 pos = e.position();
        NetShape.Push push = this.shape.collideDeformed(liveMesh(), pos.subtract(0.0, 0.3, 0.0), pos,
                e.getBbWidth() * 0.5, e.getBbHeight());
        return push != null && push.normal().y > 0.5;
    }

    /** Локальный игрок (только на клиенте; на сервере всегда null). */
    private static Entity localPlayerOrNull() {
        return com.tentnets.client.ClientEvents.localPlayer();
    }

    private void collideMobs(ServerLevel server) {
        List<LivingEntity> list = server.getEntitiesOfClass(
                LivingEntity.class,
                this.getBoundingBox().inflate(1.5),
                e -> !(e instanceof Player) && !e.isSpectator() && !e.isPassenger() && !e.isRemoved()
                        && NetRules.classify(e) == NetRules.Action.PASS
        );
        for (LivingEntity le : list) {
            NetShape.Push push = this.shape.collideDeformed(liveMesh(),
                    le.position(), new Vec3(le.xo, le.yo, le.zo), le.getBbWidth() * 0.5, le.getBbHeight());
            if (push != null) {
                applyPush(le, push);
            }
        }
    }

    /** Применяет толчок коллизии: сдвигает сущность и гасит скорость «в сеть». */
    public static void applyPush(Entity e, NetShape.Push push) {
        Vec3 o = push.offset();
        e.setPos(e.getX() + o.x, e.getY() + o.y, e.getZ() + o.z);

        Vec3 n = push.normal();
        Vec3 v = e.getDeltaMovement();
        double vn = v.dot(n);
        if (vn < 0.0) {
            e.setDeltaMovement(v.subtract(n.scale(vn)));
        }
        if (n.y > 0.5) {
            e.setOnGround(true);
            e.fallDistance = 0.0F;
        }
    }

    // ---------------------------------------------------------------- опоры (физика)

    private boolean isAnchored(Level level) {
        for (int i = 0; i < this.shape.verts.length; i++) {
            if (!vertexAnchored(level, this.shape, i)) return false;
        }
        return true;
    }

    private void collapse(ServerLevel server) {
        releaseAll();
        Vec3 c = this.shape.center;
        server.sendParticles(ParticleTypes.POOF, c.x, c.y, c.z, 15, 0.4, 0.4, 0.4, 0.02);
        server.playSound(null, c.x, c.y, c.z, SoundEvents.WOOL_BREAK, SoundSource.BLOCKS, 1.0F, 0.9F);
        this.spawnAtLocation(new ItemStack(ModRegistry.itemFor(getVariant())));
        this.discard();
    }

    private static boolean vertexAnchored(Level level, NetShape shape, int i) {
        BlockPos exact = anchorBlock(shape, i);
        if (exact != null) {
            return solidOrUnloaded(level, exact);
        }
        // Грань неизвестна (старая сеть): достаточно любого блока рядом с вершиной.
        Vec3 p = shape.verts[i];
        BlockPos base = BlockPos.containing(p.x, p.y, p.z);
        if (solidOrUnloaded(level, base)) return true;
        for (Direction d : Direction.values()) {
            if (solidOrUnloaded(level, base.relative(d))) return true;
        }
        return false;
    }

    /** Грань блока, к которой прижата вершина i (направление «от блока к сети»), либо null. */
    public static Direction anchorDir(NetShape shape, int i) {
        int f = shape.faces[i];
        if (f >= 0 && f < 6) {
            return Direction.values()[f];
        }
        // Старые сети: ищем ось, по которой точка отстоит от целой координаты на FLUSH.
        Vec3 p = shape.verts[i];
        double[] c = {p.x, p.y, p.z};
        Direction.Axis[] axes = {Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z};
        Direction found = null;
        for (int k = 0; k < 3; k++) {
            double d = c[k] - Math.rint(c[k]);
            if (Math.abs(Math.abs(d) - FLUSH) < 0.003) {
                if (found != null) return null;
                found = Direction.fromAxisAndDirection(axes[k],
                        d > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
            }
        }
        return found;
    }

    /** Блок, на котором держится вершина i, либо null, если грань неизвестна. */
    public static BlockPos anchorBlock(NetShape shape, int i) {
        Direction d = anchorDir(shape, i);
        if (d == null) return null;
        Vec3 p = shape.verts[i];
        return BlockPos.containing(p.x - d.getStepX() * 0.05, p.y - d.getStepY() * 0.05, p.z - d.getStepZ() * 0.05);
    }

    private static boolean solidOrUnloaded(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return true;
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    // ---------------------------------------------------------------- прочее

    /** Точка пересечения луча с сетью (для снятия сети игроком), либо null. */
    public Vec3 rayHit(Vec3 from, Vec3 to) {
        return this.shape == null ? null : this.shape.segmentHit(from, to, 0.08);
    }
}
