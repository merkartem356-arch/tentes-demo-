package com.tentnets.net;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Сеть, натянутая по опорным точкам.
 *
 * Контур — выпуклый многоугольник из ИСХОДНЫХ точек (сеть касается каждого блока, по которому кликнули).
 * Поверхность гладкая: внутри точки считаются по «средним координатам» (mean value coordinates), поэтому
 * при 4 точках получается один искривлённый квадрат, а не два треугольника. Поверх этого — провисание
 * (смещение вниз, максимальное в центре, ноль на краях).
 *
 * Из поверхности строится сетка треугольников (Mesh): по ней рисуем, ловим дронов и считаем коллизию.
 */
public final class NetShape {
    public static final double MIN_AREA = 1.0;
    public static final double SAG_FACTOR = 0.08;
    public static final double SAG_MAX = 0.5;
    /** Максимальная глубина продавливания сети (блоков). */
    public static final double MAX_DENT = 1.6;
    private static final double EPS = 1.0e-6;
    private static final double CELL = 0.45;
    private static final int MAX_K = 10;

    /** Вершины контура в мировых координатах, против часовой стрелки (если смотреть со стороны нормали). */
    public final Vec3[] verts;
    /** Для каждой вершины: ordinal Direction грани блока, к которой прижата точка, или -1 если неизвестно. */
    public final int[] faces;
    /** Глубина провисания в центре, блоков. */
    public final double sagDepth;
    public final Vec3 center;
    public final Vec3 normal;
    public final Vec3 uAxis;
    public final Vec3 vAxis;
    public final double area;
    public final AABB bounds;

    private final double[] px;
    private final double[] py;
    private volatile Mesh mesh;

    /** Сетка треугольников: по три вершины на треугольник. */
    public static final class Mesh {
        public final Vec3[] tri;
        /** UV-координаты в блоках (в плоскости сети), по две на вершину. */
        public final double[] uv;
        /** Нормали треугольников (по три числа), нули — вырожденный треугольник. */
        public final double[] tn;
        /** Центры треугольников (по три числа). */
        public final double[] tc;
        /** Радиусы описанных сфер. */
        public final double[] tr;
        /** Для каждой вершины: 0 на контуре (закреплено), 1 в центре. Нужно для продавливания. */
        public final double[] edge;

        Mesh(Vec3[] tri, double[] uv, double[] tn, double[] tc, double[] tr, double[] edge) {
            this.tri = tri;
            this.uv = uv;
            this.tn = tn;
            this.tc = tc;
            this.tr = tr;
            this.edge = edge;
        }

        public int triangles() {
            return tr.length;
        }
    }

    /** Результат коллизии: на сколько сдвинуть сущность и в какую сторону её оттолкнули. */
    public record Push(Vec3 offset, Vec3 normal) {
    }

    /** Касание сущности с (недеформированной) сетью: точка, сторона сети, где стоит сущность, и глубина вдавливания. */
    public record Contact(Vec3 point, Vec3 dir, double depth) {
    }

    /** Вмятина: сеть прогибается от центра в сторону, противоположную dir, на depth блоков в радиусе radius. */
    public record Dent(Vec3 center, Vec3 dir, double depth, double radius) {
    }

    private NetShape(Vec3[] verts, int[] faces, double sagDepth) {
        this.verts = verts;
        this.faces = faces;
        this.sagDepth = sagDepth;

        double cx = 0, cy = 0, cz = 0;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vec3 v : verts) {
            cx += v.x;
            cy += v.y;
            cz += v.z;
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            minZ = Math.min(minZ, v.z);
            maxX = Math.max(maxX, v.x);
            maxY = Math.max(maxY, v.y);
            maxZ = Math.max(maxZ, v.z);
        }
        int n = verts.length;
        this.center = new Vec3(cx / n, cy / n, cz / n);
        this.bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);

        Vec3 v0 = verts[0];
        Vec3 sum = Vec3.ZERO;
        for (int i = 1; i + 1 < n; i++) {
            sum = sum.add(verts[i].subtract(v0).cross(verts[i + 1].subtract(v0)));
        }
        double len = sum.length();
        this.area = len * 0.5;
        this.normal = len > 1.0e-9 ? sum.scale(1.0 / len) : new Vec3(0, 1, 0);

        Vec3 e = verts[1].subtract(v0);
        Vec3 inPlane = e.subtract(normal.scale(e.dot(normal)));
        this.uAxis = inPlane.lengthSqr() > 1.0e-12 ? inPlane.normalize() : new Vec3(1, 0, 0);
        this.vAxis = normal.cross(uAxis);

        this.px = new double[n];
        this.py = new double[n];
        for (int i = 0; i < n; i++) {
            Vec3 d = verts[i].subtract(center);
            px[i] = d.dot(uAxis);
            py[i] = d.dot(vAxis);
        }
    }

    /** Та же сеть, но с другой глубиной провисания. */
    public NetShape withSag(double depth) {
        return new NetShape(verts, faces, Math.max(0.0, depth));
    }

    /** Наибольшее провисание для этой формы (зависит от размера и наклона: вертикальная сеть не провисает). */
    public double maxSag() {
        double r = 0;
        for (Vec3 v : verts) r = Math.max(r, v.distanceTo(center));
        return Math.min(SAG_MAX, SAG_FACTOR * r * Math.abs(normal.y));
    }

    // ---------------------------------------------------------------- построение по точкам

    /**
     * Строит сеть по опорным точкам (faces — грани, по которым кликнули; может быть null).
     * Возвращает null, если точки на одной линии или площадь слишком мала.
     */
    public static NetShape fromPoints(List<Vec3> pts, List<Integer> faceList) {
        int n = pts.size();
        if (n < 3) return null;

        double cx = 0, cy = 0, cz = 0;
        for (Vec3 p : pts) {
            cx += p.x;
            cy += p.y;
            cz += p.z;
        }
        Vec3 c = new Vec3(cx / n, cy / n, cz / n);

        Vec3 best = Vec3.ZERO;
        double bestLen = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Vec3 cr = pts.get(i).subtract(c).cross(pts.get(j).subtract(c));
                double l = cr.lengthSqr();
                if (l > bestLen) {
                    bestLen = l;
                    best = cr;
                }
            }
        }
        if (bestLen < 1.0e-6) return null;
        Vec3 nrm = best.normalize();

        Vec3 u = null;
        double ul = 0;
        for (Vec3 p : pts) {
            Vec3 d = p.subtract(c);
            Vec3 in = d.subtract(nrm.scale(d.dot(nrm)));
            double l = in.lengthSqr();
            if (l > ul) {
                ul = l;
                u = in;
            }
        }
        if (u == null || ul < 1.0e-6) return null;
        u = u.normalize();
        Vec3 v = nrm.cross(u);

        List<double[]> flat = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Vec3 d = pts.get(i).subtract(c);
            flat.add(new double[]{d.dot(u), d.dot(v), i});
        }
        List<double[]> hull = convexHull(flat);
        if (hull.size() < 3) return null;

        Vec3[] out = new Vec3[hull.size()];
        int[] outFaces = new int[hull.size()];
        for (int i = 0; i < out.length; i++) {
            int idx = (int) hull.get(i)[2];
            out[i] = pts.get(idx);
            outFaces[i] = (faceList != null && idx < faceList.size() && faceList.get(idx) != null) ? faceList.get(idx) : -1;
        }
        NetShape shape = new NetShape(out, outFaces, 0.0);
        return shape.area >= MIN_AREA ? shape : null;
    }

    private static List<double[]> convexHull(List<double[]> input) {
        List<double[]> pts = new ArrayList<>(input);
        pts.sort(Comparator.<double[]>comparingDouble(p -> p[0]).thenComparingDouble(p -> p[1]));
        int n = pts.size();
        if (n < 3) return pts;

        double[][] h = new double[2 * n][];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross2(h[k - 2], h[k - 1], pts.get(i)) <= EPS) k--;
            h[k++] = pts.get(i);
        }
        for (int i = n - 2, t = k + 1; i >= 0; i--) {
            while (k >= t && cross2(h[k - 2], h[k - 1], pts.get(i)) <= EPS) k--;
            h[k++] = pts.get(i);
        }
        List<double[]> res = new ArrayList<>();
        for (int i = 0; i < k - 1; i++) res.add(h[i]);
        return res;
    }

    private static double cross2(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    // ---------------------------------------------------------------- сеть <-> строка (для синхронизации)

    /** Формат: "x,y,z,грань;x,y,z,грань;...|провисание". */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < verts.length; i++) {
            if (i > 0) sb.append(';');
            Vec3 v = verts[i];
            sb.append(v.x).append(',').append(v.y).append(',').append(v.z).append(',').append(faces[i]);
        }
        sb.append('|').append(sagDepth);
        return sb.toString();
    }

    public static NetShape decode(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            double sag = 0.0;
            String body = s;
            int bar = s.indexOf('|');
            if (bar >= 0) {
                body = s.substring(0, bar);
                sag = Double.parseDouble(s.substring(bar + 1));
            }
            String[] parts = body.split(";");
            if (parts.length < 3) return null;
            Vec3[] v = new Vec3[parts.length];
            int[] f = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                String[] c = parts[i].split(",");
                if (c.length != 3 && c.length != 4) return null;
                v[i] = new Vec3(Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2]));
                f[i] = c.length == 4 ? Integer.parseInt(c[3]) : -1;
            }
            return new NetShape(v, f, sag);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------- поверхность и сетка

    /** Сетка треугольников (строится один раз, лениво). */
    public Mesh mesh() {
        Mesh m = this.mesh;
        if (m == null) {
            m = buildMesh();
            this.mesh = m;
        }
        return m;
    }

    /** Точка гладкой поверхности над точкой (qx, qy) плоскости сети: средние координаты вершин контура. */
    private Vec3 surfaceAt(double qx, double qy) {
        int m = verts.length;
        double[] sx = new double[m];
        double[] sy = new double[m];
        double[] r = new double[m];
        for (int i = 0; i < m; i++) {
            sx[i] = px[i] - qx;
            sy[i] = py[i] - qy;
            r[i] = Math.hypot(sx[i], sy[i]);
            if (r[i] < 1.0e-9) return verts[i];
        }
        double[] t = new double[m];
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            double cross = sx[i] * sy[j] - sy[i] * sx[j];
            double dot = sx[i] * sx[j] + sy[i] * sy[j];
            double den = r[i] * r[j] + dot;
            if (den <= 1.0e-9 * r[i] * r[j]) {
                // точка на ребре — линейная интерполяция между концами ребра
                double w = r[i] / (r[i] + r[j]);
                return verts[i].scale(1.0 - w).add(verts[j].scale(w));
            }
            t[i] = cross / den;
        }
        double sw = 0;
        double ox = 0, oy = 0, oz = 0;
        for (int i = 0; i < m; i++) {
            int prev = (i + m - 1) % m;
            double w = (t[prev] + t[i]) / r[i];
            sw += w;
            ox += w * verts[i].x;
            oy += w * verts[i].y;
            oz += w * verts[i].z;
        }
        if (Math.abs(sw) < 1.0e-12) return center;
        return new Vec3(ox / sw, oy / sw, oz / sw);
    }

    private Mesh buildMesh() {
        int m = verts.length;
        double maxR = 0;
        for (int i = 0; i < m; i++) maxR = Math.max(maxR, Math.hypot(px[i], py[i]));
        int k = Mth.clamp((int) Math.ceil(maxR / CELL), 2, MAX_K);

        int triCount = m * k * k;
        Vec3[] tri = new Vec3[triCount * 3];
        double[] uv = new double[triCount * 6];
        int[] cursor = {0};

        double[] edge = new double[triCount * 3];
        double[][] gf = new double[k + 1][k + 1];
        Vec3[][] g = new Vec3[k + 1][k + 1];
        double[][] gu = new double[k + 1][k + 1];
        double[][] gv = new double[k + 1][k + 1];

        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            // Веер от центра: каждый сектор (центр, вершина i, вершина i+1) делим на k*k маленьких треугольников.
            for (int s = 0; s <= k; s++) {
                for (int t = 0; t <= k - s; t++) {
                    double a = (double) s / k;
                    double b = (double) t / k;
                    double qx = a * px[i] + b * px[j];
                    double qy = a * py[i] + b * py[j];
                    Vec3 p = surfaceAt(qx, qy);
                    double lam = 1.0 - a - b;
                    double f = 1.0 - (1.0 - lam) * (1.0 - lam);
                    if (sagDepth > 0.0) {
                        p = new Vec3(p.x, p.y - sagDepth * f, p.z);
                    }
                    g[s][t] = p;
                    gf[s][t] = f;
                    gu[s][t] = qx;
                    gv[s][t] = qy;
                }
            }
            for (int s = 0; s < k; s++) {
                for (int t = 0; t < k - s; t++) {
                    put(tri, uv, edge, cursor, g, gu, gv, gf, s, t, s + 1, t, s, t + 1);
                    if (s + t <= k - 2) {
                        put(tri, uv, edge, cursor, g, gu, gv, gf, s + 1, t, s + 1, t + 1, s, t + 1);
                    }
                }
            }
        }

        return finish(tri, uv, edge);
    }

    /** Считает нормали, центры и радиусы треугольников по готовым вершинам. */
    private static Mesh finish(Vec3[] tri, double[] uv, double[] edge) {
        int triCount = tri.length / 3;
        double[] tn = new double[triCount * 3];
        double[] tc = new double[triCount * 3];
        double[] tr = new double[triCount];
        for (int q = 0; q < triCount; q++) {
            Vec3 a = tri[q * 3];
            Vec3 b = tri[q * 3 + 1];
            Vec3 c = tri[q * 3 + 2];
            Vec3 nrm = b.subtract(a).cross(c.subtract(a));
            double len = nrm.length();
            if (len > 1.0e-9) {
                tn[q * 3] = nrm.x / len;
                tn[q * 3 + 1] = nrm.y / len;
                tn[q * 3 + 2] = nrm.z / len;
            }
            Vec3 mid = new Vec3((a.x + b.x + c.x) / 3.0, (a.y + b.y + c.y) / 3.0, (a.z + b.z + c.z) / 3.0);
            tc[q * 3] = mid.x;
            tc[q * 3 + 1] = mid.y;
            tc[q * 3 + 2] = mid.z;
            tr[q] = Math.max(mid.distanceTo(a), Math.max(mid.distanceTo(b), mid.distanceTo(c)));
        }
        return new Mesh(tri, uv, tn, tc, tr, edge);
    }

    /** Сетка с вмятинами: вершины смещаются от центров вмятин, контур остаётся на месте. */
    public Mesh deform(Collection<Dent> dents) {
        Mesh base = mesh();
        if (dents.isEmpty()) return base;
        Vec3[] tri = new Vec3[base.tri.length];
        for (int i = 0; i < tri.length; i++) {
            Vec3 v = base.tri[i];
            double ox = 0, oy = 0, oz = 0;
            for (Dent d : dents) {
                double dist = v.distanceTo(d.center());
                if (dist >= d.radius()) continue;
                double w = 0.5 * (1.0 + Math.cos(Math.PI * dist / d.radius()));
                double k = -d.depth() * w * base.edge[i];
                ox += d.dir().x * k;
                oy += d.dir().y * k;
                oz += d.dir().z * k;
            }
            tri[i] = (ox == 0.0 && oy == 0.0 && oz == 0.0) ? v : new Vec3(v.x + ox, v.y + oy, v.z + oz);
        }
        return finish(tri, base.uv, base.edge);
    }

    private static void put(Vec3[] tri, double[] uv, double[] edge, int[] cursor, Vec3[][] g, double[][] gu,
                            double[][] gv, double[][] gf, int s0, int t0, int s1, int t1, int s2, int t2) {
        int q = cursor[0]++;
        edge[q * 3] = gf[s0][t0];
        edge[q * 3 + 1] = gf[s1][t1];
        edge[q * 3 + 2] = gf[s2][t2];
        tri[q * 3] = g[s0][t0];
        tri[q * 3 + 1] = g[s1][t1];
        tri[q * 3 + 2] = g[s2][t2];
        uv[q * 6] = gu[s0][t0];
        uv[q * 6 + 1] = gv[s0][t0];
        uv[q * 6 + 2] = gu[s1][t1];
        uv[q * 6 + 3] = gv[s1][t1];
        uv[q * 6 + 4] = gu[s2][t2];
        uv[q * 6 + 5] = gv[s2][t2];
    }

    /** Точки поверхности для проверок (вершины и центры треугольников сетки). */
    public List<Vec3> samplePoints() {
        Mesh m = mesh();
        List<Vec3> out = new ArrayList<>(m.tri.length + m.triangles());
        for (Vec3 v : m.tri) out.add(v);
        for (int q = 0; q < m.triangles(); q++) {
            out.add(new Vec3(m.tc[q * 3], m.tc[q * 3 + 1], m.tc[q * 3 + 2]));
        }
        return out;
    }

    // ---------------------------------------------------------------- пересечения

    /**
     * Пересечение отрезка a->b с сеткой. margin — «толщина» (радиус летящего объекта).
     * Возвращает ближайшую к a точку на сети или null.
     */
    public Vec3 segmentHit(Vec3 a, Vec3 b, double margin) {
        double pad = margin + sagDepth + 0.1;
        if (!bounds.inflate(pad).intersects(
                Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z),
                Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z))) {
            return null;
        }
        Mesh m = mesh();
        Vec3 best = null;
        double bestD = Double.MAX_VALUE;
        for (int q = 0; q < m.triangles(); q++) {
            double nx = m.tn[q * 3], ny = m.tn[q * 3 + 1], nz = m.tn[q * 3 + 2];
            if (nx == 0.0 && ny == 0.0 && nz == 0.0) continue;
            double rad = m.tr[q] + margin + 0.01;
            if (segDistSq(a, b, m.tc[q * 3], m.tc[q * 3 + 1], m.tc[q * 3 + 2]) > rad * rad) continue;
            Vec3 hit = triangleHit(m.tri[q * 3], m.tri[q * 3 + 1], m.tri[q * 3 + 2], nx, ny, nz, a, b, margin);
            if (hit != null) {
                double d = hit.distanceToSqr(a);
                if (d < bestD) {
                    bestD = d;
                    best = hit;
                }
            }
        }
        return best;
    }

    private static double segDistSq(Vec3 a, Vec3 b, double cx, double cy, double cz) {
        double abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        double acx = cx - a.x, acy = cy - a.y, acz = cz - a.z;
        double ab2 = abx * abx + aby * aby + abz * abz;
        double t = ab2 < 1.0e-12 ? 0.0 : Mth.clamp((acx * abx + acy * aby + acz * abz) / ab2, 0.0, 1.0);
        double dx = a.x + abx * t - cx;
        double dy = a.y + aby * t - cy;
        double dz = a.z + abz * t - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static Vec3 triangleHit(Vec3 t0, Vec3 t1, Vec3 t2, double nx, double ny, double nz,
                                    Vec3 a, Vec3 b, double margin) {
        Vec3 n = new Vec3(nx, ny, nz);
        double d0 = n.dot(a.subtract(t0));
        double d1 = n.dot(b.subtract(t0));
        if ((d0 > margin && d1 > margin) || (d0 < -margin && d1 < -margin)) return null;

        Vec3 p;
        if (Math.abs(d0 - d1) < 1.0e-9) {
            p = b.subtract(n.scale(d1));
        } else {
            double t = Mth.clamp(d0 / (d0 - d1), 0.0, 1.0);
            p = a.add(b.subtract(a).scale(t));
            p = p.subtract(n.scale(n.dot(p.subtract(t0))));
        }
        return inTriangle(t0, t1, t2, n, p, margin) ? p : null;
    }

    private static boolean inTriangle(Vec3 t0, Vec3 t1, Vec3 t2, Vec3 n, Vec3 p, double margin) {
        Vec3[] tri = {t0, t1, t2};
        for (int i = 0; i < 3; i++) {
            Vec3 a = tri[i];
            Vec3 e = tri[(i + 1) % 3].subtract(a);
            double len = e.length();
            if (len < 1.0e-9) continue;
            double side = e.cross(p.subtract(a)).dot(n) / len;
            if (side < -(margin + 1.0e-6)) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- коллизия

    /**
     * Не даёт сущности пройти сквозь сеть.
     *
     * @param pos       текущая позиция (низ хитбокса, по центру)
     * @param prev      позиция на прошлом тике
     * @param halfWidth половина ширины хитбокса
     * @param height    высота хитбокса
     * @return на сколько сдвинуть сущность и нормаль толчка; null, если сети не касается
     */
    public Push collide(Vec3 pos, Vec3 prev, double halfWidth, double height) {
        return collide(mesh(), 0.0, pos, prev, halfWidth, height);
    }

    /** Коллизия с деформированной сеткой (см. {@link #deform}). */
    public Push collideDeformed(Mesh m, Vec3 pos, Vec3 prev, double halfWidth, double height) {
        return collide(m, MAX_DENT + 0.2, pos, prev, halfWidth, height);
    }

    private Push collide(Mesh m, double extra, Vec3 pos, Vec3 prev, double halfWidth, double height) {
        double pad = halfWidth + sagDepth + 0.3 + extra;
        if (!bounds.inflate(pad).intersects(
                Math.min(pos.x, prev.x), Math.min(pos.y, prev.y), Math.min(pos.z, prev.z),
                Math.max(pos.x, prev.x), Math.max(pos.y, prev.y) + height, Math.max(pos.z, prev.z))) {
            return null;
        }

        double top = Math.max(0.05, height - 0.02);
        double[] heights = {0.0, height * 0.5, top};

        Vec3 total = Vec3.ZERO;
        Vec3 normalSum = Vec3.ZERO;
        for (double dy : heights) {
            Vec3 cur = pos.add(total).add(0, dy, 0);
            Vec3 old = prev.add(0, dy, 0);
            for (int q = 0; q < m.triangles(); q++) {
                double nx = m.tn[q * 3], ny = m.tn[q * 3 + 1], nz = m.tn[q * 3 + 2];
                if (nx == 0.0 && ny == 0.0 && nz == 0.0) continue;

                double rad = m.tr[q] + halfWidth * 1.5 + 0.1;
                double ex = cur.x - m.tc[q * 3], ey = cur.y - m.tc[q * 3 + 1], ez = cur.z - m.tc[q * 3 + 2];
                if (ex * ex + ey * ey + ez * ez > rad * rad) continue;

                Vec3 n = new Vec3(nx, ny, nz);
                Vec3 t0 = m.tri[q * 3];
                double d = n.dot(cur.subtract(t0));
                double dOld = n.dot(old.subtract(t0));
                double side = Math.abs(dOld) > 1.0e-4 ? Math.signum(dOld) : (d >= 0 ? 1.0 : -1.0);
                double dSide = d * side;
                double thick = halfWidth * (Math.abs(nx) + Math.abs(nz)) + 0.005;
                if (dSide >= thick || dSide < -0.8) continue;

                Vec3 proj = cur.subtract(n.scale(d));
                if (!inTriangle(t0, m.tri[q * 3 + 1], m.tri[q * 3 + 2], n, proj, 0.04)) continue;

                Vec3 dir = n.scale(side);
                Vec3 push = dir.scale(thick - dSide);
                cur = cur.add(push);
                total = total.add(push);
                normalSum = normalSum.add(dir);
            }
        }
        if (total.lengthSqr() < 1.0e-10) return null;
        return new Push(total, normalSum.normalize());
    }

    /**
     * Насколько сущность вдавилась в НЕдеформированную сеть (для расчёта вмятины).
     * Возвращает самое глубокое касание по трём высотам хитбокса или null.
     */
    public Contact contact(Vec3 pos, Vec3 prev, double halfWidth, double height) {
        double pad = halfWidth + sagDepth + 0.3;
        if (!bounds.inflate(pad).intersects(
                Math.min(pos.x, prev.x), Math.min(pos.y, prev.y), Math.min(pos.z, prev.z),
                Math.max(pos.x, prev.x), Math.max(pos.y, prev.y) + height, Math.max(pos.z, prev.z))) {
            return null;
        }

        Mesh m = mesh();
        double top = Math.max(0.05, height - 0.02);
        double[] heights = {0.0, height * 0.5, top};

        Contact best = null;
        for (double dy : heights) {
            Vec3 cur = pos.add(0, dy, 0);
            Vec3 old = prev.add(0, dy, 0);
            for (int q = 0; q < m.triangles(); q++) {
                double nx = m.tn[q * 3], ny = m.tn[q * 3 + 1], nz = m.tn[q * 3 + 2];
                if (nx == 0.0 && ny == 0.0 && nz == 0.0) continue;

                double rad = m.tr[q] + halfWidth * 1.5 + 0.1;
                double ex = cur.x - m.tc[q * 3], ey = cur.y - m.tc[q * 3 + 1], ez = cur.z - m.tc[q * 3 + 2];
                if (ex * ex + ey * ey + ez * ez > rad * rad) continue;

                Vec3 n = new Vec3(nx, ny, nz);
                Vec3 t0 = m.tri[q * 3];
                double d = n.dot(cur.subtract(t0));
                double dOld = n.dot(old.subtract(t0));
                double side = Math.abs(dOld) > 1.0e-4 ? Math.signum(dOld) : (d >= 0 ? 1.0 : -1.0);
                double dSide = d * side;
                double thick = halfWidth * (Math.abs(nx) + Math.abs(nz)) + 0.005;
                if (dSide >= thick || dSide < -0.8) continue;

                Vec3 proj = cur.subtract(n.scale(d));
                if (!inTriangle(t0, m.tri[q * 3 + 1], m.tri[q * 3 + 2], n, proj, 0.04)) continue;

                double depth = thick - dSide;
                if (depth > 0.002 && (best == null || depth > best.depth())) {
                    best = new Contact(proj, n.scale(side), depth);
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- текстурные координаты (запасной вариант)

    /** Координата точки вдоль оси U сети. */
    public double uCoord(Vec3 p) {
        return p.subtract(center).dot(uAxis);
    }

    /** Координата точки вдоль оси V сети. */
    public double vCoord(Vec3 p) {
        return p.subtract(center).dot(vAxis);
    }
}
