package com.tentnets.net;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingFallEvent;

import java.util.List;

/** Общие события мода (и клиент, и сервер). */
public final class NetEvents {
    private NetEvents() {
    }

    /** Мягкая сеть гасит падение: тот, кто приземлился на сеть, не получает урона. */
    public static void onFall(LivingFallEvent event) {
        LivingEntity e = event.getEntity();
        List<NetEntity> nets = e.level().getEntitiesOfClass(
                NetEntity.class, e.getBoundingBox().inflate(1.0, 1.5, 1.0));
        for (NetEntity net : nets) {
            if (net.supports(e)) {
                event.setCanceled(true);
                return;
            }
        }
    }
}
