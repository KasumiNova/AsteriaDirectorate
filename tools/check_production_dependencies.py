#!/usr/bin/env python3
import argparse
import os
import re
import subprocess
from collections import defaultdict
from pathlib import Path

MISSING_REFERENCE = re.compile(r"^\s*(\S+)\s+->\s+(\S+)\s+not found\s*$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="检查生产 JAR 是否引用运行环境之外的类")
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--class-path", required=True)
    parser.add_argument("--jdeps", default="jdeps")
    parser.add_argument("--report", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        print(f"生产 JAR 不存在：{jar}")
        return 2
    runtime_jars = [entry for entry in args.class_path.split(os.pathsep) if entry]
    if not runtime_jars:
        print("运行类路径为空")
        return 2
    command = [
        args.jdeps,
        "-J-Duser.language=en",
        "-J-Duser.country=US",
        "--multi-release", "17",
        "--ignore-missing-deps",
        "--class-path", os.pathsep.join(runtime_jars),
        "-verbose:class",
        str(jar),
    ]
    try:
        result = subprocess.run(command, capture_output=True, text=True, errors="replace", check=False)
    except OSError as error:
        print(f"无法执行 jdeps：{error}")
        return 2

    output = result.stdout + result.stderr
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(
            "命令：" + " ".join(command) + "\n\n" + output,
            encoding="utf-8",
        )

    missing = defaultdict(set)
    for line in result.stdout.splitlines():
        match = MISSING_REFERENCE.match(line)
        if match:
            source, target = match.groups()
            missing[target].add(source)

    if missing:
        print(f"生产类引用检查失败：{len(missing)} 个类不在原版及声明支持的模组运行库中")
        for target in sorted(missing):
            print(f"  缺失类：{target}")
            for source in sorted(missing[target]):
                print(f"    引用方：{source}")
        if args.report:
            print(f"完整报告：{args.report}")
        return 1
    if result.returncode != 0:
        print(f"jdeps 执行失败，退出码 {result.returncode}\n{output}")
        return result.returncode

    print(f"生产类引用检查通过：{jar.name}，运行库 JAR 数量 {len(runtime_jars)}")
    if args.report:
        print(f"完整报告：{args.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
