<script setup lang="ts">
/* global HTMLDivElement, ResizeObserver, setTimeout, clearTimeout */
import { onMounted, onUnmounted, ref, watch } from 'vue'
import Map from 'ol/Map.js'
import View from 'ol/View.js'
import Feature from 'ol/Feature.js'
import Point from 'ol/geom/Point.js'
import LineString from 'ol/geom/LineString.js'
import TileLayer from 'ol/layer/Tile.js'
import VectorLayer from 'ol/layer/Vector.js'
import VectorSource from 'ol/source/Vector.js'
import WMTS from 'ol/source/WMTS.js'
import WMTSTileGrid from 'ol/tilegrid/WMTS.js'
import { fromLonLat, get as getProjection } from 'ol/proj.js'
import { getTopLeft, getWidth } from 'ol/extent.js'
import { defaults as defaultControls } from 'ol/control/defaults.js'
import { Circle, Fill, Stroke, Style } from 'ol/style.js'
import { resolveBasemap, wmtsResolutions, WMTS_MATRIX_IDS, type BasemapLayerSpec } from '../../monitoring/basemap'
import type { TrackView } from '../api'
import 'ol/ol.css'

const props = defineProps<{
  track: TrackView | null
  marker: { lng: number; lat: number } | null
}>()

const target = ref<HTMLDivElement>()
const error = ref('')
const basemapLoading = ref(false)
const segmentCount = ref(0)
const gapCount = ref(0)
const featureCount = ref(0)
const markerLng = ref('')
const markerLat = ref('')
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
const trackSource = new VectorSource()
const markerSource = new VectorSource()
let map: Map | undefined
let observer: ResizeObserver | undefined
let sources: WMTS[] = []
let basemapTimeout: ReturnType<typeof setTimeout> | undefined
let pendingTiles = 0
let failed = false
let fittedKey = ''

const lineStyle = new Style({
  stroke: new Stroke({ color: '#24669c', width: 4 }),
})
const pointStyle = new Style({
  image: new Circle({ radius: 4, fill: new Fill({ color: '#24669c' }), stroke: new Stroke({ color: '#fff', width: 1 }) }),
})
const gapStyle = new Style({
  image: new Circle({ radius: 4, fill: new Fill({ color: '#8a5a2b' }), stroke: new Stroke({ color: '#fff', width: 1 }) }),
})
const markerStyle = new Style({
  image: new Circle({ radius: 8, fill: new Fill({ color: '#c0362c' }), stroke: new Stroke({ color: '#fff', width: 2 }) }),
})

function tileLayer(spec: BasemapLayerSpec): TileLayer<WMTS> {
  const projection = getProjection('EPSG:3857')!
  const extent = projection.getExtent()
  const maxResolution = getWidth(extent) / 256
  const source = new WMTS({
    url: spec.url, layer: spec.layer, matrixSet: spec.matrixSet,
    format: spec.format, style: spec.style, projection,
    tileGrid: new WMTSTileGrid({
      origin: getTopLeft(extent),
      // 天地图 TileMatrix 编号从 1 开始，编号 N 对应 maxResolution / 2^N。
      resolutions: wmtsResolutions(maxResolution),
      matrixIds: WMTS_MATRIX_IDS,
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
    error.value = '底图加载失败：请检查网络与访问 key 配置，轨迹仍可查看。'
    pendingTiles = Math.max(0, pendingTiles - 1)
    basemapLoading.value = false
  })
  sources.push(source)
  return new TileLayer({ source })
}

function redrawTrack() {
  trackSource.clear()
  segmentCount.value = props.track?.segments.length ?? 0
  gapCount.value = props.track?.gaps.length ?? 0
  for (const segment of props.track?.segments ?? []) {
    const points = segment.points.filter(point => Number.isFinite(point.lng) && Number.isFinite(point.lat))
    if (points.length >= 2) {
      const feature = new Feature({
        geometry: new LineString(points.map(point => fromLonLat([point.lng, point.lat]))),
      })
      feature.setStyle(lineStyle)
      trackSource.addFeature(feature)
    }
    for (const point of points) {
      const feature = new Feature({ geometry: new Point(fromLonLat([point.lng, point.lat])) })
      feature.setStyle(pointStyle)
      trackSource.addFeature(feature)
    }
  }
  for (const gap of props.track?.gaps ?? []) {
    for (const time of [gap.startTime, gap.endTime]) {
      const point = findPoint(time)
      if (!point) continue
      const feature = new Feature({ geometry: new Point(fromLonLat([point.lng, point.lat])) })
      feature.setStyle(gapStyle)
      trackSource.addFeature(feature)
    }
  }
  featureCount.value = trackSource.getFeatures().length
  fitTrack()
}

function findPoint(time: string) {
  for (const segment of props.track?.segments ?? []) {
    const point = segment.points.find(candidate => candidate.gpsTime === time)
    if (point) return point
  }
  return null
}

function fitTrack() {
  if (!map || !props.track) return
  const key = props.track.vehicleId + ':' + props.track.from + ':' + props.track.to
  if (key === fittedKey) return
  fittedKey = key
  const features = trackSource.getFeatures()
  if (!features.length) return
  const extent = trackSource.getExtent()
  if (extent) map.getView().fit(extent, { padding: [60, 60, 60, 60], maxZoom: 16, duration: 0 })
}

function redrawMarker() {
  markerSource.clear()
  if (props.marker && Number.isFinite(props.marker.lng) && Number.isFinite(props.marker.lat)) {
    const feature = new Feature({ geometry: new Point(fromLonLat([props.marker.lng, props.marker.lat])) })
    feature.setStyle(markerStyle)
    markerSource.addFeature(feature)
    markerLng.value = props.marker.lng.toFixed(6)
    markerLat.value = props.marker.lat.toFixed(6)
  } else {
    markerLng.value = ''
    markerLat.value = ''
  }
}

function retryBasemap() {
  if (!sources.length) return
  clearTimeout(basemapTimeout)
  failed = false
  error.value = ''
  sources.forEach(source => source.refresh())
  basemapTimeout = setTimeout(() => {
    if (pendingTiles > 0 && !failed) error.value = '底图响应超时，轨迹数据仍可使用。'
    basemapLoading.value = false
  }, 12_000)
}

watch(() => props.track, () => { redrawTrack(); redrawMarker() }, { deep: true })
watch(() => props.marker, redrawMarker, { deep: true })

onMounted(() => {
  const layers: TileLayer<WMTS>[] = []
  if (basemap.error) error.value = basemap.error
  for (const spec of basemap.layers) layers.push(tileLayer(spec))
  map = new Map({ target: target.value, layers: [...layers,
    new VectorLayer({ source: trackSource }), new VectorLayer({ source: markerSource })],
    controls: defaultControls({ attribution: false, rotate: false }),
    view: new View({ center: fromLonLat([105, 35]), zoom: 4, minZoom: 2, maxZoom: 18 }) })
  observer = new ResizeObserver(() => map?.updateSize())
  if (target.value) observer.observe(target.value)
  redrawTrack()
  redrawMarker()
  if (sources.length) basemapTimeout = setTimeout(() => {
    if (pendingTiles > 0 && !failed) error.value = '底图响应超时，轨迹数据仍可使用。'
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
  trackSource.dispose()
  markerSource.dispose()
})
</script>

<template>
  <div
    class="track-map"
    data-testid="track-map"
    :data-segment-count="segmentCount"
    :data-gap-count="gapCount"
    :data-feature-count="featureCount"
    :data-marker-lng="markerLng"
    :data-marker-lat="markerLat"
  >
    <div
      ref="target"
      class="map-target"
      role="region"
      aria-label="轨迹地图，断点不连线"
      tabindex="0"
    />
    <div class="map-caption">
      <span class="line-dot" />轨迹段（分段绘制，不跨断点连线）<span class="caption-divider">·</span><span class="gap-dot" />断点边界
    </div>
    <div
      v-if="error"
      class="map-message"
      role="status"
      data-testid="track-map-error"
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
      v-if="attribution"
      class="attribution"
      data-testid="track-map-attribution"
    >
      {{ attribution }} <span>· OpenLayers</span>
    </div>
  </div>
</template>

<style scoped>
.track-map { position: relative; min-height: 460px; height: 100%; overflow: hidden; background: #e8edf0; }
.map-target { width: 100%; height: 100%; min-height: 460px; }
.map-caption { position: absolute; top: 14px; left: 54px; display: flex; gap: 8px; align-items: center; padding: 7px 10px; background: #ffffffed; border: 1px solid #d8e0e7; color: #43596d; font-size: 11px; pointer-events: none; }
.line-dot { width: 18px; height: 4px; background: #24669c; }
.gap-dot { width: 9px; height: 9px; background: #8a5a2b; border: 2px solid white; border-radius: 50%; }
.caption-divider { color: #a6b3bd; }
button { border: 1px solid #cbd7e0; border-radius: 3px; background: white; padding: 5px 9px; color: #24445f; cursor: pointer; font: inherit; font-size: 12px; }
.map-message { position: absolute; top: 60px; left: 16px; right: 16px; display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 10px 13px; background: #fffaf0f5; border: 1px solid #e5d8b6; color: #73501d; font-size: 12px; }
.map-message button { margin-left: auto; }
.attribution { position: absolute; bottom: 0; right: 0; max-width: 90%; padding: 4px 8px; background: #fffffff0; color: #506476; font-size: 10px; }
.attribution span { color: #718395; }
@media (max-width: 700px) { .track-map, .map-target { min-height: 340px; } .map-caption { top: auto; bottom: 22px; left: 10px; font-size: 10px; } }
</style>
