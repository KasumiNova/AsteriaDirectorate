# 舰船装配导出（Variant Export）

把游戏内配好的舰船装配导出为标准 `.variant` JSON，产物可直接移入 `contents/data/variants/` 被游戏作为 stock variant 加载。

- 入口类：`cn.kasuminova.astd.campaign.ASTDVariantExporter`（`astd-campaign` 模块，全部为静态入口）
- 输出目录：游戏根目录下 `saves/astd_variant_export/`（相对进程工作目录，不存在则自动创建）
- 文件名：`<variantId>_<yyyyMMdd-HHmmss>.variant`，同秒重名自动追加序号，绝不覆盖旧产物
- 每次导出在 `starsector.log` 打印完整绝对路径；IO 失败记录 error 日志并抛出异常
- JSON 字段对齐原版加载器（`HullVariantSpec(JSONObject)`）：必需字段
  `displayName / hullId / variantId / fluxVents / fluxCapacitors / weaponGroups(mode/autofire/weapons)` 始终写出；
  `goalVariant` 仅 true 时写出；`wings / modules / tags / sModdedBuiltIns` 仅非空时写出；
  `hullMods / permaMods / sMods` 始终写出（hullMods 不含内置船插）；`quality` 为 stock variant 惯例元数据（加载器忽略）

## runcode 调用示例（SSOptimizer）

```java
// 导出玩家舰队旗舰
cn.kasuminova.astd.campaign.ASTDVariantExporter.exportPlayerFlagship();

// 导出玩家舰队全部舰船成员（不含舰载机联队）
cn.kasuminova.astd.campaign.ASTDVariantExporter.exportPlayerFleet();

// 按舰体 id 过滤导出
cn.kasuminova.astd.campaign.ASTDVariantExporter.exportPlayerFleetByHullId("astd_xc_001");

// 导出任意 FleetMember（quality 可省略，默认 1.0）
com.fs.starfarer.api.campaign.FleetMemberAPI member = com.fs.starfarer.api.Global.getSector().getPlayerFleet().getFlagship();
cn.kasuminova.astd.campaign.ASTDVariantExporter.exportMember(member, 1.0f);

// 导出任意 ShipVariant 到指定目录
com.fs.starfarer.api.combat.ShipVariantAPI variant = member.getVariant();
cn.kasuminova.astd.campaign.ASTDVariantExporter.exportVariant(variant, 1.0f, new java.io.File("saves/astd_variant_export"));

// 只拿 JSON 不落盘
cn.kasuminova.astd.campaign.ASTDVariantExporter.toJson(variant, 1.0f).toString(4);
```

导出后打开 `starsector.log` 搜索 `[ASTDVariantExporter] Exported variant` 即可拿到完整文件路径。

## RPC 快捷导出（sso-debug）

`./gradlew runGame` 启动的游戏默认已开启 SSOptimizer 调试端（127.0.0.1:8471），
载入存档后可直接用客户端脚本触发导出，无需手敲 runcode：

```bash
tools/astd_variant_export_rpc.py ping                        # 探活
tools/astd_variant_export_rpc.py export                      # 玩家舰队全部舰船
tools/astd_variant_export_rpc.py export --mode flagship      # 仅旗舰
tools/astd_variant_export_rpc.py export --mode hull:astd_xc_001
```

未带调试端启动的游戏实例，可在游戏内控制台（ConsoleCommands）执行一次即可热开启：

```
runcode System.setProperty("ssoptimizer.debug.enabled","true"); github.kasuminova.ssoptimizer.common.debug.DebugServerBootstrap.startIfEnabled();
```

工作流细节见 `.agents/skills/workflow-ssoptimizer-script-debug/SKILL.md`。

## 产物入库与验证

1. 把 `saves/astd_variant_export/*.variant` 复制到 `contents/data/variants/`（舰载机装配放 `contents/data/variants/fighters/`）。
2. 按需把 `variantId` / `displayName` 改为项目命名约定（如 `astd_xc_001_Standard`），并确认 `goalVariant` 是否保留。
3. 验证加载：
   - 直接验证：重启游戏，`starsector.log` 出现 `Loading variant [.../xxx.variant]` 且无 `already exists` / JSON 异常即加载成功；可在任务编辑器或 `Global.getSettings().getVariant("variantId")` 查到。
   - 若该装配需要进 ss-csv 生成流程（如被 ship_data.csv 的 `codexVariantId` 或生成器 entry 引用），按 ss-csv 规范先运行 `./gradlew :astd-csv:generateSsCsv` 检查产物，再考虑 `writeSsCsvToContents`（危险操作，需用户明确要求）。

注意：武器分组未保存的装配（`hasUnassignedWeapons`）会在导出时克隆并自动分组，不会改动游戏内原装配。
