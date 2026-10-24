export type ShipClass = 'CAPITAL_SHIP' | 'CRUISER' | 'DESTROYER' | 'FRIGATE'
export type ShipSeries = 'ARC' | 'LENS'

export interface Ship {
  id: string
  name: string
  designation: string
  series: ShipSeries
  shipClass: ShipClass
  sprite: string
  bloom: string
}

const ship = (
  id: string,
  name: string,
  designation: string,
  series: ShipSeries,
  shipClass: ShipClass,
): Ship => ({
  id,
  name,
  designation,
  series,
  shipClass,
  sprite: `${id}.png`,
  bloom: `${id}_bloom.png`,
})

export const ships: Ship[] = [
  ship('astd_xc_001', '星坠', '战列舰', 'ARC', 'CAPITAL_SHIP'),
  ship('astd_xc_002', '星翼', '驱逐舰', 'ARC', 'DESTROYER'),
  ship('astd_xc_101', '熔壁', '巡洋舰', 'ARC', 'CRUISER'),
  ship('astd_xc_102', '烽燧', '主力舰', 'ARC', 'CAPITAL_SHIP'),
  ship('astd_xc_103', '列星', '驱逐舰', 'ARC', 'DESTROYER'),
  ship('astd_xc_104', '逐电', '护卫舰', 'ARC', 'FRIGATE'),
  ship('astd_zw_001', '决明', '指挥巡洋舰', 'LENS', 'CRUISER'),
  ship('astd_zw_002', '密蒙', '相位巡洋舰', 'LENS', 'CRUISER'),
  ship('astd_zw_101', '舜华', '相位驱逐舰', 'LENS', 'DESTROYER'),
  ship('astd_zw_102', '飞蓬', '航空战列舰', 'LENS', 'CAPITAL_SHIP'),
  ship('astd_zw_103', '茑萝', '相位护航航母', 'LENS', 'DESTROYER'),
  {
    ...ship('astd_lh_001', '衔星 (ARC)', '先进炮艇', 'ARC', 'FRIGATE'),
    sprite: 'astd_ast_101.png',
    bloom: 'astd_ast_101_arc_bloom.png',
  },
  {
    ...ship('astd_lh_002', '衔星 (LENS)', '先进炮艇', 'LENS', 'FRIGATE'),
    sprite: 'astd_ast_101.png',
    bloom: 'astd_ast_101_lens_bloom.png',
  },
]

export const shipById = new Map(ships.map((item) => [item.id, item]))

export const heroShipIds = [
  'astd_xc_001',
  'astd_zw_001',
  'astd_zw_002',
  'astd_xc_102',
  'astd_zw_102',
]

export const classLabels: Record<ShipClass, string> = {
  CAPITAL_SHIP: '主力舰',
  CRUISER: '巡洋舰',
  DESTROYER: '驱逐舰',
  FRIGATE: '护卫舰',
}

export const shipSources = ships.flatMap((item) => [
  `/assets/ships/${item.sprite}`,
  `/assets/ships/${item.bloom}`,
])
