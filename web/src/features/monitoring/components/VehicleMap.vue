<script setup lang="ts">
/* global HTMLDivElement, ResizeObserver, setTimeout, clearTimeout */
import { onMounted, onUnmounted, ref, watch } from 'vue'
import Map from 'ol/Map.js'
import View from 'ol/View.js'
import Feature from 'ol/Feature.js'
import Point from 'ol/geom/Point.js'
import TileLayer from 'ol/layer/Tile.js'
import VectorLayer from 'ol/layer/Vector.js'
import VectorSource from 'ol/source/Vector.js'
import WMTS from 'ol/source/WMTS.js'
import WMTSTileGrid from 'ol/tilegrid/WMTS.js'
import { fromLonLat, get as getProjection } from 'ol/proj.js'
import { getTopLeft, getWidth } from 'ol/extent.js'
import { defaults as defaultControls } from 'ol/control/defaults.js'
import { Fill, Stroke, Style, RegularShape } from 'ol/style.js'
import { formatTime, type MonitorVehicle } from '../api'
import 'ol/ol.css'

const props = defineProps<{ vehicles: MonitorVehicle[]; selectedId: number | null }>()
const emit = defineEmits<{ select: [vehicleId: number] }>()
const target = ref<HTMLDivElement>()
const error = ref('')
const hover = ref<MonitorVehicle | null>(null)
const basemapLoading = ref(false)
const attribution = import.meta.env.VITE_WMTS_ATTRIBUTION || '天地图 · 国家地理信息公共服务平台'
const source = new VectorSource<Feature<Point>>()
let map: Map | undefined
let observer: ResizeObserver | undefined
let sources: WMTS[] = []
let basemapTimeout: ReturnType<typeof setTimeout> | undefined
let centered = false
let pendingTiles = 0
let failed = false

function tileLayer(urlTemplate: string, layer: string): TileLayer<WMTS> {
  const projection = getProjection('EPSG:3857')!
  const extent = projection.getExtent()
  const maxResolution = getWidth(extent) / 256
  const source = new WMTS({
    url: urlTemplate.replaceAll('{key}', encodeURIComponent(import.meta.env.VITE_WMTS_KEY || '')),
    layer, matrixSet: import.meta.env.VITE_WMTS_MATRIX_SET || 'w',
    format: import.meta.env.VITE_WMTS_FORMAT || 'tiles',
    style: import.meta.env.VITE_WMTS_STYLE || 'default', projection,
    tileGrid: new WMTSTileGrid({
      origin: getTopLeft(extent),
      resolutions: Array.from({ length: 19 }, (_, index) => maxResolution / 2 ** index),
      matrixIds: Array.from({ length: 19 }, (_, index) => String(index)),
    }),
    crossOrigin: 'anonymous', wrapX: false,
  })
  source.on('tileloadstart', () => { pendingTiles += 1; basemapLoading.value = true })
  source.on('tileloadend', () => {
    pendingTiles = Math.max(0, pendingTiles - 1)
    if (!pendingTiles) { basemapLoading.value = false; clearTimeout(basemapTimeout) }
  })
  source.on('tileloaderror', () => {
    failed = true
    error.value = '底图加载失败，车辆列表与定位数据仍可使用。'
    pendingTiles = Math.max(0, pendingTiles - 1)
    basemapLoading.value = false
  })
  sources.push(source)
  return new TileLayer({ source })
}

function updateVehicles() {
  source.clear()
  for (const vehicle of props.vehicles) {
    const position = vehicle.position
    if (!position?.fixValid || !Number.isFinite(position.lng) || !Number.isFinite(position.lat)) continue
    const selected = vehicle.vehicleId === props.selectedId
    const feature = new Feature({ geometry: new Point(fromLonLat([position.lng, position.lat])), vehicle })
    feature.setId(vehicle.vehicleId)
    // 单色方向标记；选中通过尺寸和表面 ring 表达，不以颜色编码车队。
    feature.setStyle(new Style({
      image: new RegularShape({ points: 3, radius: selected ? 12 : 9,
        rotation: position.directionDeg * Math.PI / 180, rotateWithView: true,
        fill: new Fill({ color: '#24669c' }), stroke: new Stroke({ color: '#fff', width: selected ? 3 : 2 }) }),
      zIndex: selected ? 2 : 1,
    }))
    source.addFeature(feature)
  }
  if (!centered && source.getFeatures().length) { fitVehicles(); centered = true }
}

function fitVehicles() {
  if (!source.getFeatures().length || !map) return
  const extent = source.getExtent()
  if (extent) map.getView().fit(extent, { padding: [70, 70, 70, 70], maxZoom: 14, duration: 0 })
}

function retryBasemap() {
  if (!sources.length) return
  clearTimeout(basemapTimeout)
  failed = false
  error.value = ''
  sources.forEach(source => source.refresh())
  basemapTimeout = setTimeout(() => {
    if (pendingTiles > 0 && !failed) error.value = '底图响应超时，车辆列表与定位数据仍可使用。'
    basemapLoading.value = false
  }, 12_000)
}

watch(() => props.vehicles, updateVehicles, { deep: true })
watch(() => props.selectedId, () => {
  updateVehicles()
  const position = props.vehicles.find(vehicle => vehicle.vehicleId === props.selectedId)?.position
  if (position?.fixValid) map?.getView().setCenter(fromLonLat([position.lng, position.lat]))
})
onMounted(() => {
  const roadUrl = import.meta.env.VITE_WMTS_ROAD_URL || ''
  const labelUrl = import.meta.env.VITE_WMTS_LABEL_URL || ''
  const matrixSet = import.meta.env.VITE_WMTS_MATRIX_SET || 'w'
  const layers: TileLayer<WMTS>[] = []
  if (matrixSet !== 'w') {
    error.value = '底图配置错误：当前仅支持 Web Mercator（matrixSet=w），车辆列表与定位数据仍可使用。'
  } else if (roadUrl && labelUrl) {
    layers.push(tileLayer(roadUrl, import.meta.env.VITE_WMTS_ROAD_LAYER || 'vec'))
    layers.push(tileLayer(labelUrl, import.meta.env.VITE_WMTS_LABEL_LAYER || 'cva'))
  } else error.value = '底图未配置，道路与注记服务接入后可显示；车辆列表与定位数据仍可使用。'
  map = new Map({ target: target.value, layers: [...layers, new VectorLayer({ source })],
    controls: defaultControls({ attribution: false, rotate: false }),
    view: new View({ center: fromLonLat([105, 35]), zoom: 4, minZoom: 2, maxZoom: 18 }) })
  map.on('singleclick', event => {
    const vehicle = map!.forEachFeatureAtPixel(event.pixel, feature => feature.get('vehicle') as MonitorVehicle,
      { hitTolerance: 12 })
    if (vehicle) emit('select', vehicle.vehicleId)
  })
  map.on('pointermove', event => {
    hover.value = event.dragging ? null : map!.forEachFeatureAtPixel(event.pixel,
      feature => feature.get('vehicle') as MonitorVehicle, { hitTolerance: 12 }) ?? null
    if (target.value) target.value.style.cursor = hover.value ? 'pointer' : ''
  })
  observer = new ResizeObserver(() => map?.updateSize())
  if (target.value) observer.observe(target.value)
  updateVehicles()
  if (sources.length) basemapTimeout = setTimeout(() => {
    if (pendingTiles > 0 && !failed) error.value = '底图响应超时，车辆列表与定位数据仍可使用。'
    basemapLoading.value = false
  }, 12_000)
})
onUnmounted(() => {
  clearTimeout(basemapTimeout)
  observer?.disconnect()
  map?.setTarget(undefined)
  map?.dispose()
  sources.forEach(source => source.dispose())
  sources = []
  source.dispose()
})
</script>

<template>
  <div
    class="map-workspace"
    data-testid="monitor-map"
  >
    <div
      ref="target"
      class="map-target"
      role="region"
      aria-label="车辆位置地图，详细数据也可通过车辆列表查看"
      tabindex="0"
    />
    <div class="map-caption">
      <span class="map-dot" />车辆有效位置 <span class="caption-divider">·</span> 方向箭头
    </div>
    <button
      class="fit-button"
      type="button"
      @click="fitVehicles"
    >
      显示全部位置
    </button>
    <div
      v-if="error"
      class="map-message"
      role="status"
      data-testid="monitor-map-error"
    >
      <strong>底图服务</strong><span>{{ error }}</span>
      <button
        v-if="sources.length"
        type="button"
        @click="retryBasemap"
      >
        重试底图
      </button>
    </div>
    <div
      v-else-if="basemapLoading"
      class="map-message"
      role="status"
    >
      正在加载道路与注记…
    </div>
    <div
      v-if="hover"
      class="map-tooltip"
      role="tooltip"
    >
      <strong>{{ hover.plateNo }}</strong>
      <span>{{ hover.position?.speedKph.toFixed(1) }} km/h · {{ hover.position?.directionDeg }}°</span>
      <small>有效定位 {{ formatTime(hover.position?.gpsTime) }}</small>
    </div>
    <div class="attribution">
      {{ attribution }} <span>· OpenLayers</span>
    </div>
  </div>
</template>

<style scoped>
.map-workspace { position: relative; min-height: 520px; height: 100%; overflow: hidden; background: #e8edf0; }
.map-target { width: 100%; height: 100%; min-height: 520px; }
.map-caption { position: absolute; top: 16px; left: 54px; display: flex; gap: 8px; align-items: center; padding: 7px 10px; background: #ffffffed; border: 1px solid #d8e0e7; color: #43596d; font-size: 11px; pointer-events: none; }
.map-dot { width: 9px; height: 9px; background: #24669c; border: 2px solid white; border-radius: 50%; }
.caption-divider { color: #a6b3bd; }
.fit-button { position: absolute; top: 16px; right: 16px; }
button { border: 1px solid #cbd7e0; border-radius: 3px; background: white; padding: 7px 10px; color: #24445f; cursor: pointer; font: inherit; font-size: 12px; }
button:hover { background: #eff5fa; }
button:focus-visible { outline: 2px solid #24669c; outline-offset: 2px; }
.map-message { position: absolute; top: 65px; left: 16px; right: 16px; display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 11px 14px; background: #fffaf0f5; border: 1px solid #e5d8b6; color: #73501d; font-size: 12px; }
.map-message strong { font-size: 11px; letter-spacing: 1px; }
.map-message button { margin-left: auto; padding: 4px 8px; }
.map-tooltip { position: absolute; left: 16px; bottom: 40px; display: grid; gap: 5px; min-width: 160px; padding: 12px; background: #fff; border: 1px solid #ccd7e0; box-shadow: 0 4px 16px #233b501a; color: #243c50; font-size: 12px; pointer-events: none; }
.map-tooltip small { color: #607487; }
.attribution { position: absolute; bottom: 0; right: 0; max-width: 90%; padding: 4px 8px; background: #fffffff0; color: #506476; font-size: 10px; }
.attribution span { color: #718395; }
@media (max-width: 700px) { .map-workspace, .map-target { min-height: 380px; } .map-caption { top: auto; bottom: 24px; left: 10px; font-size: 10px; } }
</style>
