# 舰船生产与随机订单限制

所有模组船体必须在 `ship_data` 条目的 `tags` 中声明 `no_sell, no_dealer`。战机联队必须同时在 `wing_data` 条目的 `tags` 中声明这两个标签，生产合同读取联队规格上的标签。

当前覆盖 19 个船体和 5 个联队。既有的 `astd_unique`、`astd_production`、战机定位及内置联队标签继续保留。

## 原版检查位置

依据当前 Starsector 0.98a 原版实现：

| 标签 | 检查位置 | 生效规则 |
| --- | --- | --- |
| `no_sell` | `BaseSubmarketPlugin.addShips`、`addFighters` | 从势力市场随机售船、战机 LPC 供货中排除 |
| `no_sell` | `CustomProductionContract.addMilitaryBlueprints` | 从使用 NPC 势力已知蓝图的军方生产合同目录中排除 |
| `no_dealer` | `CustomProductionContract.addArmsDealerBlueprints` | 从军火商随机生产目录中排除，即使同时存在 `dealer` 标签也会排除 |

军火商候选需要同时满足 `!no_dealer && (!no_sell || dealer) && !restricted`。同时声明 `no_sell` 和 `no_dealer`，可以覆盖普通供货、军方合同和军火商随机合同入口。

这两个标签控制供货与合同候选。固定任务按舰船 ID 生成、战后回收，以及玩家通过已有蓝图进行生产，分别沿用各自规则。

## 修改与验证

修改来源：

- [星坠船体](../../modules/internal/astd-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/entries/catalog/shipdata/arc/Catalog_ShipData_ARC.kt)
- [紫菀船体](../../modules/internal/astd-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/entries/catalog/shipdata/lens/Catalog_ShipData_LENS.kt)
- [联合船体](../../modules/internal/astd-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/entries/catalog/shipdata/joint/Catalog_ShipData_JOINT.kt)
- [战机联队](../../modules/internal/astd-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/entries/catalog/wingdata/lens/Catalog_WingData_LENS.kt)

运行 `./gradlew :astd-csv:generateSsCsv` 后，必须检查全部船体和联队均包含这两个标签，原有标签与其他字段保持一致。生成物仅写入构建目录，通过 `deployMod` 部署。
