#!/usr/bin/env python3
"""ASTD 装配导出调试客户端（sso-debug HTTP RPC）。

经 SSOptimizer 游戏内调试服务（127.0.0.1:8471）动态编译并执行
cn.kasuminova.astd.campaign.ASTDVariantExporter 导出调用，
产物落在游戏进程工作目录 saves/astd_variant_export/。

前置：
- 游戏经 ./gradlew runGame 启动（默认已注入 -Dssoptimizer.debug.enabled=true），
  或启动时携带该 JVM 属性；
- 游戏内已载入存档（导出对象是玩家舰队）。

用法：
    tools/astd_variant_export_rpc.py ping
    tools/astd_variant_export_rpc.py stats
    tools/astd_variant_export_rpc.py export                     # 导出玩家舰队全部舰船
    tools/astd_variant_export_rpc.py export --mode flagship     # 仅旗舰
    tools/astd_variant_export_rpc.py export --mode hull:astd_xc_001

工作流细节见 .agents/skills/workflow-ssoptimizer-script-debug/SKILL.md。
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

SCRIPT_ID = "astd_variant_export"

EXPORT_SCRIPT_SOURCE = """
public class AstdVariantExportScript implements github.kasuminova.ssoptimizer.api.debug.DebugScript {
    @Override
    public Object invoke(github.kasuminova.ssoptimizer.api.debug.DebugContext ctx,
                         java.util.Map<String, Object> args) throws Exception {
        if (com.fs.starfarer.api.Global.getSector() == null
                || com.fs.starfarer.api.Global.getSector().getPlayerFleet() == null) {
            throw new IllegalStateException("玩家舰队不存在：请先载入存档再导出");
        }
        String mode = String.valueOf(args.getOrDefault("mode", "fleet"));
        java.util.List<java.io.File> files;
        switch (mode) {
            case "flagship":
                return java.util.List.of(
                        cn.kasuminova.astd.campaign.ASTDVariantExporter.exportPlayerFlagship()
                                .getAbsolutePath());
            case "fleet":
                files = cn.kasuminova.astd.campaign.ASTDVariantExporter.exportPlayerFleet();
                break;
            default:
                if (mode.startsWith("hull:")) {
                    files = cn.kasuminova.astd.campaign.ASTDVariantExporter
                            .exportPlayerFleetByHullId(mode.substring("hull:".length()));
                } else {
                    throw new IllegalArgumentException(
                            "未知导出模式: " + mode + "（可选 fleet / flagship / hull:<hullId>）");
                }
        }
        java.util.List<String> paths = new java.util.ArrayList<>();
        for (java.io.File file : files) {
            paths.add(file.getAbsolutePath());
        }
        ctx.log("[astd_variant_export] mode=" + mode + ", exported=" + paths.size());
        return paths;
    }
}
"""


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def default_game_dir() -> Path:
    props = repo_root() / "gradle.properties"
    for line in props.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("starsector.gameDir="):
            return Path(line.split("=", 1)[1].strip())
    sys.exit("gradle.properties 缺少 starsector.gameDir，请用 --game-dir 显式指定")


def read_token(game_dir: Path) -> str:
    token_file = game_dir / "ssoptimizer-debug-output" / "debug-token"
    if not token_file.is_file():
        sys.exit(
            f"未找到调试 token：{token_file}\n"
            "调试服务未开启或未生成本次 token。确认游戏以 -Dssoptimizer.debug.enabled=true 启动。"
        )
    return token_file.read_text(encoding="utf-8").strip()


def rpc(port: int, token: str, method: str, params: dict | None = None) -> object:
    payload: dict = {"jsonrpc": "2.0", "method": method, "id": 1}
    if params is not None:
        payload["params"] = params
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/rpc",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.URLError as exc:
        sys.exit(f"RPC 不可达（127.0.0.1:{port}）：{exc}\n确认游戏在运行且调试服务已开启。")
    if "error" in body:
        error = body["error"]
        sys.exit(f"RPC 错误 {error.get('code')}: {error.get('message')}")
    return body.get("result")


def cmd_export(args: argparse.Namespace, port: int, token: str) -> None:
    compile_result = rpc(
        port,
        token,
        "script_compile",
        {
            "scriptId": SCRIPT_ID,
            "className": "AstdVariantExportScript",
            "source": EXPORT_SCRIPT_SOURCE,
        },
    )
    print(f"脚本已编译: {compile_result}")
    invoke_result = rpc(
        port,
        token,
        "script_invoke",
        {"scriptId": SCRIPT_ID, "args": {"mode": args.mode}, "threadMode": "main"},
    )
    if isinstance(invoke_result, list):
        print(f"导出 {len(invoke_result)} 个装配：")
        for path in invoke_result:
            print(f"  {path}")
    else:
        print(f"导出结果: {invoke_result}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--game-dir", type=Path, default=None, help="游戏根目录（默认读 gradle.properties）")
    parser.add_argument("--port", type=int, default=8471, help="sso-debug 端口（默认 8471）")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("ping", help="探活")
    sub.add_parser("stats", help="debug_stats：drainCount 增长说明主循环在跑")
    export_parser = sub.add_parser("export", help="编译并执行装配导出")
    export_parser.add_argument(
        "--mode",
        default="fleet",
        help="fleet（默认，全舰队）/ flagship（仅旗舰）/ hull:<hullId>（按舰体过滤）",
    )
    args = parser.parse_args()

    game_dir = args.game_dir or default_game_dir()
    token = read_token(game_dir)

    if args.command == "ping":
        print(f"pong: {rpc(args.port, token, 'ping')}")
    elif args.command == "stats":
        print(json.dumps(rpc(args.port, token, "debug_stats"), ensure_ascii=False, indent=2))
    elif args.command == "export":
        cmd_export(args, args.port, token)


if __name__ == "__main__":
    main()
