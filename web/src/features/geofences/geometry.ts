/**
 * 与服务端 `GeofenceGeometryValidator` 使用同一套围栏合法性规则。
 * 服务端校验是最终权威；页面校验只用于即时反馈，规则变更必须同步两处。
 */

export const MIN_RADIUS_M = 10
export const MAX_RADIUS_M = 100_000
export const MIN_VERTICES = 3
export const MAX_VERTICES = 20

const EPSILON = 1e-12
const METRES_PER_DEGREE = 111_320
const MIN_AREA_M2 = 1

export interface Vertex {
  lng: number
  lat: number
}

export interface CircleGeometry {
  fenceType: 'CIRCLE'
  centerLng: number
  centerLat: number
  radiusM: number
}

export interface PolygonGeometry {
  fenceType: 'POLYGON'
  vertices: Vertex[]
}

export type DraftGeometry = CircleGeometry | PolygonGeometry

/** 返回空字符串表示合法；否则返回面向用户的错误说明。 */
export function validateGeometry(geometry: DraftGeometry | null): string {
  if (!geometry) {
    return '请先在地图上绘制围栏'
  }
  if (geometry.fenceType === 'CIRCLE') {
    if (!coordinateValid(geometry.centerLng, geometry.centerLat)) {
      return '围栏坐标超出 WGS84 经纬度范围'
    }
    if (!Number.isFinite(geometry.radiusM)) {
      return '圆形围栏必须提供半径'
    }
    if (geometry.radiusM < MIN_RADIUS_M || geometry.radiusM > MAX_RADIUS_M) {
      return '圆形围栏半径必须在 10 到 100000 米之间'
    }
    return ''
  }
  const vertices = geometry.vertices
  if (vertices.length < MIN_VERTICES || vertices.length > MAX_VERTICES) {
    return '多边形围栏顶点数必须在 3 到 20 之间'
  }
  for (let i = 0; i < vertices.length; i++) {
    const vertex = vertices[i]
    if (!vertex || !coordinateValid(vertex.lng, vertex.lat)) {
      return '围栏坐标超出 WGS84 经纬度范围'
    }
    if (samePoint(vertex, vertices[(i + 1) % vertices.length])) {
      return '多边形围栏不能包含重复相邻顶点'
    }
  }
  if (hasSelfIntersection(vertices)) {
    return '多边形围栏不能自交'
  }
  if (approximateAreaM2(vertices) < MIN_AREA_M2) {
    return '多边形围栏面积不能为 0'
  }
  return ''
}

function coordinateValid(lng: number, lat: number): boolean {
  return Number.isFinite(lng) && Number.isFinite(lat)
    && lng >= -180 && lng <= 180 && lat >= -90 && lat <= 90
}

function samePoint(a: Vertex, b: Vertex): boolean {
  return a.lng === b.lng && a.lat === b.lat
}

function hasSelfIntersection(vertices: Vertex[]): boolean {
  const n = vertices.length
  for (let i = 0; i < n; i++) {
    const a = vertices[i]
    const b = vertices[(i + 1) % n]
    for (let j = i + 1; j < n; j++) {
      // 相邻边共享端点，不视为自交；最后一条边与第一条边同样相邻。
      if (j === (i + 1) % n || i === (j + 1) % n) {
        continue
      }
      const c = vertices[j]
      const d = vertices[(j + 1) % n]
      if (segmentsIntersect(a, b, c, d)) {
        return true
      }
    }
  }
  return false
}

function segmentsIntersect(a: Vertex, b: Vertex, c: Vertex, d: Vertex): boolean {
  const d1 = cross(c, d, a)
  const d2 = cross(c, d, b)
  const d3 = cross(a, b, c)
  const d4 = cross(a, b, d)
  if (((d1 > EPSILON && d2 < -EPSILON) || (d1 < -EPSILON && d2 > EPSILON))
    && ((d3 > EPSILON && d4 < -EPSILON) || (d3 < -EPSILON && d4 > EPSILON))) {
    return true
  }
  // 共线且落在对方线段内也算自交，避免重复顶点或重叠边绕过检查。
  return (Math.abs(d1) <= EPSILON && onSegment(c, d, a))
    || (Math.abs(d2) <= EPSILON && onSegment(c, d, b))
    || (Math.abs(d3) <= EPSILON && onSegment(a, b, c))
    || (Math.abs(d4) <= EPSILON && onSegment(a, b, d))
}

function cross(a: Vertex, b: Vertex, p: Vertex): number {
  return (b.lng - a.lng) * (p.lat - a.lat) - (b.lat - a.lat) * (p.lng - a.lng)
}

function onSegment(a: Vertex, b: Vertex, p: Vertex): boolean {
  return p.lng >= Math.min(a.lng, b.lng) - EPSILON
    && p.lng <= Math.max(a.lng, b.lng) + EPSILON
    && p.lat >= Math.min(a.lat, b.lat) - EPSILON
    && p.lat <= Math.max(a.lat, b.lat) + EPSILON
}

/** 鞋带公式先平移到首点附近，再按中心纬度换算为平方米，避免大数相减丢精度。 */
export function approximateAreaM2(vertices: Vertex[]): number {
  if (vertices.length < 3) {
    return 0
  }
  const originLng = vertices[0].lng
  const originLat = vertices[0].lat
  let twiceArea = 0
  let meanLat = 0
  for (let i = 0; i < vertices.length; i++) {
    const a = vertices[i]
    const b = vertices[(i + 1) % vertices.length]
    const ax = a.lng - originLng
    const ay = a.lat - originLat
    const bx = b.lng - originLng
    const by = b.lat - originLat
    twiceArea += ax * by - bx * ay
    meanLat += a.lat
  }
  meanLat /= vertices.length
  const lngScale = Math.max(METRES_PER_DEGREE * Math.cos(meanLat * Math.PI / 180), 1)
  return Math.abs(twiceArea) / 2 * METRES_PER_DEGREE * lngScale
}