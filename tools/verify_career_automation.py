#!/usr/bin/env python3
"""ASTD 生涯集成自动化驱动与校验（参照 verify_ingame_vfx_automation.py 范式）。

流程（三个阶段，每阶段独立启动一次游戏进程）：
- main   ：程序化新开档（ASTD agent 标题屏 hook，固定种子）→ 世界生成检查 → 赏金主线全链路
           （序章签署 → 挂出 → 击毁 → 终端核销 → 批次 gating / 失败重挂 / 引力节点拔除 /
           清算进度节拍 / 章末钩子）→ 终端 UI → 四章末归档挂起 → 封存签署 → 执行官签发 →
           无限赏金 3 槽位挂出与换代 → 两次存档副本（签前/签后）。
- reload ：读“签后”存档 → 状态补齐校验（normalize / 世界生成幂等 / 延迟条目恢复与激活 /
           无限赏金槽位恢复）→ 终端重开冒烟。
- trade  ：读“签前”存档 → 交易选签署（报酬/关系/势力强度）→ 执行官行政特化任命。

替代点（需要真实玩家输入/等待的环节用桥接层等价入口替代）：
- 序章酒馆 BarEvent 对话逐点选项 → MainBountyBridge.acceptPrologueWorkOrder 直接接取；
- 战斗击毁 → ActiveBounty.endBounty(Succeeded) + 舰队 despawn（由 BountyCampaignManager 正常
  tick 消费，等效击毁后的阶段推进/待核销管线）；
- 结局延迟条目的 30 标准日等待 → force_due_effects 把激活时刻提前到当前；
- 终端手动点击 → 程序化 showInteractionDialog + BranchTerminalUi.open。

人工验收清单（无头环境无法验证的视觉项）见报告末尾。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
LAUNCHER = REPO_ROOT / "tools" / "career_automation_launch.sh"

# ─── 主线工单 key ───
XW = "astd_main_xw_c206_0447"
YJ_1102 = "astd_main_yj_c206_1102"
YJ_1103 = "astd_main_yj_c206_1103"
YJ_1198 = "astd_main_yj_c206_1198"
YJ_1201 = "astd_main_yj_c206_1201"
YJ_1204 = "astd_main_yj_c206_1204"
JJ_0007 = "astd_main_jj_c206_0007"
XC_0216 = "astd_main_xc_c208_0216"
XC_0217 = "astd_main_xc_c208_0217"
XC_0221 = "astd_main_xc_c208_0221"
ZW_0309 = "astd_main_zw_c208_0309"
ZX_1001 = "astd_main_zx_c208_1001"
ZX_0344 = "astd_main_zx_c207_0344"
ZX_0002 = "astd_main_zx_c208_0002"
ZQ_0001 = "astd_main_zq_c208_0001"

NODE_1 = "astd_story_aster_node_1"
NODE_2 = "astd_story_aster_node_2"
NODE_3 = "astd_story_aster_node_3"

HOOK_SEALED = "sealed_categories_unlocked"
HOOK_DUP = "duplicate_target_reposted"
HOOK_HALF = "half_line_work_order"
HOOK_FINAL = "final_receipt"

MARKET_MAIN_STATION = "astd_story_market_main_station"

FLOAT_EPS = 0.05


class PhaseAbort(Exception):
    """阶段内前序步骤失败/游戏进程退出，剩余检查项记为阻塞。"""


class Checker:
    def __init__(self) -> None:
        self.rows: list[tuple[str, str, bool, str]] = []

    def check(self, phase: str, item: str, ok: bool, detail: str = "") -> bool:
        self.rows.append((phase, item, bool(ok), detail))
        mark = "PASS" if ok else "FAIL"
        print(f"  [{mark}] {phase} :: {item}" + (f" — {detail}" if detail else ""))
        return bool(ok)

    def blocked(self, phase: str, item: str, reason: str) -> None:
        self.rows.append((phase, item, False, f"BLOCKED: {reason}"))
        print(f"  [BLOCK] {phase} :: {item} — {reason}")

    @property
    def failed(self) -> list[tuple[str, str, bool, str]]:
        return [r for r in self.rows if not r[2]]


def _read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _approx(actual: float, expected: float, eps: float = FLOAT_EPS) -> bool:
    return abs(float(actual) - expected) <= eps


class CareerDriver:
    """单阶段游戏进程 + 文件 IO 命令通道。"""

    def __init__(self, game_dir: Path, phase: str, timeout: int, new_game: bool, save_dir: str | None) -> None:
        self.game_dir = game_dir
        self.phase = phase
        self.timeout = timeout
        self.new_game = new_game
        self.save_dir = save_dir
        self.work_dir = game_dir / "career-automation" / phase
        self.log_file = game_dir / "career-automation-logs" / phase / "starsector.log"
        self.proc: subprocess.Popen | None = None
        self.seq = 0
        self.started_at = 0.0

    def __enter__(self) -> "CareerDriver":
        env = dict(os.environ)
        env["ASTD_CAREER_WORK_DIR"] = str(self.work_dir)
        if self.new_game:
            env["ASTD_CAREER_NEW_GAME"] = "true"
        elif self.save_dir:
            env["ASTD_CAREER_SAVE_DIR"] = self.save_dir
        # 启动器脚本要做 java 扫描等准备才清理工作目录，存在数十秒窗口：
        # 必须以启动时刻之后的 status.json 心跳为准，否则会吃到上一轮残留心跳
        self.started_at = time.time()
        self.proc = subprocess.Popen(
            ["bash", str(LAUNCHER), str(self.game_dir), str(self.timeout), self.phase],
            cwd=str(REPO_ROOT),
            env=env,
        )
        self._wait_status_ready()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        if self.proc is None:
            return
        # 无论阶段是否中途失败都尽力送 finish（游戏侧写 session-done 让启动器正常收停）；
        # 失败则走下方 SIGTERM/SIGKILL 兜底，不屏蔽原异常
        try:
            self.send("finish", timeout=30)
        except Exception as abort:
            print(f"  [career] finish 命令未能送达：{abort}")
        deadline = time.time() + 120
        while self.proc.poll() is None and time.time() < deadline:
            time.sleep(1)
        if self.proc.poll() is None:
            # 先 SIGTERM：启动器脚本的 trap 会顺带回收游戏进程；SIGKILL 会跳过清理产生孤儿
            print("  [career] 启动器未及时退出，发送 SIGTERM")
            self.proc.terminate()
        term_deadline = time.time() + 15
        while self.proc.poll() is None and time.time() < term_deadline:
            time.sleep(0.5)
        if self.proc.poll() is None:
            print("  [career] SIGTERM 无效，强制 SIGKILL（可能遗留游戏进程）")
            self.proc.kill()
        self.proc.wait()

    def _wait_status_ready(self) -> None:
        status = self.work_dir / "status.json"
        deadline = time.time() + min(300, self.timeout)
        while time.time() < deadline:
            # mtime 必须晚于本次启动：启动器清理完成前的残留心跳不可信
            if status.is_file() and status.stat().st_mtime > self.started_at:
                try:
                    _read_json(status)
                    print(f"  [debug] status 就绪：{status}", flush=True)
                    return
                except json.JSONDecodeError:
                    time.sleep(0.5)
                    continue
            if self.proc is not None and self.proc.poll() is not None:
                raise PhaseAbort(f"游戏进程在自动化就绪前退出（exit={self.proc.returncode}）")
            time.sleep(1)
        raise PhaseAbort("自动化心跳 status.json 未在 300s 内出现")

    def send(self, command: str, timeout: int = 90) -> dict:
        self.seq += 1
        seq = self.seq
        result_path = self.work_dir / f"result-{seq}.json"
        tmp = self.work_dir / "command.txt.tmp"
        target = self.work_dir / "command.txt"
        tmp.write_text(f"{seq} {command}\n", encoding="utf-8")
        tmp.replace(target)
        print(f"  [debug] send#{seq} {command!r} -> {target} exists={target.exists()}", flush=True)
        deadline = time.time() + timeout
        while time.time() < deadline:
            if result_path.is_file():
                try:
                    return _read_json(result_path)
                except json.JSONDecodeError:
                    time.sleep(0.5)
                    continue
            if self.proc is not None and self.proc.poll() is not None:
                raise PhaseAbort(f"游戏进程在命令 {command!r} 期间退出（exit={self.proc.returncode}）")
            time.sleep(0.5)
        raise PhaseAbort(f"命令超时（{timeout}s）：{command}")

    def send_ok(self, checker: Checker, item: str, command: str, timeout: int = 90) -> dict:
        result = self.send(command, timeout=timeout)
        if not result.get("ok"):
            checker.check(self.phase, item, False, f"命令失败：{command} error={result.get('error')}")
            raise PhaseAbort(f"命令失败：{command}")
        return result.get("data") or {}

    def poll_until(self, command: str, predicate, timeout: int = 60, interval: float = 1.5) -> dict:
        deadline = time.time() + timeout
        last: dict = {}
        while time.time() < deadline:
            last = self.send_ok_data(command)
            if predicate(last):
                return last
            time.sleep(interval)
        raise PhaseAbort(f"轮询超时（{timeout}s）：{command}，最后数据={last}")

    def send_ok_data(self, command: str) -> dict:
        result = self.send(command)
        if not result.get("ok"):
            raise PhaseAbort(f"命令失败：{command} error={result.get('error')}")
        return result.get("data") or {}


# ─── 存档发现 ───

def wait_save_landed(driver: CareerDriver, saves_root: Path, save_dir_name: str, timeout: int = 90) -> str:
    """等待 save_copy 返回的具名存档目录落盘并写稳（压缩与否取决于本机 compressSaveGameData 设置）。"""
    candidate = saves_root / save_dir_name
    deadline = time.time() + timeout
    while time.time() < deadline:
        payload = candidate / "campaign.xml.zip"
        if not payload.is_file():
            payload = candidate / "campaign.xml"
        if payload.is_file():
            size_a = payload.stat().st_size
            time.sleep(1.5)
            if payload.stat().st_size == size_a and size_a > 0:
                return str(candidate)
        if driver.proc is not None and driver.proc.poll() is not None:
            raise PhaseAbort("游戏进程在存档期间退出")
        time.sleep(1)
    raise PhaseAbort(f"存档 {save_dir_name} 未在 {timeout}s 内落盘于 {saves_root}")


# ─── 日志收集 ───

MOD_ERROR_PATTERN = re.compile(r"ERROR", re.IGNORECASE)
MOD_MARK_PATTERN = re.compile(r"astd|asteria|kasuminova", re.IGNORECASE)
CAREER_TRACE_PATTERN = re.compile(r"\[ASTD-Career]")


def collect_mod_errors(log_path: Path) -> list[str]:
    if not log_path.is_file():
        return [f"<日志缺失：{log_path}>"]
    lines: list[str] = []
    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        if MOD_ERROR_PATTERN.search(line) and MOD_MARK_PATTERN.search(line):
            lines.append(line.strip()[:400])
    return lines


def collect_career_trace(log_path: Path) -> list[str]:
    if not log_path.is_file():
        return []
    return [
        line.strip()[:300]
        for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines()
        if CAREER_TRACE_PATTERN.search(line)
    ]


# ─── 阶段一：新开档全链路 ───

def phase_main(checker: Checker, game_dir: Path, saves_root: Path, timeout: int) -> tuple[str, str]:
    phase = "main"
    pre_sign_save = ""
    post_save = ""

    with CareerDriver(game_dir, phase, timeout, new_game=True, save_dir=None) as driver:
        driver.send_ok(checker, "心跳/ping", "ping")

        # ── 1a. 世界生成：主星系 ──
        world = driver.send_ok(checker, "世界生成：主星系事实采集", "check_world_main")
        checker.check(phase, "主星系存在", world.get("systemExists") is True, f"systemId={world.get('systemId')}")
        entities = world.get("entities") or {}
        missing = [k for k, v in entities.items() if not v]
        checker.check(
            phase, "主星系实体齐全（行星/分局站/预留站/中继/阵列/浮标/星门）",
            not missing, f"缺失={missing}" if missing else f"共 {len(entities)} 个实体",
        )
        checker.check(
            phase, "休眠星门实体类型", world.get("gateEntityType") == "inactive_gate",
            f"type={world.get('gateEntityType')}",
        )
        markets = world.get("markets") or {}
        lantai = markets.get("astd_story_market_lantai") or {}
        checker.check(
            phase, "兰台市场挂“菀星行政部遗址”状况",
            "astd_wanxing_admin_ruins" in (lantai.get("conditions") or []),
            f"conditions={lantai.get('conditions')}",
        )
        station = markets.get(MARKET_MAIN_STATION) or {}
        checker.check(
            phase, "分局空间站市场（size 4 / independent / 入经济）",
            station.get("exists") is True and station.get("size") == 4 and station.get("inEconomy") is True,
            f"size={station.get('size')} faction={station.get('faction')} inEconomy={station.get('inEconomy')}",
        )
        for pid in ("astd_story_market_honglu", "astd_story_market_cuichi"):
            m = markets.get(pid) or {}
            checker.check(phase, f"{pid} 市场存在", m.get("exists") is True, f"size={m.get('size')}")
        checker.check(
            phase, "主星系无重复生成", world.get("mainStarSystemCount") == 1,
            f"count={world.get('mainStarSystemCount')}",
        )
        if world.get("indEvoEnabled"):
            ind_evo = world.get("indEvo") or {}
            checker.check(
                phase, "IndEvo：洪炉/淬池磁轨炮台与状况",
                all(ind_evo.get(k) for k in ("hongluArtillery", "hongluArtilleryCondition", "cuichiArtillery", "cuichiArtilleryCondition")),
                json.dumps(ind_evo, ensure_ascii=False),
            )
            checker.check(
                phase, "IndEvo：主星系观锚站 ×4", ind_evo.get("watchtowerCount") == 4,
                f"count={ind_evo.get('watchtowerCount')}",
            )
        else:
            checker.check(phase, "IndEvo：未安装，跳过联动项", True, "isModEnabled(IndEvo)=false")

        # ── 初始状态基线 ──
        state = driver.send_ok(checker, "初始状态基线", "dump_state")
        checker.check(
            phase, "基线：章节 0 / 等级 0 / 清算 97.3%",
            state.get("chapter") == 0 and state.get("contractorLevel") == 0
            and _approx(state.get("liquidationProgress", -1), 97.3),
            f"chapter={state.get('chapter')} level={state.get('contractorLevel')} progress={state.get('liquidationProgress')}",
        )

        snap = driver.send_ok(checker, "终端快照（未接序章）", "terminal_snapshot")
        checker.check(
            phase, "终端初始：LOCKED / 无工单 / glitch 变体就绪",
            snap.get("phase") == "LOCKED" and snap.get("orderCount") == 0
            and snap.get("glitchCh1") == "TARGET_STATUS" and snap.get("glitchCh3") == "HALF_LINE"
            and snap.get("glitchCh2") is None and snap.get("glitchCh4") is None,
            f"phase={snap.get('phase')} orders={snap.get('orderCount')} glitch={snap.get('glitchCh1')}/{snap.get('glitchCh3')}",
        )

        # ── 1b. 赏金链路：序章 ──
        accepted = driver.send_ok(checker, "序章签署（桥接接取）", "accept_prologue")
        checker.check(phase, "序章工单挂出", accepted.get("accepted") is True, f"posted={accepted.get('posted')}")
        driver.send_ok(checker, "序章工单激活", f"wait_posted {XW}")

        killed = driver.send_ok(checker, "序章目标击毁（桥接推进）", f"kill {XW}")
        checker.check(phase, "序章进入待核销", killed.get("destroyed") is True, f"progress={killed.get('liquidationProgress')}")

        settled = driver.send_ok(checker, "序章终端核销", f"settle {XW}")
        checker.check(
            phase, "序章核销：报酬到账 / 注册一级 / 章节推进",
            settled.get("success") is True and settled.get("payout", 0) > 0
            and settled.get("creditsDelta") == settled.get("payout")
            and settled.get("contractorLevel") == 1 and settled.get("chapter") == 1,
            f"payout={settled.get('payout')} creditsDelta={settled.get('creditsDelta')} level={settled.get('contractorLevel')}",
        )

        # ── 第一章批次 gating ──
        driver.send_ok(checker, "批一 YJ-1102 挂出", f"wait_posted {YJ_1102}")
        driver.send_ok(checker, "批一 YJ-1103 挂出", f"wait_posted {YJ_1103}")
        gating = driver.send_ok(checker, "批次 gating 快照", "dump_state")
        checker.check(
            phase, "批次 gating：批一未结清时批二不挂出",
            set(gating.get("posted") or []) == {YJ_1102, YJ_1103} and not gating.get("postable"),
            f"posted={gating.get('posted')} postable={gating.get('postable')}",
        )

        # ── 失败重挂 ──
        reposted = driver.send_ok(checker, "失败终态重挂（YJ-1102）", f"fail {YJ_1102}", timeout=120)
        checker.check(phase, "失败重挂完成", reposted.get("reposted") is True)

        # ── 批一结清 ──
        driver.send_ok(checker, "击毁 YJ-1102", f"kill {YJ_1102}")
        driver.send_ok(checker, "击毁 YJ-1103", f"kill {YJ_1103}")
        s1 = driver.send_ok(checker, "核销 YJ-1102", f"settle {YJ_1102}")
        checker.check(phase, "批一单 1 核销（组未结清无奖金）", s1.get("success") is True and s1.get("groupBonusId") is None)
        s2 = driver.send_ok(checker, "核销 YJ-1103", f"settle {YJ_1103}")
        checker.check(
            phase, "批一结清奖金", s2.get("success") is True and s2.get("groupBonusId") == "ch1_batch1"
            and s2.get("groupBonusAmount", 0) > 0 and s2.get("creditsDelta") == s2.get("payout") + s2.get("groupBonusAmount"),
            f"bonus={s2.get('groupBonusAmount')}",
        )

        # ── 批二 / 批三 ──
        for key in (YJ_1198, YJ_1201, YJ_1204):
            driver.send_ok(checker, f"批二挂出 {key}", f"wait_posted {key}")
        mid = driver.send_ok(checker, "批二 gating 快照", "dump_state")
        checker.check(
            phase, "批次 gating：批三未因批二挂出",
            JJ_0007 not in (mid.get("posted") or []) and JJ_0007 not in (mid.get("postable") or []),
            f"posted={mid.get('posted')}",
        )
        bonus2 = None
        for key in (YJ_1198, YJ_1201, YJ_1204):
            driver.send_ok(checker, f"击毁 {key}", f"kill {key}")
            bonus2 = driver.send_ok(checker, f"核销 {key}", f"settle {key}")
        checker.check(
            phase, "批二结清奖金", bonus2 is not None and bonus2.get("groupBonusId") == "ch1_batch2",
            f"bonus={None if bonus2 is None else bonus2.get('groupBonusAmount')}",
        )

        driver.send_ok(checker, "批三 JJ-0007 挂出", f"wait_posted {JJ_0007}")
        driver.send_ok(checker, "击毁 JJ-0007", f"kill {JJ_0007}")
        s3 = driver.send_ok(checker, "核销 JJ-0007", f"settle {JJ_0007}")
        checker.check(
            phase, "第一章结清：等级 2 / 章末钩子（封存类目解锁）",
            s3.get("chapter") == 2 and s3.get("contractorLevel") == 2 and HOOK_SEALED in (s3.get("chapterHooks") or []),
            f"chapter={s3.get('chapter')} hooks={s3.get('chapterHooks')}",
        )

        # ── 1a 续：第二章双遗址星系（第一章结清钩子即时生成） ──
        ch2 = driver.send_ok(checker, "世界生成：第二章双遗址星系事实采集", "check_world_ch2")
        starfall = ch2.get("starfall") or {}
        aster = ch2.get("aster") or {}
        checker.check(
            phase, "第一章结清钩子：双遗址星系生成",
            starfall.get("systemExists") is True and aster.get("systemExists") is True
            and ch2.get("chapter2SystemsGenerated") is True,
            f"starfall={starfall.get('systemExists')} aster={aster.get('systemExists')}",
        )
        sf_missing = [k for k, v in (starfall.get("entities") or {}).items() if not v]
        checker.check(phase, "星坠星系实体齐全（含休眠星门）", not sf_missing, f"缺失={sf_missing}")
        duanyuan = (starfall.get("markets") or {}).get("astd_story_market_duanyuan") or {}
        checker.check(
            phase, "锻原市场挂“星坠工程部遗址”状况",
            "astd_starfall_engineering_ruins" in (duanyuan.get("conditions") or []),
            f"conditions={duanyuan.get('conditions')}",
        )
        aster_missing = [k for k, v in (aster.get("entities") or {}).items() if not v]
        checker.check(phase, "紫菀星系实体齐全（含核心数据舱/休眠星门）", not aster_missing, f"缺失={aster_missing}")
        nodes = aster.get("nodeEntities") or {}
        checker.check(
            phase, "紫菀 3 引力节点存在", all(nodes.values()) and len(nodes) == 3,
            f"nodes={nodes}",
        )
        shiguang = (aster.get("markets") or {}).get("astd_story_market_shiguang") or {}
        shiguang_conditions = shiguang.get("conditions") or []
        checker.check(
            phase, "拾光站市场状况（视界动力 + 紫菀科研部遗址，size 4 入经济）",
            "astd_event_horizon_power" in shiguang_conditions
            and "astd_aster_research_ruins" in shiguang_conditions
            and shiguang.get("size") == 4 and shiguang.get("inEconomy") is True,
            f"conditions={shiguang_conditions}",
        )
        if ch2.get("indEvoEnabled"):
            ind_evo2 = ch2.get("indEvo") or {}
            checker.check(
                phase, "IndEvo：锻原敌对炮台 + 观锚站 ×4",
                ind_evo2.get("duanyuanArtillery") is True
                and ind_evo2.get("duanyuanArtilleryCondition") is True
                and ind_evo2.get("duanyuanFaction") == "IndEvo_derelict"
                and ind_evo2.get("watchtowerCount") == 4,
                json.dumps(ind_evo2, ensure_ascii=False),
            )
        else:
            checker.check(phase, "IndEvo：未安装，跳过星坠联动项", True, "")

        # ── 1c. 终端 UI（一章结清后） ──
        snap1 = driver.send_ok(checker, "终端快照（一章结清后）", "terminal_snapshot")
        layers1 = {l["layer"]: l for l in (snap1.get("archiveLayers") or [])}
        checker.check(
            phase, "终端：OPEN / 工单行 9 / 档案第一层全解锁",
            snap1.get("phase") == "OPEN" and snap1.get("orderCount") == 9
            and layers1.get(1, {}).get("unlocked") == 7 and layers1.get(2, {}).get("unlocked") == 0,
            f"orders={snap1.get('orderCount')} layers={snap1.get('archiveLayers')}",
        )
        opened = driver.send_ok(checker, "程序化打开分局终端", "open_terminal", timeout=120)
        checker.check(
            phase, "终端真实打开/关闭无异常",
            opened.get("shown") is True and opened.get("terminalOpened") is True and opened.get("dismissed") is True,
            f"dialogPlugin={opened.get('dialogPlugin')}",
        )

        # ── 第二章：星坠线 + 紫菀线（引力节点） ──
        driver.send_ok(checker, "XC-0216 挂出", f"wait_posted {XC_0216}")
        # ZW 节点驱动阶段不产生 MagicBounty 条目，用 dump_state 验证 posted 登记
        zw_posted = driver.send_ok_data("dump_state")
        checker.check(
            phase, "双章并行：XC/ZW 同章挂出", ZW_0309 in (zw_posted.get("posted") or []),
            f"posted={zw_posted.get('posted')}",
        )

        driver.send_ok(checker, "击毁 XC-0216", f"kill {XC_0216}")
        s_xc1 = driver.send_ok(checker, "核销 XC-0216", f"settle {XC_0216}")
        checker.check(phase, "XC 线性递进：单 1 核销后单 2 可挂", s_xc1.get("success") is True)

        for idx, node in enumerate((NODE_1, NODE_2, NODE_3), start=1):
            pulled = driver.send_ok(checker, f"拔除引力节点 {idx}/3", f"pull_node {node}")
            checker.check(
                phase, f"ZW 阶段同步（拔除 {idx} → 阶段 {idx}）",
                pulled.get("zwStageIndex") == idx and pulled.get("pulledCount") == idx,
                f"stage={pulled.get('zwStageIndex')} pulled={pulled.get('pulledCount')}",
            )
        driver.send_ok(checker, "ZW 核心守备工单挂出（阶段 4）", f"wait_posted {ZW_0309}", timeout=120)
        driver.send_ok(checker, "击毁 ZW 核心守备", f"kill {ZW_0309}")
        s_zw = driver.send_ok(checker, "核销 ZW-0309（交割物自愈补发）", f"settle {ZW_0309}")
        checker.check(
            phase, "ZW 结清（紫菀线）",
            s_zw.get("success") is True and s_zw.get("groupBonusId") == "ch2_zw",
            f"reject={s_zw.get('rejectReason')} bonus={s_zw.get('groupBonusAmount')}",
        )

        for key in (XC_0217, XC_0221):
            driver.send_ok(checker, f"{key} 挂出", f"wait_posted {key}")
            driver.send_ok(checker, f"击毁 {key}", f"kill {key}")
            s_xc = driver.send_ok(checker, f"核销 {key}", f"settle {key}")
        checker.check(
            phase, "第二章结清：等级 3 / 章末钩子（识别码重挂）",
            s_xc.get("chapter") == 3 and s_xc.get("contractorLevel") == 3 and HOOK_DUP in (s_xc.get("chapterHooks") or []),
            f"chapter={s_xc.get('chapter')} hooks={s_xc.get('chapterHooks')}",
        )

        # ── 第三章：清算进度节拍 97.9 / 97.4 / 98.8 ──
        zx_expect = ((ZX_1001, 97.9), (ZX_0344, 97.4), (ZX_0002, 98.8))
        s_zx = None
        for key, expected in zx_expect:
            driver.send_ok(checker, f"{key} 挂出", f"wait_posted {key}")
            driver.send_ok(checker, f"击毁 {key}", f"kill {key}")
            s_zx = driver.send_ok(checker, f"核销 {key}", f"settle {key}")
            checker.check(
                phase, f"清算进度节拍：{key} 核销后 {expected}%",
                _approx(s_zx.get("liquidationProgress", -1), expected),
                f"actual={s_zx.get('liquidationProgress')}",
            )
        checker.check(
            phase, "第三章结清：等级 4 / 章末钩子（半行工单）",
            s_zx is not None and s_zx.get("chapter") == 4 and s_zx.get("contractorLevel") == 4
            and HOOK_HALF in (s_zx.get("chapterHooks") or []),
            f"chapter={None if s_zx is None else s_zx.get('chapter')}",
        )

        snap3 = driver.send_ok(checker, "终端快照（三章结清后）", "terminal_snapshot")
        checker.check(
            phase, "终端：清算进度顶栏（三章起常驻）",
            snap3.get("showLiquidationTopbar") is True,
            f"chapter={snap3.get('chapter')}",
        )

        # ── 第四章：ZQ 三阶段 99.1 / 99.6 / 100.0 → 归档挂起 ──
        driver.send_ok(checker, "ZQ-0001 挂出", f"wait_posted {ZQ_0001}", timeout=120)
        zq_expect = (99.1, 99.6, 100.0)
        for stage, expected in enumerate(zq_expect):
            kz = driver.send_ok(checker, f"击毁 ZQ 阶段 {stage + 1}", f"kill {ZQ_0001}", timeout=120)
            checker.check(
                phase, f"清算进度节拍：ZQ 阶段 {stage + 1} 击毁后 {expected}%",
                _approx(kz.get("liquidationProgress", -1), expected),
                f"actual={kz.get('liquidationProgress')} destroyed={kz.get('destroyed')}",
            )
        s_zq = driver.send_ok(checker, "核销 ZQ-0001", f"settle {ZQ_0001}")
        checker.check(
            phase, "第四章结清：等级 5 / 终局钩子 / 归档挂起",
            s_zq.get("success") is True and s_zq.get("chapter") == 5 and s_zq.get("contractorLevel") == 5
            and HOOK_FINAL in (s_zq.get("chapterHooks") or []) and s_zq.get("archivalPending") is True,
            f"archivalPending={s_zq.get('archivalPending')} progress={s_zq.get('liquidationProgress')}",
        )
        mem_state = driver.send_ok(checker, "章末钩子 memory 写入校验", "dump_state")
        checker.check(
            phase, "memory：四章末钩子 + 归档挂起 + 清算进度读数",
            (mem_state.get("memHooks") or {}).get(HOOK_FINAL) is True
            and mem_state.get("memArchivalPending") is True
            and _approx(mem_state.get("memLiquidation", -1), 100.0),
            f"memHooks={mem_state.get('memHooks')} memLiquidation={mem_state.get('memLiquidation')}",
        )

        snap4 = driver.send_ok(checker, "终端快照（归档挂起）", "terminal_snapshot")
        checker.check(
            phase, "终端：结局事务待签署（AWAITING_SIGN）",
            snap4.get("endingStage") == "AWAITING_SIGN" and snap4.get("settledCount") == 15,
            f"stage={snap4.get('endingStage')} settled={snap4.get('settledCount')}",
        )

        # ── 1e. 存档（签前副本，供 trade 阶段复用） ──
        saved_pre = driver.send_ok(checker, "存档副本（签前）", "save_copy", timeout=120)
        pre_sign_save = wait_save_landed(driver, saves_root, saved_pre["saveDirName"])
        checker.check(phase, "签前存档落盘", True, pre_sign_save)

        # ── 1d. 结局：封存签署 → 执行官签发（战斗） ──
        signed = driver.send_ok(checker, "归档签署（封存）", "sign SEAL")
        checker.check(
            phase, "封存签署：延迟条目落账 / 保留档案访问 / 无限期承包商",
            signed.get("signed") is True and signed.get("choice") == "SEAL"
            and signed.get("archivesReadOnly") is False
            and signed.get("indefiniteContractor") is True
            and signed.get("pendingStrengthCount", 0) > 0
            and not signed.get("appliedStrengthPct"),
            f"pending={signed.get('pendingStrengthCount')} pages={signed.get('receiptPages')}",
        )
        issued = driver.send_ok(checker, "执行官签发（战斗特化）", "issue_executor COMBAT")
        checker.check(
            phase, "执行官签发：物品入舱 / 特化锁定",
            issued.get("issued") is True and issued.get("executorSpec") == "COMBAT",
            f"pages={issued.get('receiptPages')}",
        )
        assigned = driver.send_ok(checker, "指定指挥舰", "assign_command_ship")
        checker.check(
            phase, "指挥舰任命落账",
            assigned.get("assigned") is True and assigned.get("stateCommandShipId") == assigned.get("memberId"),
            f"member={assigned.get('memberId')}",
        )
        snap5 = driver.send_ok(checker, "终端快照（结局完成）", "terminal_snapshot")
        checker.check(phase, "终端：结局事务完成（COMPLETE）", snap5.get("endingStage") == "COMPLETE", f"stage={snap5.get('endingStage')}")

        # ── 无限赏金：3 槽位挂出与换代 ──
        inf = driver.poll_until(
            "infinite_status",
            lambda d: d.get("slotCount") == 3
            and all(s.get("lifecycle") == "POSTED" and s.get("active") for s in (d.get("slots") or [])),
            timeout=60,
        )
        checker.check(
            phase, "无限赏金 3 槽位挂出",
            inf.get("indefiniteContractor") is True and inf.get("slotCount") == 3,
            f"slots={[(s.get('index'), s.get('generation'), s.get('lifecycle')) for s in (inf.get('slots') or [])]}",
        )
        driver.send_ok(checker, "击毁无限赏金槽位 0", "kill_infinite 0")
        inf_settle = driver.send_ok(checker, "无限赏金槽位 0 核销换代", "settle_infinite 0", timeout=120)
        checker.check(
            # 换代序号全局单调（InfiniteBountyGeneratorTest 锁定）：初始 1/2/3，槽位 0 换代后取 4
            phase, "无限赏金核销换代（槽位 0 第 1 代 → 全局序号 4 重挂）",
            inf_settle.get("settled") is True and inf_settle.get("generationBefore") == 1
            and inf_settle.get("generationAfter") == 4
            and inf_settle.get("creditsDelta") == inf_settle.get("payout")
            and inf_settle.get("settlements") == 1,
            f"gen={inf_settle.get('generationBefore')}→{inf_settle.get('generationAfter')} lifecycle={inf_settle.get('lifecycleAfter')}",
        )

        # ── 1e. 存档（签后副本，供 reload 阶段） ──
        saved_post = driver.send_ok(checker, "存档副本（签后）", "save_copy", timeout=120)
        post_save = wait_save_landed(driver, saves_root, saved_post["saveDirName"])
        checker.check(phase, "签后存档落盘", True, post_save)

    return pre_sign_save, post_save


# ─── 阶段二：读档补齐 ───

def phase_reload(checker: Checker, game_dir: Path, save_dir: str, timeout: int) -> None:
    phase = "reload"
    with CareerDriver(game_dir, phase, timeout, new_game=False, save_dir=save_dir) as driver:
        driver.send_ok(checker, "心跳/ping", "ping")

        state = driver.send_ok(checker, "读档状态转储", "dump_state")
        checker.check(
            phase, "BountyState normalize 后状态完整",
            state.get("chapter") == 5 and state.get("contractorLevel") == 5
            and state.get("archivalChoice") == "SEAL" and state.get("indefiniteContractor") is True
            and len(state.get("settled") or []) == 15
            and len(state.get("gravityNodesPulled") or []) == 3,
            f"chapter={state.get('chapter')} choice={state.get('archivalChoice')} settled={len(state.get('settled') or [])}",
        )
        world_state = state.get("worldState") or {}
        checker.check(
            phase, "世界生成幂等标记恢复",
            world_state.get("mainSystemGenerated") is True and world_state.get("chapter2SystemsGenerated") is True,
            f"worldState={world_state}",
        )
        # 实机环境战役时钟被极大加速（实测签署后数秒内 30 标准日即流逝），延迟条目可能在
        # 签后存档前已到期激活；读档恢复口径改为“待生效 + 已生效合计 10 条、幅度全 0.12”，
        # 到期时刻是否落在存档前不作强约束（30 日延迟语义由 EndingProgressionTest 单测覆盖）。
        pending_map = state.get("pendingStrengthEffects") or {}
        applied_map = state.get("appliedStrengthPct") or {}
        pending_before = len(pending_map)
        checker.check(
            phase, "结局延迟条目读档恢复（待生效+已生效合计 10 / 幅度 0.12）",
            pending_before + len(applied_map) == 10
            and all(_approx(v.get("pct"), 0.12) for v in pending_map.values())
            and all(_approx(v, 0.12) for v in applied_map.values()),
            f"pending={pending_before} applied={len(applied_map)}",
        )

        world = driver.send_ok(checker, "读档后世界生成复查", "check_world_main")
        checker.check(
            phase, "主星系读档后完整且无重复",
            world.get("systemExists") is True and world.get("mainStarSystemCount") == 1
            and all((world.get("entities") or {}).values()),
            f"count={world.get('mainStarSystemCount')}",
        )
        ch2 = driver.send_ok(checker, "读档后第二章星系复查", "check_world_ch2")
        checker.check(
            phase, "双遗址星系读档后完整（节点已拔除不重生）",
            (ch2.get("starfall") or {}).get("systemExists") is True
            and (ch2.get("aster") or {}).get("systemExists") is True
            and not any(((ch2.get("aster") or {}).get("nodeEntities") or {}).values()),
            f"nodes={((ch2.get('aster') or {}).get('nodeEntities'))}",
        )

        # 延迟条目到期激活（force_due_effects 提前剩余条目的激活时刻；时钟竞速下可能已全部到期，
        # 此时 forced=0 属预期——终态口径：已生效表满 10 条且幅度全 0.12）
        forced = driver.send_ok(checker, "延迟条目激活时刻提前", "force_due_effects")
        applied = driver.send_ok(checker, "延迟条目到期激活", "wait_effects_applied", timeout=60)
        checker.check(
            phase, "结局延迟条目激活（封存 +12% 全体）",
            len(applied.get("appliedStrengthPct") or {}) == 10
            and all(_approx(v, 0.12) for v in (applied.get("appliedStrengthPct") or {}).values()),
            f"forced={forced.get('forced')} applied={len(applied.get('appliedStrengthPct') or {})} 势力",
        )

        # 无限赏金读档恢复：槽位保持，管理脚本 tick 后 active 条目在位
        inf = driver.poll_until(
            "infinite_status",
            lambda d: d.get("slotCount") == 3
            and all(s.get("lifecycle") == "POSTED" and s.get("active") for s in (d.get("slots") or [])),
            timeout=60,
        )
        generations = sorted(s.get("generation") for s in (inf.get("slots") or []))
        checker.check(
            # 全局单调换代序号：初始 1/2/3，槽位 0 已换代至 4（换代序号见 InfiniteBountyGenerator.ensureSlots）
            phase, "无限赏金读档恢复（槽位 0 已换代，全局序号 [2,3,4]）",
            generations == [2, 3, 4] and inf.get("settlements") and len(inf.get("settlements")) == 1,
            f"generations={generations} settlements={inf.get('settlements')}",
        )

        opened = driver.send_ok(checker, "读档后程序化打开分局终端", "open_terminal", timeout=120)
        checker.check(
            phase, "终端读档后真实打开/关闭无异常",
            opened.get("shown") is True and opened.get("terminalOpened") is True and opened.get("dismissed") is True,
            f"dialogPlugin={opened.get('dialogPlugin')}",
        )
        snap = driver.send_ok(checker, "读档后终端快照", "terminal_snapshot")
        checker.check(
            phase, "终端读档后快照一致（15 单全核销 / 结局 COMPLETE / 档案全解锁）",
            snap.get("settledCount") == 15 and snap.get("endingStage") == "COMPLETE"
            and snap.get("archiveUnlocked") == snap.get("archiveTotal"),
            f"archives={snap.get('archiveUnlocked')}/{snap.get('archiveTotal')}",
        )


# ─── 阶段三：交易选结局 ───

def phase_trade(checker: Checker, game_dir: Path, save_dir: str, timeout: int) -> None:
    phase = "trade"
    with CareerDriver(game_dir, phase, timeout, new_game=False, save_dir=save_dir) as driver:
        driver.send_ok(checker, "心跳/ping", "ping")

        state = driver.send_ok(checker, "签前状态确认", "dump_state")
        checker.check(
            phase, "签前：归档挂起且未签署",
            state.get("archivalPending") is True and state.get("archivalChoice") is None
            and state.get("memArchivalPending") is True,
            f"choice={state.get('archivalChoice')}",
        )

        signed = driver.send_ok(checker, "归档签署（交易→霸主）", "sign TRADE hegemony")
        relation_delta = None
        if signed.get("relationBefore") is not None and signed.get("relationAfter") is not None:
            relation_delta = signed["relationAfter"] - signed["relationBefore"]
        applied = signed.get("appliedStrengthPct") or {}
        checker.check(
            phase, "交易签署：报酬到账 / 对象 +50% 立即 / 关系 +0.3 / 档案转只读",
            signed.get("signed") is True and signed.get("choice") == "TRADE"
            and signed.get("tradePayout", 0) > 0 and signed.get("creditsDelta") == signed.get("tradePayout")
            and _approx(applied.get("hegemony", -1), 0.5)
            and relation_delta is not None and _approx(relation_delta, 0.3)
            and signed.get("archivesReadOnly") is True
            and signed.get("pendingStrengthCount", 0) > 0,
            f"payout={signed.get('tradePayout')} relationΔ={relation_delta} applied={applied}",
        )

        issued = driver.send_ok(checker, "执行官签发（行政特化）", "issue_executor ADMIN")
        checker.check(phase, "执行官签发（行政）", issued.get("issued") is True and issued.get("executorSpec") == "ADMIN")
        appointed = driver.send_ok(checker, "任命分局站行政官", "appoint_admin")
        checker.check(
            phase, "行政任命落账",
            appointed.get("appointed") is True and appointed.get("stateAdminMarketId") == MARKET_MAIN_STATION,
            f"market={appointed.get('stateAdminMarketId')}",
        )
        snap = driver.send_ok(checker, "终端快照（交易结局）", "terminal_snapshot")
        checker.check(
            phase, "终端：结局事务完成 / 档案只读",
            snap.get("endingStage") == "COMPLETE" and snap.get("archivesReadOnly") is True,
            f"stage={snap.get('endingStage')}",
        )


# ─── 报告 ───

MANUAL_ACCEPTANCE = [
    "分局终端纸质卡/铅封/印章观感与扫描线方向（BranchTerminalPlugin/PaperCardPlugin 渲染层）",
    "章末 glitch 闪现视觉（TARGET_STATUS 状态章瞬替 / HALF_LINE 打印卡死自愈）",
    "结算回执逐行打印 + 金额数字滚动动效（ReceiptOverlayPlugin）",
    "签署/签发叙事回执链分页弹出（清算 100% 反转回执 → 归档回执 → 无限期承包合同）",
    "引力节点护卫舰队接触触发实机战斗（自动化以移除节点实体替代接触+打捞）",
    "赏金目标舰队实机战斗与词缀表现（自动化以 endBounty(Succeeded) 替代击毁）",
    "序章酒馆 BarEvent 代办对话逐选项流程（自动化以 acceptPrologueWorkOrder 直接接取替代）",
    "HUD 回执消息颜色/排版（HudMessages.campaign）",
]

SUBSTITUTIONS = [
    "序章酒馆对话逐点选项 → MainBountyBridge.acceptPrologueWorkOrder 直接接取",
    "战斗击毁目标舰队 → ActiveBounty.endBounty(Succeeded) + 舰队 despawn（BountyCampaignManager 正常 tick 消费终态）",
    "引力节点接触触发 + 打捞拔除 → 直接 removeEntity 节点（GravityNodeWatchScript 正常判定拔除）",
    "结局延迟条目 30 标准日等待 → force_due_effects 提前激活时刻（EndingCampaignManager 正常到期激活管线）",
    "终端手动打开/点击 → showInteractionDialog + BranchTerminalUi.open 程序化打开；核销/签署/签发均走 BranchStationBackendImpl 真实终端后端",
]


def write_report(checker: Checker, traces: dict[str, list[str]], mod_errors: dict[str, list[str]], out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    total = len(checker.rows)
    passed = sum(1 for r in checker.rows if r[2])

    lines = ["# ASTD 生涯集成自动化验收报告", ""]
    lines.append(f"- 检查项：{passed}/{total} 通过")
    lines.append("")
    lines.append("| 阶段 | 检查项 | 结果 | 详情 |")
    lines.append("| --- | --- | --- | --- |")
    for phase, item, ok, detail in checker.rows:
        safe_detail = detail.replace("|", "\\|")[:300]
        lines.append(f"| {phase} | {item} | {'✅' if ok else '❌'} | {safe_detail} |")
    lines.append("")
    lines.append("## 替代点（无头自动化等价入口）")
    for item in SUBSTITUTIONS:
        lines.append(f"- {item}")
    lines.append("")
    lines.append("## 人工验收清单（无头环境无法验证）")
    for item in MANUAL_ACCEPTANCE:
        lines.append(f"- {item}")
    lines.append("")
    lines.append("## starsector.log 本模组 ERROR 收集")
    any_error = False
    for phase, errors in mod_errors.items():
        lines.append(f"### {phase}（{len(errors)} 条）")
        for error in errors[:50]:
            any_error = True
            lines.append(f"- `{error}`")
    if not any_error:
        lines.append("- 无")
    lines.append("")
    lines.append("## 自动化命令轨迹（[ASTD-Career] 日志行）")
    for phase, trace in traces.items():
        lines.append(f"### {phase}")
        lines.append("```")
        lines.extend(trace[-120:])
        lines.append("```")

    report_md = out_dir / "report.md"
    report_md.write_text("\n".join(lines) + "\n", encoding="utf-8")
    (out_dir / "report.json").write_text(
        json.dumps(
            {
                "total": total,
                "passed": passed,
                "rows": [
                    {"phase": p, "item": i, "ok": ok, "detail": d} for p, i, ok, d in checker.rows
                ],
                "modErrors": mod_errors,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"\n报告已写入：{report_md}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game-dir", default=None, help="Starsector 游戏目录（默认读 gradle.properties 的 starsector.gameDir）")
    parser.add_argument("--only", choices=("main", "reload", "trade"), default=None, help="只跑指定阶段")
    parser.add_argument("--pre-sign-save", default=None, help="trade 阶段用签前存档路径（--only trade 时必填）")
    parser.add_argument("--post-save", default=None, help="reload 阶段用签后存档路径（--only reload 时必填）")
    parser.add_argument("--timeout-main", type=int, default=1200)
    parser.add_argument("--timeout-phase", type=int, default=420)
    args = parser.parse_args()

    game_dir = Path(args.game_dir) if args.game_dir else None
    if game_dir is None:
        props = (REPO_ROOT / "gradle.properties").read_text(encoding="utf-8")
        match = re.search(r"^starsector\.gameDir=(.+)$", props, re.MULTILINE)
        if not match:
            print("FAIL: 未提供 --game-dir 且 gradle.properties 无 starsector.gameDir")
            return 1
        game_dir = Path(match.group(1).strip())
    saves_root = game_dir / "saves-career-automation"

    checker = Checker()
    traces: dict[str, list[str]] = {}
    mod_errors: dict[str, list[str]] = {}
    pre_sign_save = args.pre_sign_save
    post_save = args.post_save

    def run(phase: str, fn) -> None:
        print(f"\n===== 阶段 {phase} =====")
        try:
            fn()
        except PhaseAbort as abort:
            print(f"  [BLOCK] 阶段 {phase} 中止：{abort}")
            checker.blocked(phase, f"阶段 {phase} 提前中止", str(abort))
        except Exception as exc:  # 驱动自身异常也要进报告
            print(f"  [BLOCK] 阶段 {phase} 驱动异常：{exc}")
            checker.blocked(phase, f"阶段 {phase} 驱动异常", repr(exc))
        finally:
            log_path = game_dir / "career-automation-logs" / phase / "starsector.log"
            mod_errors[phase] = collect_mod_errors(log_path)
            traces[phase] = collect_career_trace(log_path)

    if args.only in (None, "main"):
        saves: list[str] = []

        def _main() -> None:
            saves.extend(phase_main(checker, game_dir, saves_root, args.timeout_main))

        run("main", _main)
        if len(saves) == 2:
            pre_sign_save, post_save = saves

    if args.only in (None, "reload"):
        def _reload() -> None:
            if not post_save:
                raise PhaseAbort("缺少签后存档（main 阶段未产出或未传 --post-save）")
            phase_reload(checker, game_dir, post_save, args.timeout_phase)

        run("reload", _reload)

    if args.only in (None, "trade"):
        def _trade() -> None:
            if not pre_sign_save:
                raise PhaseAbort("缺少签前存档（main 阶段未产出或未传 --pre-sign-save）")
            phase_trade(checker, game_dir, pre_sign_save, args.timeout_phase)

        run("trade", _trade)

    write_report(checker, traces, mod_errors, REPO_ROOT / "build" / "career-automation")

    total = len(checker.rows)
    passed = sum(1 for r in checker.rows if r[2])
    print(f"\n===== 汇总：{passed}/{total} 通过 =====")
    for phase, item, ok, detail in checker.failed:
        print(f"  FAIL {phase} :: {item} — {detail}")
    mod_error_total = sum(len(v) for v in mod_errors.values())
    if mod_error_total:
        print(f"  starsector.log 本模组 ERROR 共 {mod_error_total} 条（见报告）")
    if checker.failed:
        return 1
    print("PASS 生涯集成自动化验收")
    return 0


if __name__ == "__main__":
    sys.exit(main())
