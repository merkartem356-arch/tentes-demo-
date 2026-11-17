package com.tentnets;

import com.tentnets.net.NetEntity;
import com.tentnets.net.NetItem;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

public final class ModRegistry {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, TentNetsMod.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, TentNetsMod.MODID);

    public static final RegistryObject<EntityType<NetEntity>> NET = ENTITIES.register("net",
            () -> EntityType.Builder.<NetEntity>of(NetEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(16)
                    .updateInterval(Integer.MAX_VALUE)
                    .fireImmune()
                    .noSummon()
                    .build("net"));

    /** По одному предмету на каждый вариант сети. */
    public static final Map<NetEntity.Variant, RegistryObject<Item>> NET_ITEMS = new EnumMap<>(NetEntity.Variant.class);

    static {
        for (NetEntity.Variant v : NetEntity.Variant.values()) {
            NET_ITEMS.put(v, ITEMS.register(v.id, () -> new NetItem(v, new Item.Properties().stacksTo(16))));
        }
    }

    public static Item itemFor(NetEntity.Variant variant) {
        return NET_ITEMS.get(variant).get();
    }

    private ModRegistry() {
    }
}
