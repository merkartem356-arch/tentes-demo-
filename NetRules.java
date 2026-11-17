package com.tentnets.net;

import com.tentnets.TentNetsMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;

import java.util.Locale;
import java.util.Set;

/**
 * Решает, что антидроновая сеть делает с пролетающим объектом.
 *
 * Порядок:
 *  1. теги сущностей (data/tentnets/tags/entity_type/): passthrough, explode, catch, vehicle;
 *  2. эвристика по имени сущности (drone, bomb, grenade, missile, rocket, shell ... -> взрыв;
 *     bullet -> пролетает) — чтобы работало с дронами/боеприпасами из других модов без настройки;
 *  3. лодки, вагонетки и техника из модов (car, truck, tank ...) — VEHICLE: рвут сеть;
 *     стрелы и всё, что наследует AbstractArrow, пролетают как «пули»;
 *     огненные шары и TNT взрываются; прочие Projectile застревают.
 */
public final class NetRules {
    public enum Action {
        /** Пролетает сквозь сеть. */
        PASS,
        /** Взрывается на сетке. */
        EXPLODE,
        /** Застревает в сетке и исчезает. */
        CATCH,
        /** Транспорт: проезжает насквозь и рвёт сеть тем сильнее, чем быстрее едет. */
        VEHICLE
    }

    public static final TagKey<EntityType<?>> PASS_TAG = tag("passthrough");
    public static final TagKey<EntityType<?>> EXPLODE_TAG = tag("explode");
    public static final TagKey<EntityType<?>> CATCH_TAG = tag("catch");
    public static final TagKey<EntityType<?>> VEHICLE_TAG = tag("vehicle");

    private static final String[] EXPLOSIVE_WORDS = {
            "drone", "fpv", "kamikaze", "shahed", "bomb", "grenade", "missile", "rocket", "shell", "mortar", "torpedo"
    };
    /** Слова в id сущности (целые слова, разделённые «_», «.» и т.п.), по которым узнаём технику из других модов. */
    private static final Set<String> VEHICLE_WORDS = Set.of(
            "car", "cars", "vehicle", "truck", "lorry", "van", "bus", "taxi", "jeep", "suv", "sedan", "humvee",
            "uaz", "tank", "apc", "btr", "bmp", "ifv", "tractor", "motorcycle", "motorbike", "bike", "atv",
            "quad", "buggy", "scooter", "kart", "wagon", "helicopter", "heli", "plane", "aircraft", "boat",
            "minecart", "cart"
    );
    private static final String[] BULLET_WORDS = {"bullet", "cartridge", "slug"};

    private NetRules() {
    }

    private static TagKey<EntityType<?>> tag(String name) {
        return TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(TentNetsMod.MODID, name));
    }

    public static Action classify(Entity entity) {
        EntityType<?> type = entity.getType();

        if (type.is(PASS_TAG)) return Action.PASS;
        if (type.is(EXPLODE_TAG)) return Action.EXPLODE;
        if (type.is(CATCH_TAG)) return Action.CATCH;
        if (type.is(VEHICLE_TAG)) return Action.VEHICLE;

        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        String path = key == null ? "" : key.getPath().toLowerCase(Locale.ROOT);

        for (String w : BULLET_WORDS) {
            if (path.contains(w)) return Action.PASS;
        }
        for (String w : EXPLOSIVE_WORDS) {
            if (path.contains(w)) return Action.EXPLODE;
        }

        if (entity instanceof Boat || entity instanceof AbstractMinecart || isVehicleName(path)) {
            return Action.VEHICLE;
        }

        if (entity instanceof AbstractArrow) return Action.PASS;
        if (entity instanceof PrimedTnt || entity instanceof AbstractHurtingProjectile) return Action.EXPLODE;
        if (entity instanceof Projectile) return Action.CATCH;
        return Action.PASS;
    }

    private static boolean isVehicleName(String path) {
        for (String word : path.split("[^a-z0-9]+")) {
            if (VEHICLE_WORDS.contains(word)) return true;
        }
        return false;
    }

    /** Сила взрыва на сетке. Блоки не ломаются (см. NetEntity). */
    public static float power(Entity entity) {
        return entity instanceof PrimedTnt ? 4.0F : 2.5F;
    }
}
