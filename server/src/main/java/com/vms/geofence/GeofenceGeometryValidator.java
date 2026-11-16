package com.vms.geofence;

import com.vms.infrastructure.web.BadRequestException;
import java.util.List;

/**
 * 15：页面与服务端共用同一套围栏合法性规则，服务端校验是最终权威。
 *
 * <p>圆形半径按米计，范围 10–100000；多边形 3–20 个顶点，坐标必须是合法 WGS84 经纬度，
 * 拒绝连续重复点、自交与面积近似为 0 的退化形状。规则变更必须同步前端绘制校验。
 */
public final class GeofenceGeometryValidator {

    public static final String ERROR_CODE = "INVALID_GEOFENCE_GEOMETRY";
    public static final double MIN_RADIUS_M = 10.0;
    public static final double MAX_RADIUS_M = 100_000.0;
    public static final int MIN_VERTICES = 3;
    public static final int MAX_VERTICES = 20;

    private static final double EPSILON = 1e-12;
    private static final double METRES_PER_DEGREE = 111_320.0;
    private static final double MIN_AREA_M2 = 1.0;

    private GeofenceGeometryValidator() {
    }

    public record Vertex(double lng, double lat) {
    }

    public record Geometry(String fenceType, Double centerLng, Double centerLat, Double radiusM,
                           List<Vertex> vertices) {
    }

    public static Geometry validate(String fenceType, Double centerLng, Double centerLat,
                                    Double radiusM, List<Vertex> vertices) {
        if ("CIRCLE".equals(fenceType)) {
            requireCoordinate(centerLng, centerLat);
            if (radiusM == null || !Double.isFinite(radiusM)) {
                throw invalid("圆形围栏必须提供半径");
            }
            if (radiusM < MIN_RADIUS_M || radiusM > MAX_RADIUS_M) {
                throw invalid("圆形围栏半径必须在 10 到 100000 米之间");
            }
            if (vertices != null && !vertices.isEmpty()) {
                throw invalid("圆形围栏不能同时提供多边形顶点");
            }
            return new Geometry(fenceType, centerLng, centerLat, radiusM, List.of());
        }
        if ("POLYGON".equals(fenceType)) {
            if (centerLng != null || centerLat != null || radiusM != null) {
                throw invalid("多边形围栏不能同时提供圆心或半径");
            }
            return new Geometry(fenceType, null, null, null, requirePolygon(vertices));
        }
        throw invalid("围栏类型必须为 CIRCLE 或 POLYGON");
    }

    private static List<Vertex> requirePolygon(List<Vertex> vertices) {
        if (vertices == null || vertices.size() < MIN_VERTICES || vertices.size() > MAX_VERTICES) {
            throw invalid("多边形围栏顶点数必须在 3 到 20 之间");
        }
        for (int i = 0; i < vertices.size(); i++) {
            Vertex vertex = vertices.get(i);
            if (vertex == null) {
                throw invalid("多边形围栏顶点不能为空");
            }
            requireCoordinate(vertex.lng(), vertex.lat());
            Vertex next = vertices.get((i + 1) % vertices.size());
            if (next == null) {
                throw invalid("多边形围栏顶点不能为空");
            }
            if (samePoint(vertex, next)) {
                throw invalid("多边形围栏不能包含重复相邻顶点");
            }
        }
        if (hasSelfIntersection(vertices)) {
            throw invalid("多边形围栏不能自交");
        }
        if (approximateAreaM2(vertices) < MIN_AREA_M2) {
            throw invalid("多边形围栏面积不能为 0");
        }
        return List.copyOf(vertices);
    }

    private static void requireCoordinate(Double lng, Double lat) {
        if (lng == null || lat == null || !Double.isFinite(lng) || !Double.isFinite(lat)) {
            throw invalid("围栏坐标必须是有效经纬度");
        }
        if (lng < -180 || lng > 180 || lat < -90 || lat > 90) {
            throw invalid("围栏坐标超出 WGS84 经纬度范围");
        }
    }

    private static boolean samePoint(Vertex a, Vertex b) {
        return Double.compare(a.lng(), b.lng()) == 0 && Double.compare(a.lat(), b.lat()) == 0;
    }

    private static boolean hasSelfIntersection(List<Vertex> vertices) {
        int n = vertices.size();
        for (int i = 0; i < n; i++) {
            Vertex a = vertices.get(i);
            Vertex b = vertices.get((i + 1) % n);
            for (int j = i + 1; j < n; j++) {
                // 相邻边共享端点，不视为自交；最后一条边与第一条边同样相邻。
                if (j == i || j == (i + 1) % n || i == (j + 1) % n) {
                    continue;
                }
                Vertex c = vertices.get(j);
                Vertex d = vertices.get((j + 1) % n);
                if (segmentsIntersect(a, b, c, d)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(Vertex a, Vertex b, Vertex c, Vertex d) {
        double d1 = cross(c, d, a);
        double d2 = cross(c, d, b);
        double d3 = cross(a, b, c);
        double d4 = cross(a, b, d);
        if (((d1 > EPSILON && d2 < -EPSILON) || (d1 < -EPSILON && d2 > EPSILON))
                && ((d3 > EPSILON && d4 < -EPSILON) || (d3 < -EPSILON && d4 > EPSILON))) {
            return true;
        }
        // 共线且落在对方线段内也算自交，避免重复顶点或重叠边绕过检查。
        return (Math.abs(d1) <= EPSILON && onSegment(c, d, a))
                || (Math.abs(d2) <= EPSILON && onSegment(c, d, b))
                || (Math.abs(d3) <= EPSILON && onSegment(a, b, c))
                || (Math.abs(d4) <= EPSILON && onSegment(a, b, d));
    }

    private static double cross(Vertex a, Vertex b, Vertex p) {
        return (b.lng() - a.lng()) * (p.lat() - a.lat())
                - (b.lat() - a.lat()) * (p.lng() - a.lng());
    }

    private static boolean onSegment(Vertex a, Vertex b, Vertex p) {
        return p.lng() >= Math.min(a.lng(), b.lng()) - EPSILON
                && p.lng() <= Math.max(a.lng(), b.lng()) + EPSILON
                && p.lat() >= Math.min(a.lat(), b.lat()) - EPSILON
                && p.lat() <= Math.max(a.lat(), b.lat()) + EPSILON;
    }

    /** 鞋带公式先平移到首点附近，再按中心纬度换算为平方米，避免大数相减丢精度。 */
    private static double approximateAreaM2(List<Vertex> vertices) {
        double originLng = vertices.getFirst().lng();
        double originLat = vertices.getFirst().lat();
        double twiceArea = 0;
        double meanLat = 0;
        for (int i = 0; i < vertices.size(); i++) {
            Vertex a = vertices.get(i);
            Vertex b = vertices.get((i + 1) % vertices.size());
            double ax = a.lng() - originLng;
            double ay = a.lat() - originLat;
            double bx = b.lng() - originLng;
            double by = b.lat() - originLat;
            twiceArea += ax * by - bx * ay;
            meanLat += a.lat();
        }
        meanLat /= vertices.size();
        double lngScale = Math.max(METRES_PER_DEGREE * Math.cos(Math.toRadians(meanLat)), 1.0);
        return Math.abs(twiceArea) / 2.0 * METRES_PER_DEGREE * lngScale;
    }

    private static BadRequestException invalid(String message) {
        return new BadRequestException(ERROR_CODE, message);
    }
}