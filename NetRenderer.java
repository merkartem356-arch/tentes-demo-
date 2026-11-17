package com.tentnets.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tentnets.TentNetsMod;
import com.tentnets.net.NetEntity;
import com.tentnets.net.NetShape;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * Рисует сеть как гладкую сетку треугольников (двусторонняя, со своим освещением с каждой стороны),
 * плюс крепления: болтик на каждой опорной точке, трос по всему контуру и обмотка верёвкой,
 * если сеть привязана к столбу (забор, стена).
 * Антидроновая — полупрозрачная сетка, маскировочная — непрозрачный камуфляж.
 * Лесная маскировочная сеть дополнительно обвешана листвой.
 */
public class NetRenderer extends EntityRenderer<NetEntity> {
    private static final Map<NetEntity.Variant, ResourceLocation> TEX = new EnumMap<>(NetEntity.Variant.class);
    private static final ResourceLocation LEAVES =
            ResourceLocation.fromNamespaceAndPath(TentNetsMod.MODID, "textures/entity/net_leaves.png");
    private static final ResourceLocation HARDWARE =
            ResourceLocation.fromNamespaceAndPath(TentNetsMod.MODID, "textures/entity/net_hardware.png");

    static {
        for (NetEntity.Variant v : NetEntity.Variant.values()) {
            TEX.put(v, ResourceLocation.fromNamespaceAndPath(TentNetsMod.MODID, "textures/entity/" + v.id + ".png"));
        }
    }

    /** Сколько повторов текстуры на блок. */
    private static final float ANTI_UV_PER_BLOCK = 1.0F;
    private static final float MASK_UV_PER_BLOCK = 0.5F;

    public NetRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(NetEntity entity) {
        return TEX.get(entity.getVariant());
    }

    @Override
    public void render(NetEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        NetShape shape = entity.getShape();
        if (shape == null) return;

        boolean anti = entity.getKind() == NetEntity.Kind.ANTI;
        ResourceLocation tex = TEX.get(entity.getVariant());
        // Culling включён, обе стороны рисуем отдельно: так у каждой стороны правильное освещение.
        VertexConsumer vc = buffer.getBuffer(anti
                ? RenderType.entityTranslucentCull(tex)
                : RenderType.entityCutout(tex));
        float uvScale = anti ? ANTI_UV_PER_BLOCK : MASK_UV_PER_BLOCK;

        // Центр сети может оказаться внутри блока (тёмный свет) — подтягиваем минимум.
        int light = LightTexture.pack(
                Math.max(LightTexture.block(packedLight), 4),
                Math.max(LightTexture.sky(packedLight), 8));

        PoseStack.Pose pose = poseStack.last();
        Vec3 origin = entity.position();

        // Сетка с учётом вмятин. Режим QUADS: четвёртая вершина дублирует третью.
        NetShape.Mesh mesh = entity.liveMesh();
        for (int q = 0; q < mesh.triangles(); q++) {
            float nx = (float) mesh.tn[q * 3];
            float ny = (float) mesh.tn[q * 3 + 1];
            float nz = (float) mesh.tn[q * 3 + 2];
            if (nx == 0.0F && ny == 0.0F && nz == 0.0F) {
                nx = (float) shape.normal.x;
                ny = (float) shape.normal.y;
                nz = (float) shape.normal.z;
            }
            // Лицевая сторона: вершины 0, 1, 2, 2.
            for (int k : FRONT) {
                vertex(vc, pose, mesh.tri[q * 3 + k], origin,
                        (float) mesh.uv[q * 6 + k * 2] * uvScale, (float) mesh.uv[q * 6 + k * 2 + 1] * uvScale,
                        light, nx, ny, nz);
            }
            // Оборотная сторона: обратный обход вершин и перевёрнутая нормаль.
            for (int k : BACK) {
                vertex(vc, pose, mesh.tri[q * 3 + k], origin,
                        (float) mesh.uv[q * 6 + k * 2] * uvScale, (float) mesh.uv[q * 6 + k * 2 + 1] * uvScale,
                        light, -nx, -ny, -nz);
            }
        }

        // Важно: каждый буфер используем целиком до запроса следующего, иначе предыдущий закроется.
        renderHardware(entity, shape, buffer, pose, origin, light);

        if (entity.getVariant() == NetEntity.Variant.MASK_FOREST) {
            VertexConsumer lv = buffer.getBuffer(RenderType.entityCutoutNoCull(LEAVES));
            renderLeaves(mesh, lv, pose, origin, light, entity.tickCount + partialTick);
        }
    }

    private static final int[] FRONT = {0, 1, 2, 2};
    private static final int[] BACK = {0, 2, 1, 1};

    // ---------------------------------------------------------------- листва

    /** Листья на лесной сетке: по 1-2 на каждый треугольник сетки, лежат сверху и слегка покачиваются. */
    private static void renderLeaves(NetShape.Mesh mesh, VertexConsumer lv, PoseStack.Pose pose, Vec3 origin,
                                     int light, float time) {
        for (int q = 0; q < mesh.triangles(); q++) {
            double nx = mesh.tn[q * 3], ny = mesh.tn[q * 3 + 1], nz = mesh.tn[q * 3 + 2];
            if (nx == 0.0 && ny == 0.0 && nz == 0.0) continue;
            double sgn = ny >= 0.0 ? 1.0 : -1.0; // листья лежат на верхней стороне
            Vec3 n = new Vec3(nx * sgn, ny * sgn, nz * sgn);
            Vec3 a = mesh.tri[q * 3];
            Vec3 b = mesh.tri[q * 3 + 1];
            Vec3 c = mesh.tri[q * 3 + 2];

            for (int k = 0; k < 2; k++) {
                int h = hash(q * 2 + k + 17);
                if (k == 1 && (h & 1) == 0) continue; // второй лист не везде

                double r1 = rnd(h, 1);
                double r2 = rnd(h, 2);
                if (r1 + r2 > 1.0) {
                    r1 = 1.0 - r1;
                    r2 = 1.0 - r2;
                }
                Vec3 p = a.add(b.subtract(a).scale(r1)).add(c.subtract(a).scale(r2))
                        .add(n.scale(0.02 + 0.05 * rnd(h, 3)));

                double size = 0.30 + 0.25 * rnd(h, 4);
                double ang = rnd(h, 5) * Math.PI * 2.0;
                Vec3 ref = Math.abs(n.y) < 0.9 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
                Vec3 t1 = n.cross(ref).normalize();
                Vec3 t2 = n.cross(t1);
                Vec3 u = t1.scale(Math.cos(ang)).add(t2.scale(Math.sin(ang)));
                Vec3 v = n.cross(u);

                // Кончик листа приподнят/опущен и качается на ветру.
                double tilt = (rnd(h, 6) - 0.4) * 0.5 * size
                        + Math.sin(time * 0.08 + rnd(h, 8) * 6.2832) * 0.10 * size;
                double half = size * 0.38;
                Vec3 c0 = p.add(v.scale(-half));
                Vec3 c1 = p.add(v.scale(half));
                Vec3 c2 = p.add(u.scale(size)).add(v.scale(half)).add(n.scale(tilt));
                Vec3 c3 = p.add(u.scale(size)).add(v.scale(-half)).add(n.scale(tilt));

                int cell = (h >>> 12) & 3;
                float u0 = (cell & 1) * 0.5F;
                float v0 = (cell >> 1) * 0.5F;
                float u1 = u0 + 0.5F;
                float v1 = v0 + 0.5F;

                double bright = 0.70 + 0.30 * rnd(h, 7);
                int cr = (int) (255 * bright * (0.88 + 0.12 * rnd(h, 9)));
                int cg = (int) (255 * bright);
                int cb = (int) (255 * bright * (0.78 + 0.18 * rnd(h, 10)));

                float fx = (float) n.x;
                float fy = (float) n.y;
                float fz = (float) n.z;
                leafVertex(lv, pose, c0, origin, u0, v1, light, cr, cg, cb, fx, fy, fz);
                leafVertex(lv, pose, c1, origin, u1, v1, light, cr, cg, cb, fx, fy, fz);
                leafVertex(lv, pose, c2, origin, u1, v0, light, cr, cg, cb, fx, fy, fz);
                leafVertex(lv, pose, c3, origin, u0, v0, light, cr, cg, cb, fx, fy, fz);
            }
        }
    }

    private static int hash(int x) {
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }

    /** Псевдослучайное число 0..1 по зерну h и номеру k. */
    private static double rnd(int h, int k) {
        return (hash(h + k * 0x9E3779B1) >>> 8) / (double) (1 << 24);
    }

    private static void leafVertex(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, Vec3 origin,
                                   float u, float v, int light, int r, int g, int b,
                                   float nx, float ny, float nz) {
        vc.addVertex(pose, (float) (p.x - origin.x), (float) (p.y - origin.y), (float) (p.z - origin.z))
                .setColor(r, g, b, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }

    // ---------------------------------------------------------------- крепления

    private void renderHardware(NetEntity entity, NetShape shape, MultiBufferSource buffer, PoseStack.Pose pose,
                                Vec3 origin, int light) {
        VertexConsumer hv = buffer.getBuffer(RenderType.entityCutoutNoCull(HARDWARE));
        Level level = entity.level();

        // Окантовочный трос по контуру, от крепления к креплению.
        for (int i = 0; i < shape.verts.length; i++) {
            rope(hv, pose, origin, shape.verts[i], shape.verts[(i + 1) % shape.verts.length], 0.03, light);
        }

        for (int i = 0; i < shape.verts.length; i++) {
            Vec3 p = shape.verts[i];
            Direction face = NetEntity.anchorDir(shape, i);
            if (face == null) continue;

            // Болтик: плоская шайба на грани блока и шляпка над ней.
            double ax = face.getAxis() == Direction.Axis.X ? 1.0 : 0.0;
            double ay = face.getAxis() == Direction.Axis.Y ? 1.0 : 0.0;
            double az = face.getAxis() == Direction.Axis.Z ? 1.0 : 0.0;
            box(hv, pose, origin, p.x, p.y, p.z,
                    ax > 0 ? 0.02 : 0.07, ay > 0 ? 0.02 : 0.07, az > 0 ? 0.02 : 0.07,
                    0.5F, 0.0F, 1.0F, 1.0F, light);
            box(hv, pose, origin,
                    p.x + face.getStepX() * 0.045, p.y + face.getStepY() * 0.045, p.z + face.getStepZ() * 0.045,
                    ax > 0 ? 0.03 : 0.035, ay > 0 ? 0.03 : 0.035, az > 0 ? 0.03 : 0.035,
                    0.5F, 0.0F, 1.0F, 1.0F, light);

            // Обмотка вокруг столба (забор, стена и т.п.), если сеть привязана к боковой грани.
            if (face.getAxis() != Direction.Axis.Y) {
                BlockPos anchor = NetEntity.anchorBlock(shape, i);
                if (anchor != null) {
                    AABB core = postCore(level, anchor);
                    if (core != null) {
                        wrap(hv, pose, origin, core, p.y, light);
                    }
                }
            }
        }
    }

    /** Трос: четырёхгранная призма толщиной 2*t от точки a до точки b (левая половина текстуры крепежа). */
    private static void rope(VertexConsumer vc, PoseStack.Pose pose, Vec3 o, Vec3 a, Vec3 b, double t, int light) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        if (len < 1.0e-4) return;
        Vec3 u = d.scale(1.0 / len);
        Vec3 ref = Math.abs(u.y) < 0.9 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
        Vec3 w = u.cross(ref).normalize();
        Vec3 v = u.cross(w).normalize();
        Vec3[] ring = {w.add(v), v.subtract(w), w.add(v).scale(-1.0), w.subtract(v)};
        for (int k = 0; k < 4; k++) {
            int m = (k + 1) % 4;
            Vec3 nrm = ring[k].add(ring[m]).normalize();
            Vec3 a0 = a.add(ring[k].scale(t));
            Vec3 a1 = a.add(ring[m].scale(t));
            Vec3 b0 = b.add(ring[k].scale(t));
            Vec3 b1 = b.add(ring[m].scale(t));
            float nx = (float) nrm.x, ny = (float) nrm.y, nz = (float) nrm.z;
            vertex(vc, pose, a0, o, 0.0F, 0.25F, light, nx, ny, nz);
            vertex(vc, pose, b0, o, 0.5F, 0.25F, light, nx, ny, nz);
            vertex(vc, pose, b1, o, 0.5F, 0.75F, light, nx, ny, nz);
            vertex(vc, pose, a1, o, 0.0F, 0.75F, light, nx, ny, nz);
        }
    }

    /** Ствол столба в мировых координатах или null, если блок — не столб. */
    private static AABB postCore(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return null;

        double half;
        if (state.is(BlockTags.FENCES)) {
            half = 0.125;
        } else if (state.is(BlockTags.WALLS)) {
            half = 0.25;
        } else {
            VoxelShape vs = state.getShape(level, pos);
            if (vs.isEmpty()) return null;
            AABB b = vs.bounds();
            if (b.getXsize() > 0.55 || b.getZsize() > 0.55 || b.getYsize() < 0.5) return null;
            return b.move(pos);
        }
        return new AABB(pos.getX() + 0.5 - half, pos.getY(), pos.getZ() + 0.5 - half,
                pos.getX() + 0.5 + half, pos.getY() + 1.0, pos.getZ() + 0.5 + half);
    }

    /** Два витка верёвки вокруг столба на высоте y. */
    private static void wrap(VertexConsumer vc, PoseStack.Pose pose, Vec3 origin, AABB core, double y, int light) {
        double e = 0.018;
        double x0 = core.minX - e, x1 = core.maxX + e;
        double z0 = core.minZ - e, z1 = core.maxZ + e;
        double yc = Math.max(core.minY + 0.07, Math.min(core.maxY - 0.07, y));
        for (int band = 0; band < 2; band++) {
            double yb = yc + (band == 0 ? -0.04 : 0.04);
            double y0 = yb - 0.02, y1 = yb + 0.02;
            quad(vc, pose, origin, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, 0, 0, -1, light);
            quad(vc, pose, origin, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, 0, 0, 1, light);
            quad(vc, pose, origin, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1, 0, 0, light);
            quad(vc, pose, origin, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 0, 0, light);
        }
    }

    /** Верёвка: левая половина текстуры крепежа. */
    private static void quad(VertexConsumer vc, PoseStack.Pose pose, Vec3 o,
                             double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cy, double cz, double dx, double dy, double dz,
                             float nx, float ny, float nz, int light) {
        quadUv(vc, pose, o, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, nx, ny, nz, 0.0F, 0.25F, 0.5F, 0.75F, light);
    }

    private static void quadUv(VertexConsumer vc, PoseStack.Pose pose, Vec3 o,
                               double ax, double ay, double az, double bx, double by, double bz,
                               double cx, double cy, double cz, double dx, double dy, double dz,
                               float nx, float ny, float nz, float u0, float v0, float u1, float v1, int light) {
        vertex(vc, pose, new Vec3(ax, ay, az), o, u0, v1, light, nx, ny, nz);
        vertex(vc, pose, new Vec3(bx, by, bz), o, u0, v0, light, nx, ny, nz);
        vertex(vc, pose, new Vec3(cx, cy, cz), o, u1, v0, light, nx, ny, nz);
        vertex(vc, pose, new Vec3(dx, dy, dz), o, u1, v1, light, nx, ny, nz);
    }

    /** Параллелепипед с центром в (cx,cy,cz) и половинными размерами hx,hy,hz. */
    private static void box(VertexConsumer vc, PoseStack.Pose pose, Vec3 o, double cx, double cy, double cz,
                            double hx, double hy, double hz, float u0, float v0, float u1, float v1, int light) {
        double x0 = cx - hx, x1 = cx + hx;
        double y0 = cy - hy, y1 = cy + hy;
        double z0 = cz - hz, z1 = cz + hz;
        quadUv(vc, pose, o, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0, u0, v0, u1, v1, light);
        quadUv(vc, pose, o, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, u0, v0, u1, v1, light);
        quadUv(vc, pose, o, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1, 0, 0, u0, v0, u1, v1, light);
        quadUv(vc, pose, o, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, -1, 0, 0, u0, v0, u1, v1, light);
        quadUv(vc, pose, o, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, 0, 0, 1, u0, v0, u1, v1, light);
        quadUv(vc, pose, o, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0, 0, -1, u0, v0, u1, v1, light);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, Vec3 origin,
                               float u, float v, int light, float nx, float ny, float nz) {
        vc.addVertex(pose, (float) (p.x - origin.x), (float) (p.y - origin.y), (float) (p.z - origin.z))
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
