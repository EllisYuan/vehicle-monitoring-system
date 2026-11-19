<script setup lang="ts">
/* global HTMLDivElement, ResizeObserver */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import Map from 'ol/Map.js'
import View from 'ol/View.js'
import Feature from 'ol/Feature.js'
import Point from 'ol/geom/Point.js'
import Polygon from 'ol/geom/Polygon.js'
import CircleGeometry from 'ol/geom/Circle.js'
import TileLayer from 'ol/layer/Tile.js'
import VectorLayer from 'ol/layer/Vector.js'
import VectorSource from 'ol/source/Vector.js'
import WMTS from 'ol/source/WMTS.js'
import WMTSTileGrid from 'ol/tilegrid/WMTS.js'
import Draw from 'ol/interaction/Draw.js'
import DoubleClickZoom from 'ol/interaction/DoubleClickZoom.js'
import { defaults as defaultControls } from 'ol/control/defaults.js'
import { fromLonLat, get as getProjection, toLonLat } from 'ol/proj.js'
import { getTopLeft, getWidth } from 'ol/extent.js'
import { Fill, Stroke, Style, Circle as CircleStyle } from 'ol/style.js'
import {
  resolveBasemap,
  wmtsResolutions,
  WMTS_MATRIX_IDS,
  type BasemapLayerSpec,
} from '../../monitoring/basemap'
import { validateGeometry, type DraftGeometry, type Vertex } from '../geometry'
import 'ol/ol.css'

const props = defineProps<{ geometry: DraftGeometry | null }>()
const emit = defineEmits<{ 'update:geometry': [geometry: DraftGeometry | null] }>()

const target = ref<HTMLDivElement>()
const mode = ref<'IDLE' | 'CIRCLE' | 'POLYGON'>('IDLE')
const basemapError = ref('')
const radiusInput = ref<number | ''>('')
const basemap = resolveBasemap({
  roadUrl: import.meta.env.VITE_WMTS_ROAD_URL,
  labelUrl: import.meta.env.VITE_WMTS_LABEL_URL,
  key: import.meta.env.VITE_WMTS_KEY,
  roadLayer: import.meta.env.VITE_WMTS_ROAD_LAYER,
  labelLayer: import.meta.env.VITE_WMTS_LABEL_LAYER,
  matrixSet: import.meta.env.VITE_WMTS_MATRIX_SET,
  style: import.meta.env.VITE_WMTS_STYLE,
  format: import.meta.env.VITE_WMTS_FORMAT,
  attribution: import.meta.env.VITE_WMTS_ATTRIBUTION,
})
const attribution = basemap.attribution ?? ''
const source = new VectorSource()
const validationMessage = computed(() => (props.geometry ? validateGeometry(props.geometry) : ''))
const radiusM = computed(() => (props.geometry?.fenceType === 'CIRCLE' ? props.geometry.radiusM : null))
const vertexCount = computed(() => (props.geometry?.fenceType === 'POLYGON'
  ? props.geometry.vertices.length : 0))
const canSubmit = computed(() => props.geometry !== null && validationMessage.value === '')

let map: Map | undefined
let observer: ResizeObserver | undefined
let draw: Draw | undefined
let doubleClickZoom: DoubleClickZoom | undefined
let sources: WMTS[] = []
let renderedSignature: string | null = null

function circleStyle(): Style[] {
  return [
    new Style({
      fill: new Fill({ color: 'rgba(47, 111, 237, 0.16)' }),
      stroke: new Stroke({ color: '#2f6fed', width: 2 }),
    }),
    new Style({
      image: new CircleStyle({
        radius: 5,
        fill: new Fill({ color: '#ffffff' }),
        stroke: new Stroke({ color: '#2f6fed', width: 2 }),
      }),
      geometry: (feature) => feature.get('center') as Point | undefined,
    }),
  ]
}

function tileLayer(spec: BasemapLayerSpec): TileLayer<WMTS> {
  const projection = getProjection('EPSG:3857')!
  const extent = projection.getExtent()
  const maxResolution = getWidth(extent) / 256
  const tileSource = new WMTS({
    url: spec.url,
    layer: spec.layer,
    matrixSet: spec.matrixSet,
    format: spec.format,
    style: spec.style,
    projection,
    tileGrid: new WMTSTileGrid({
      origin: getTopLeft(extent),
      // 天地图 TileMatrix 编号从 1 开始，编号 N 对应 maxResolution / 2^N。
      resolutions: wmtsResolutions(maxResolution),
      matrixIds: WMTS_MATRIX_IDS,
    }),
    crossOrigin: 'anonymous',
    wrapX: false,
  })
  tileSource.on('tileloaderror', () => {
    basemapError.value = '底图加载失败：请检查网络与访问 key 配置，围栏绘制仍可使用。'
  })
  sources.push(tileSource)
  return new TileLayer({ source: tileSource })
}

/** 米制半径按大圆距离换算，显示时采样为球面圆多边形，不用屏幕像素或经纬度差。 */
function destination(latDeg: number, lngDeg: number, bearingDeg: number, distanceM: number): [number, number] {
  const angular = distanceM / 6_371_008.8
  const bearing = bearingDeg * Math.PI / 180
  const lat1 = latDeg * Math.PI / 180
  const lng1 = lngDeg * Math.PI / 180
  const sinLat2 = Math.sin(lat1) * Math.cos(angular) + Math.cos(lat1) * Math.sin(angular) * Math.cos(bearing)
  const lat2 = Math.asin(Math.min(1, Math.max(-1, sinLat2)))
  const lng2 = lng1 + Math.atan2(
    Math.sin(bearing) * Math.sin(angular) * Math.cos(lat1),
    Math.cos(angular) - Math.sin(lat1) * sinLat2,
  )
  return [normalizeLng(lng2 * 180 / Math.PI), lat2 * 180 / Math.PI]
}

function haversineMeters(aLng: number, aLat: number, bLng: number, bLat: number): number {
  const dLat = (bLat - aLat) * Math.PI / 180
  const dLng = (bLng - aLng) * Math.PI / 180
  const h = Math.sin(dLat / 2) ** 2
    + Math.cos(aLat * Math.PI / 180) * Math.cos(bLat * Math.PI / 180) * Math.sin(dLng / 2) ** 2
  return 2 * 6_371_008.8 * Math.asin(Math.sqrt(Math.min(1, h)))
}

function normalizeLng(lng: number): number {
  let value = lng
  while (value > 180) value -= 360
  while (value < -180) value += 360
  return value
}

function polygonFromCircle(centerLng: number, centerLat: number, radius: number): Polygon {
  const ring: number[][] = []
  const steps = 96
  for (let i = 0; i < steps; i++) {
    const [lng, lat] = destination(centerLat, centerLng, (360 * i) / steps, radius)
    ring.push(fromLonLat([lng, lat]))
  }
  ring.push(ring[0])
  return new Polygon([ring])
}

function signature(geometry: DraftGeometry | null): string {
  return geometry ? JSON.stringify(geometry) : ''
}

function renderGeometry(geometry: DraftGeometry | null, fit: boolean) {
  source.clear()
  if (!geometry) {
    renderedSignature = ''
    return
  }
  if (geometry.fenceType === 'CIRCLE') {
    source.addFeature(new Feature({
      geometry: polygonFromCircle(geometry.centerLng, geometry.centerLat, geometry.radiusM),
    }))
    source.addFeature(new Feature({
      geometry: new Point(fromLonLat([geometry.centerLng, geometry.centerLat])),
      center: true,
    }))
  } else {
    const ring = geometry.vertices.map(vertex => fromLonLat([vertex.lng, vertex.lat]))
    ring.push(ring[0])
    source.addFeature(new Feature({ geometry: new Polygon([ring]) }))
    for (const vertex of geometry.vertices) {
      source.addFeature(new Feature({
        geometry: new Point(fromLonLat([vertex.lng, vertex.lat])),
        center: true,
      }))
    }
  }
  renderedSignature = signature(geometry)
  if (fit) {
    fitGeometry()
  }
}

function fitGeometry() {
  if (!map || source.isEmpty()) return
  const extent = source.getExtent()
  if (!extent) return
  map.getView().fit(extent, { padding: [60, 60, 60, 60], maxZoom: 16, duration: 0 })
}

function stopDraw() {
  if (draw && map) {
    map.removeInteraction(draw)
  }
  draw = undefined
  doubleClickZoom?.setActive(true)
  mode.value = 'IDLE'
}

function startDraw(next: 'CIRCLE' | 'POLYGON') {
  stopDraw()
  if (!map) return
  doubleClickZoom?.setActive(false)
  mode.value = next
  draw = new Draw({ source, type: next === 'CIRCLE' ? 'Circle' : 'Polygon' })
  draw.on('drawend', (event) => {
    const feature = event.feature
    let geometry: DraftGeometry | null = null
    if (next === 'CIRCLE') {
      const circle = feature.getGeometry() as CircleGeometry
      const center = toLonLat(circle.getCenter())
      const edge = toLonLat([circle.getCenter()[0] + circle.getRadius(), circle.getCenter()[1]])
      const radius = haversineMeters(center[0], center[1], edge[0], edge[1])
      geometry = {
        fenceType: 'CIRCLE',
        centerLng: round(center[0], 7),
        centerLat: round(center[1], 7),
        radiusM: Math.round(radius * 10) / 10,
      }
    } else {
      const polygon = feature.getGeometry() as Polygon
      const coords = polygon.getCoordinates()[0]
      const vertices: Vertex[] = coords.map(coordinate => {
        const [lng, lat] = toLonLat(coordinate)
        return { lng: round(lng, 7), lat: round(lat, 7) }
      })
      if (vertices.length > 1 && vertices[0].lng === vertices[vertices.length - 1].lng
        && vertices[0].lat === vertices[vertices.length - 1].lat) {
        vertices.pop()
      }
      geometry = { fenceType: 'POLYGON', vertices }
    }
    stopDraw()
    renderedSignature = signature(geometry)
    renderGeometry(geometry, false)
    emit('update:geometry', geometry)
  })
  map.addInteraction(draw)
}

function round(value: number, digits: number): number {
  const scale = 10 ** digits
  return Math.round(value * scale) / scale
}

function finishDraw() {
  if (draw) {
    draw.finishDrawing()
  }
}

function clearGeometry() {
  stopDraw()
  source.clear()
  renderedSignature = ''
  emit('update:geometry', null)
}

function applyRadius() {
  if (props.geometry?.fenceType !== 'CIRCLE') return
  const value = Number(radiusInput.value)
  if (!Number.isFinite(value)) return
  const geometry: DraftGeometry = {
    fenceType: 'CIRCLE',
    centerLng: props.geometry.centerLng,
    centerLat: props.geometry.centerLat,
    radiusM: value,
  }
  renderedSignature = signature(geometry)
  renderGeometry(geometry, false)
  emit('update:geometry', geometry)
}

watch(() => props.geometry, (geometry) => {
  if (signature(geometry) === renderedSignature) return
  renderGeometry(geometry, geometry !== null)
}, { deep: true })

watch(() => props.geometry, (geometry) => {
  radiusInput.value = geometry?.fenceType === 'CIRCLE' ? geometry.radiusM : ''
}, { immediate: true, deep: true })

onMounted(() => {
  const layers: TileLayer<WMTS>[] = []
  if (basemap.error) basemapError.value = basemap.error
  for (const spec of basemap.layers) layers.push(tileLayer(spec))
  map = new Map({
    target: target.value,
    layers: [...layers, new VectorLayer({ source, style: circleStyle() })],
    controls: defaultControls({ attribution: false, rotate: false }),
    view: new View({ center: fromLonLat([116.4, 39.9]), zoom: 12, minZoom: 2, maxZoom: 18 }),
  })
  doubleClickZoom = map.getInteractions().getArray()
    .find(interaction => interaction instanceof DoubleClickZoom) as DoubleClickZoom | undefined
  observer = new ResizeObserver(() => map?.updateSize())
  if (target.value) observer.observe(target.value)
  if (props.geometry) renderGeometry(props.geometry, true)
})

onUnmounted(() => {
  stopDraw()
  observer?.disconnect()
  map?.setTarget(undefined)
  map?.dispose()
  sources.forEach(tileSource => tileSource.dispose())
  sources = []
  source.dispose()
})
</script>

<template>
  <div
    class="geofence-map"
    data-testid="geofence-map"
    :data-mode="mode"
    :data-geometry-type="geometry?.fenceType ?? ''"
    :data-radius-m="radiusM ?? ''"
    :data-vertex-count="vertexCount"
    :data-valid="canSubmit ? 'true' : 'false'"
  >
    <div
      ref="target"
      class="map-target"
      role="region"
      aria-label="电子围栏绘制地图"
      tabindex="0"
    />
    <div class="draw-toolbar">
      <button
        type="button"
        data-testid="draw-circle"
        :class="{ active: mode === 'CIRCLE' }"
        @click="startDraw('CIRCLE')"
      >
        绘制圆形
      </button>
      <button
        type="button"
        data-testid="draw-polygon"
        :class="{ active: mode === 'POLYGON' }"
        @click="startDraw('POLYGON')"
      >
        绘制多边形
      </button>
      <button
        v-if="mode === 'POLYGON'"
        type="button"
        data-testid="finish-draw"
        @click="finishDraw"
      >
        完成绘制
      </button>
      <button
        v-if="geometry"
        type="button"
        data-testid="clear-geometry"
        @click="clearGeometry"
      >
        清除图形
      </button>
      <button
        v-if="geometry"
        type="button"
        data-testid="fit-geometry"
        @click="fitGeometry"
      >
        定位到围栏
      </button>
    </div>
    <div
      v-if="mode !== 'IDLE'"
      class="draw-hint"
      data-testid="geofence-draw-hint"
    >
      {{ mode === 'CIRCLE'
        ? '单击设置圆心，再次单击确定半径'
        : '依次单击顶点，再单击“完成绘制”闭合（3–20 个顶点）' }}
    </div>
    <div
      v-if="geometry && radiusM !== null"
      class="radius-control"
    >
      <label>
        半径（米）
        <input
          v-model.number="radiusInput"
          type="number"
          min="10"
          max="100000"
          step="1"
          data-testid="geofence-radius-input"
          @change="applyRadius"
        >
      </label>
    </div>
    <p
      v-if="geometry && validationMessage"
      class="draw-error"
      role="alert"
      data-testid="geofence-draw-error"
    >
      {{ validationMessage }}
    </p>
    <div
      v-if="basemapError"
      class="map-message"
      role="status"
      data-testid="geofence-map-error"
    >
      {{ basemapError }}
    </div>
    <div
      v-if="attribution"
      class="attribution"
      data-testid="geofence-map-attribution"
    >
      {{ attribution }} <span>· OpenLayers</span>
    </div>
  </div>
</template>

<style scoped>
.geofence-map { position: relative; min-height: 480px; height: 100%; overflow: hidden; background: #e8edf0; }
.map-target { width: 100%; height: 100%; min-height: 480px; }
.draw-toolbar { position: absolute; top: 12px; right: 12px; display: flex; flex-wrap: wrap; gap: 6px; }
button { border: 1px solid #cbd7e0; border-radius: 3px; background: white; padding: 7px 10px; color: #24445f; cursor: pointer; font: inherit; font-size: 12px; }
button:hover { background: #eff5fa; }
button.active { background: #24669c; border-color: #24669c; color: #fff; }
button:focus-visible { outline: 2px solid #24669c; outline-offset: 2px; }
.draw-hint { position: absolute; top: 12px; left: 12px; max-width: 55%; padding: 6px 9px; background: #fffffff0; border: 1px solid #d8e0e7; color: #43596d; font-size: 11px; pointer-events: none; }
.radius-control { position: absolute; bottom: 34px; left: 12px; padding: 8px 10px; background: #fffffff0; border: 1px solid #d8e0e7; font-size: 12px; }
.radius-control input { width: 8rem; margin-left: 6px; padding: 4px 6px; border: 1px solid #cfd8e3; border-radius: 3px; }
.draw-error { position: absolute; bottom: 34px; right: 12px; max-width: 55%; margin: 0; padding: 8px 10px; background: #fff4f2f0; border: 1px solid #e5b6ae; color: #8f2018; font-size: 12px; }
.map-message { position: absolute; top: 54px; left: 12px; right: 12px; padding: 10px 12px; background: #fffaf0f5; border: 1px solid #e5d8b6; color: #73501d; font-size: 12px; }
.attribution { position: absolute; bottom: 0; right: 0; max-width: 90%; padding: 4px 8px; background: #fffffff0; color: #506476; font-size: 10px; }
.attribution span { color: #718395; }
@media (max-width: 700px) { .geofence-map, .map-target { min-height: 360px; } .draw-hint { max-width: 70%; } }
</style>
