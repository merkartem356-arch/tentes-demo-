package com.tentnets.net;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Проверки при установке сети. */
public final class NetPlacement {
    private NetPlacement() {
    }

    /** true, если поверхность сети проходит сквозь твёрдые блоки. */
    public static boolean crossesBlocks(Level level, NetShape shape) {
        for (Vec3 p : shape.samplePoints()) {
            if (insideSolid(level, p)) return true;
        }
        return false;
    }

    private static boolean insideSolid(Level level, Vec3 p) {
        BlockPos pos = BlockPos.containing(p.x, p.y, p.z);
        if (!level.hasChunkAt(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        VoxelShape vs = state.getCollisionShape(level, pos);
        if (vs.isEmpty()) return false;
        for (AABB box : vs.toAabbs()) {
            // Чуть сжимаем коробку, чтобы сеть, лежащая вплотную к грани, не считалась «внутри».
            if (box.move(pos).inflate(-0.004).contains(p)) return true;
        }
        return false;
    }
}
