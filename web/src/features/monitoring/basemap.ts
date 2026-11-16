/** 底图配置解析：不依赖 Vite 运行时，便于用公开服务元数据核对参数。 */

export interface BasemapEnv {
  roadUrl?: string
  labelUrl?: string
  key?: string
  roadLayer?: string
  labelLayer?: string
  matrixSet?: string
  style?: string
  format?: string
  attribution?: string
}

export interface BasemapLayerSpec {
  role: 'road' | 'label'
  url: string
  layer: string
  style: string
  format: string
  matrixSet: string
}

export interface BasemapResolution {
  layers: BasemapLayerSpec[]
  /** 空字符串表示配置可用；否则为面向用户的配置错误。 */
  error: string
  /** 仅在实际配置底图时返回；未配置或配置错误时为 null。 */
  attribution: string | null
}

export const BASEMAP_PROVIDER_DEFAULT_ATTRIBUTION = '天地图 · 国家地理信息公共服务平台'
export const SUPPORTED_MATRIX_SET = 'w'

/**
 * 天地图球面墨卡托 WMTS 的 TileMatrix 编号从 1 开始（GetCapabilities 中 vec 为 1—18、cva 为 1—19）。
 * 编号 N 对应分辨率 maxResolution / 2^N；当前地图最大缩放为 18，因此道路与注记共用 1—18。
 */
export const WMTS_MATRIX_IDS = Array.from({ length: 18 }, (_, index) => String(index + 1))

export function wmtsResolutions(maxResolution: number, count = WMTS_MATRIX_IDS.length): number[] {
  return Array.from({ length: count }, (_, index) => maxResolution / 2 ** (index + 1))
}

export function resolveBasemap(env: BasemapEnv): BasemapResolution {
  const matrixSet = (env.matrixSet || SUPPORTED_MATRIX_SET).trim()
  if (matrixSet !== SUPPORTED_MATRIX_SET) {
    return {
      layers: [],
      error: '底图配置错误：当前仅支持球面墨卡托（matrixSet=w），车辆列表与定位数据仍可使用。',
      attribution: null,
    }
  }
  const roadUrl = (env.roadUrl || '').trim()
  const labelUrl = (env.labelUrl || '').trim()
  if (!roadUrl || !labelUrl) {
    return {
      layers: [],
      error: '底图未配置，道路与注记服务接入后可显示；车辆列表与定位数据仍可使用。',
      attribution: null,
    }
  }
  const key = (env.key || '').trim()
  if (!key && [roadUrl, labelUrl].some(url => url.includes('{key}'))) {
    return {
      layers: [],
      error: '底图未配置访问 key，道路与注记服务接入后可显示；车辆列表与定位数据仍可使用。',
      attribution: null,
    }
  }
  const withKey = (url: string) => url.replaceAll('{key}', encodeURIComponent(key))
  const common = {
    style: (env.style || 'default').trim(),
    format: (env.format || 'tiles').trim(),
    matrixSet,
  }
  return {
    layers: [
      { role: 'road', url: withKey(roadUrl), layer: (env.roadLayer || 'vec').trim(), ...common },
      { role: 'label', url: withKey(labelUrl), layer: (env.labelLayer || 'cva').trim(), ...common },
    ],
    error: '',
    attribution: (env.attribution || '').trim() || BASEMAP_PROVIDER_DEFAULT_ATTRIBUTION,
  }
}
