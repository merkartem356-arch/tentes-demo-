package com.tentnets.client;

import com.tentnets.net.NetEntity;
import com.tentnets.net.NetShape;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderNameTagEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Скрытие ников игроков, которые находятся под маскировочной сетью. */
public final class ClientEvents {
    private static final double LOOK_UP = 128.0;
    private static final int CACHE_TICKS = 5;

    private record Cached(long tick, boolean hidden) {
    }

    private static final Map<UUID, Cached> CACHE = new HashMap<>();

    private ClientEvents() {
    }

    public static void onNameTag(RenderNameTagEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (isUnderMaskNet(player)) {
            // Пустой текст — ник не отображается.
            event.setContent(Component.empty());
        }
    }

    /**
     * Коллизия сети для самого игрока. Считается на клиенте (клиент двигает игрока сам),
     * иначе сервер откатывал бы игрока рывками. Вызывается из NetEntity.tick() только на клиенте.
     */
    /** Локальный игрок или null. Безопасно звать только с клиента (из кода, который выполняется на клиенте). */
    public static Player localPlayer() {
        return Minecraft.getInstance().player;
    }

    public static void collideLocalPlayer(NetEntity net) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || player.isSpectator() || player.getAbilities().flying) return;
        if (player.level() != net.level()) return;

        NetShape shape = net.getShape();
        if (shape == null) return;

        NetShape.Push push = shape.collideDeformed(net.liveMesh(),
                player.position(), new Vec3(player.xo, player.yo, player.zo),
                player.getBbWidth() * 0.5, player.getBbHeight());
        if (push != null) {
            NetEntity.applyPush(player, push);
        }
    }

    private static boolean isUnderMaskNet(Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;

        long now = mc.level.getGameTime();
        Cached cached = CACHE.get(player.getUUID());
        if (cached != null && now >= cached.tick() && now - cached.tick() < CACHE_TICKS) {
            return cached.hidden();
        }

        // Вертикальный луч вверх от макушки: если он пересекает маскировочную сеть — игрок под навесом.
        Vec3 top = new Vec3(player.getX(), player.getBoundingBox().maxY + 0.05, player.getZ());
        Vec3 end = top.add(0.0, LOOK_UP, 0.0);

        boolean hidden = false;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof NetEntity net && net.getKind() == NetEntity.Kind.MASK) {
                NetShape shape = net.getShape();
                if (shape != null && shape.segmentHit(top, end, 0.0) != null) {
                    hidden = true;
                    break;
                }
            }
        }
        CACHE.put(player.getUUID(), new Cached(now, hidden));
        return hidden;
    }
}
