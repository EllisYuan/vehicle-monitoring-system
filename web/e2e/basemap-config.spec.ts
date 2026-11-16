import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'
import {
  BASEMAP_PROVIDER_DEFAULT_ATTRIBUTION,
  WMTS_MATRIX_IDS,
  resolveBasemap,
  wmtsResolutions,
} from '../src/features/monitoring/basemap'

const baseEnv = {
  roadUrl: 'https://t0.tianditu.gov.cn/vec_w/wmts?tk={key}',
  labelUrl: 'https://t0.tianditu.gov.cn/cva_w/wmts?tk={key}',
  key: 'fixture-key-not-real',
}

// 配置核对不依赖真实天地图 key；真实底图与对齐仍属未执行项。
test.describe('底图配置解析', () => {
  test('道路与注记图层参数来自配置，key 仅替换到请求地址', () => {
    const result = resolveBasemap(baseEnv)
    expect(result.error).toBe('')
    expect(result.attribution).toBe(BASEMAP_PROVIDER_DEFAULT_ATTRIBUTION)
    expect(result.layers.map(layer => layer.role)).toEqual(['road', 'label'])
    expect(result.layers[0]).toMatchObject({ layer: 'vec', style: 'default', format: 'tiles', matrixSet: 'w' })
    expect(result.layers[1]).toMatchObject({ layer: 'cva', style: 'default', format: 'tiles', matrixSet: 'w' })
    expect(result.layers[0].url).toContain('tk=fixture-key-not-real')
    expect(result.layers[0].url).not.toContain('{key}')
  })

  test('key 中的特殊字符编码后进入请求地址，错误提示不含 key 值', () => {
    const secret = 'sentinel&key/值'
    const result = resolveBasemap({ ...baseEnv, key: secret })
    expect(result.layers[0].url).toContain(`tk=${encodeURIComponent(secret)}`)
    expect(result.error).not.toContain(secret)
  })

  test('仅支持球面墨卡托 matrixSet=w', () => {
    const result = resolveBasemap({ ...baseEnv, matrixSet: 'c' })
    expect(result.error).toContain('matrixSet=w')
    expect(result.layers).toHaveLength(0)
    expect(result.attribution).toBeNull()
  })

  test('缺少道路或注记地址时不请求底图且不显示 attribution', () => {
    const result = resolveBasemap({ ...baseEnv, labelUrl: '' })
    expect(result.error).toContain('底图未配置')
    expect(result.layers).toHaveLength(0)
    expect(result.attribution).toBeNull()
  })

  test('地址含 {key} 占位符但未配置 key 时给出配置错误而不发请求', () => {
    const result = resolveBasemap({ roadUrl: baseEnv.roadUrl, labelUrl: baseEnv.labelUrl })
    expect(result.error).toContain('key')
    expect(result.layers).toHaveLength(0)
    expect(result.attribution).toBeNull()
  })

  test('自定义 layer/style/format 与 attribution 生效', () => {
    const result = resolveBasemap({ ...baseEnv, roadLayer: 'img', labelLayer: 'cia', style: 'night', format: 'image/png', attribution: ' 示例地图供应商 ' })
    expect(result.layers[0]).toMatchObject({ layer: 'img', style: 'night', format: 'image/png' })
    expect(result.layers[1].layer).toBe('cia')
    expect(result.attribution).toBe('示例地图供应商')
  })

  test('WMTS 矩阵编号与分辨率对齐服务 GetCapabilities 元数据', () => {
    const metadata = JSON.parse(readFileSync(new URL('./fixtures/tianditu-wmts-metadata.json', import.meta.url), 'utf8')) as {
      supportedCrs: string
      tileMatrixSet: string
      matrices: { id: string; scaleDenominator: number; matrixWidth: number }[]
    }
    expect(metadata.tileMatrixSet).toBe('w')
    expect(metadata.supportedCrs).toBe('urn:ogc:def:crs:EPSG::900913')
    expect(WMTS_MATRIX_IDS).toEqual(metadata.matrices.map(matrix => matrix.id))
    const maxResolution = 40075016.68557849 / 256
    const resolutions = wmtsResolutions(maxResolution)
    expect(resolutions).toHaveLength(metadata.matrices.length)
    // OGC 标准按 0.28mm 像素换算；天地图元数据使用 96 DPI（25.4/96 mm）。
    const pixelSizeMeters = 25.4 / 96 / 1000
    metadata.matrices.forEach((matrix, index) => {
      const fromMetadata = matrix.scaleDenominator * pixelSizeMeters
      expect(Math.abs(resolutions[index] - fromMetadata) / fromMetadata).toBeLessThan(1e-6)
      expect(matrix.matrixWidth).toBe(2 ** Number(matrix.id))
    })
  })

  test('已知点的 Web Mercator 坐标与 14 级瓦片行列号可作为真实底图对齐基准', () => {
    const metadata = JSON.parse(readFileSync(new URL('./fixtures/tianditu-wmts-metadata.json', import.meta.url), 'utf8')) as {
      matrices: { id: string; scaleDenominator: number }[]
    }
    const level14 = metadata.matrices.find(matrix => matrix.id === '14')!
    // 由服务元数据独立换算分辨率，不使用组件内的 wmtsResolutions。
    const resolution = level14.scaleDenominator * 25.4 / 96 / 1000
    const origin = 20037508.3427892
    const knownPoints = [
      { name: '北京天安门', lng: 116.3913, lat: 39.9075, x: 12956620.248767, y: 4852509.522163, col: 13489, row: 6208 },
      { name: '上海外滩', lng: 121.49, lat: 31.24, x: 13524204.936475, y: 3663957.09309, col: 13721, row: 6694 },
      { name: '广州塔', lng: 113.3245, lat: 23.1066, x: 12615225.634402, y: 2644915.204952, col: 13349, row: 7110 },
    ]
    for (const point of knownPoints) {
      const x = point.lng * origin / 180
      const y = Math.log(Math.tan((90 + point.lat) * Math.PI / 360)) * origin / Math.PI
      expect(x, point.name).toBeCloseTo(point.x, 3)
      expect(y, point.name).toBeCloseTo(point.y, 3)
      expect(Math.floor((x + origin) / (resolution * 256)), point.name).toBe(point.col)
      expect(Math.floor((origin - y) / (resolution * 256)), point.name).toBe(point.row)
    }
  })})

