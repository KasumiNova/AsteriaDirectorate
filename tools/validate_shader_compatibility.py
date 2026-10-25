#!/usr/bin/env python3
"""验证 ASTD Shader 同时满足桌面 GLSL 110 与 GLES 100。

流程：
1. 从生产 Kotlin 文件的三引号常量提取着色器源码，缺失或重复直接报错；
2. glslangValidator 分别按桌面 GLSL 110 与 GLES 100 编译、链接；
3. 编译并运行 C 验证器，在 Mesa surfaceless EGL 的 GLES2 context 中
   编译链接全部登记的程序，通过 FBO 渲染并检查 GL 错误与非零输出像素。

GLES 源码在副本中按 ShaderCompatibility 的运行时规则拼接：统一前置 #version 100，
片元再注入 precision highp float/int；生产源码保持无版本指令。
顶点四边形位于 [-32, 32]，u_clipCenter 取 0，u_clipTransform.xy 取 1/32，
u_origin 取 [-32, -32]，使 UI 片元的 v_pos - u_origin 落在 [0, 64]。
"""

import argparse
import os
import re
import shlex
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_BUILD_DIR = REPO_ROOT / "build" / "shader-validation"
C_VALIDATOR_SOURCE = REPO_ROOT / "tools" / "shader-validation" / "validate_shader_gles.c"
GLES_VERSION_PREFIX = "#version 100\n"
GLES_FRAGMENT_PRECISION = "precision highp float;\nprecision highp int;\n"


class ValidationError(Exception):
    pass


@dataclass(frozen=True)
class ShaderSpec:
    slug: str
    display_name: str
    path: Path
    constant: str
    stage: str


VERTEX_SPEC = ShaderSpec(
    slug="vertex",
    display_name="统一顶点",
    path=REPO_ROOT
    / "modules/internal/astd-impl/src/main/kotlin/cn/kasuminova/astd/impl/render/ShaderCompatibility.kt",
    constant="VERTEX_SHADER_SOURCE",
    stage="vert",
)

FRAGMENT_SPECS = (
    ShaderSpec(
        slug="xc102_shockwave_ring",
        display_name="Xc102ShockwaveRingEffect",
        path=REPO_ROOT
        / "modules/internal/astd-combat/src/main/kotlin/cn/kasuminova/astd/renderer/effect/system/Xc102ShockwaveRingEffect.kt",
        constant="FRAGMENT_SHADER_SOURCE",
        stage="frag",
    ),
    ShaderSpec(
        slug="annihilation_vortex_beam",
        display_name="AnnihilationVortexBeamComponents",
        path=REPO_ROOT
        / "modules/internal/astd-render/src/main/kotlin/cn/kasuminova/astd/impl/render/AnnihilationVortexBeamComponents.kt",
        constant="FRAGMENT_SHADER_SOURCE",
        stage="frag",
    ),
    ShaderSpec(
        slug="hullmod_corner_pulse",
        display_name="ASTDHullModTooltipBackground.CORNER_PULSE",
        path=REPO_ROOT
        / "modules/internal/astd-ui/src/main/kotlin/cn/kasuminova/astd/ui/effect/ASTDHullModTooltipBackground.kt",
        constant="FRAGMENT_CORNER_PULSE",
        stage="frag",
    ),
    ShaderSpec(
        slug="hullmod_prism_lattice",
        display_name="ASTDHullModTooltipBackground.PRISM_LATTICE",
        path=REPO_ROOT
        / "modules/internal/astd-ui/src/main/kotlin/cn/kasuminova/astd/ui/effect/ASTDHullModTooltipBackground.kt",
        constant="FRAGMENT_PRISM_LATTICE",
        stage="frag",
    ),
    ShaderSpec(
        slug="stencil_solid_quad",
        display_name="ASTDStencilRenderer",
        path=REPO_ROOT
        / "modules/internal/astd-ui/src/main/kotlin/cn/kasuminova/astd/ui/render/ASTDStencilRenderer.kt",
        constant="FRAGMENT_SHADER_SOURCE",
        stage="frag",
    ),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="验证 ASTD 生产 Shader 的桌面 GLSL110 / GLES100 兼容性"
    )
    parser.add_argument(
        "--build-dir",
        type=Path,
        default=DEFAULT_BUILD_DIR,
        help="中间文件与验证器二进制的输出目录（默认 build/shader-validation）",
    )
    parser.add_argument("--glslang", default="glslangValidator", help="glslangValidator 路径")
    parser.add_argument("--cc", default="cc", help="C 编译器路径")
    parser.add_argument("--pkg-config", default="pkg-config", help="pkg-config 路径")
    return parser.parse_args()


def kotlin_trim_indent(text: str) -> str:
    lines = text.split("\n")
    if lines and not lines[0].strip():
        lines = lines[1:]
    if lines and not lines[-1].strip():
        lines = lines[:-1]
    indents = [len(line) - len(line.lstrip()) for line in lines if line.strip()]
    indent = min(indents) if indents else 0
    return "\n".join(line[indent:] if line.strip() else "" for line in lines)


def extract_shader_source(spec: ShaderSpec) -> str:
    if not spec.path.is_file():
        raise ValidationError(f"缺少生产源码文件：{spec.path}")
    text = spec.path.read_text(encoding="utf-8")
    pattern = re.compile(
        r"^[ \t]*(?:(?:private|internal|public|protected)\s+)*const\s+val\s+"
        + re.escape(spec.constant)
        + r"\s*(?::\s*String\s*)?=\s*\"\"\"(.*?)\"\"\"(?P<trim>\s*\.trimIndent\(\))?",
        re.MULTILINE | re.DOTALL,
    )
    matches = list(pattern.finditer(text))
    if not matches:
        raise ValidationError(
            f"{spec.path} 中找不到三引号常量 {spec.constant}"
        )
    if len(matches) > 1:
        raise ValidationError(
            f"{spec.path} 中常量 {spec.constant} 出现 {len(matches)} 次，无法确定使用哪一份"
        )
    source = matches[0].group(1)
    if "$" in source:
        raise ValidationError(
            f"{spec.path} 的 {spec.constant} 含字符串模板，无法静态提取"
        )
    if matches[0].group("trim"):
        source = kotlin_trim_indent(source)
    return source


def build_gles_source(source: str, stage: str) -> str:
    if re.match(r"^[ \t]*#version", source):
        raise ValidationError(
            "生产源码包含 #version 指令；运行时统一前置 #version 100，源码必须保持无版本指令"
        )
    if stage == "frag":
        return GLES_VERSION_PREFIX + GLES_FRAGMENT_PRECISION + source
    return GLES_VERSION_PREFIX + source


def write_source(path: Path, source: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")


def resolve_executable(name: str, label: str) -> str:
    candidate = Path(name)
    if candidate.is_absolute() or candidate.parent != Path("."):
        if candidate.is_file():
            return str(candidate)
        raise ValidationError(f"找不到{label}：{name}")
    resolved = shutil.which(name)
    if resolved is None:
        raise ValidationError(f"找不到{label}：{name}")
    return resolved


def run_command(command: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(command, capture_output=True, text=True, errors="replace", check=False)


def report_command_failure(label: str, command: list[str], result: subprocess.CompletedProcess) -> None:
    print(f"[失败] {label}", file=sys.stderr)
    print(f"  命令：{shlex.join(command)}", file=sys.stderr)
    output = (result.stdout + result.stderr).strip()
    if output:
        for line in output.splitlines():
            print(f"  {line}", file=sys.stderr)


def glslang_compile(glslang: str, variant: str, stage: str, source: Path) -> bool:
    command = [glslang]
    if variant == "桌面 GLSL110":
        command.append("-d")
    command += ["-S", stage, str(source)]
    result = run_command(command)
    if result.returncode != 0:
        report_command_failure(f"{variant} 编译 {source.name}", command, result)
        return False
    print(f"[通过] {variant} 编译 {source.name}")
    return True


def glslang_link(glslang: str, variant: str, vertex: Path, fragment: Path) -> bool:
    command = [glslang]
    if variant == "桌面 GLSL110":
        command.append("-d")
    command += ["-l", str(vertex), str(fragment)]
    result = run_command(command)
    if result.returncode != 0:
        report_command_failure(f"{variant} 链接 {fragment.name}", command, result)
        return False
    print(f"[通过] {variant} 链接 {fragment.name}")
    return True


def build_c_validator(cc: str, pkg_config: str, binary: Path) -> None:
    pkg_config_command = [pkg_config, "--cflags", "--libs", "egl", "glesv2"]
    result = run_command(pkg_config_command)
    if result.returncode != 0:
        report_command_failure("pkg-config egl glesv2", pkg_config_command, result)
        raise ValidationError("pkg-config 无法提供 egl / glesv2 编译参数")
    flags = shlex.split(result.stdout)
    command = [
        cc,
        "-std=c11",
        "-O2",
        "-Wall",
        "-Wextra",
        "-o",
        str(binary),
        str(C_VALIDATOR_SOURCE),
    ] + flags
    compiled = run_command(command)
    if compiled.returncode != 0:
        report_command_failure("编译 C GLES 验证器", command, compiled)
        raise ValidationError("C GLES 验证器编译失败")
    output = (compiled.stdout + compiled.stderr).strip()
    if output:
        print(output)


def run_gles_validator(
    binary: Path, vertex: Path, fragments: list[tuple[ShaderSpec, Path]]
) -> tuple[bool, dict[str, bool]]:
    command = [str(binary), "--vertex", str(vertex)]
    for spec, path in fragments:
        command += ["--program", f"{spec.slug}={path}"]
    environment = os.environ.copy()
    environment.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
    print(f"\n运行 GLES2 验证器，LIBGL_ALWAYS_SOFTWARE={environment['LIBGL_ALWAYS_SOFTWARE']}")
    result = subprocess.run(
        command, capture_output=True, text=True, errors="replace", env=environment, check=False
    )
    if result.stdout:
        print(result.stdout, end="" if result.stdout.endswith("\n") else "\n")
    if result.stderr:
        print(result.stderr, end="" if result.stderr.endswith("\n") else "\n", file=sys.stderr)
    statuses: dict[str, bool] = {}
    for line in result.stdout.splitlines():
        match = re.fullmatch(r"RESULT (\S+) (PASS|FAIL)", line.strip())
        if match:
            statuses[match.group(1)] = match.group(2) == "PASS"
    return result.returncode == 0, statuses


def main() -> int:
    args = parse_args()
    build_dir = args.build_dir.resolve()
    if not (C_VALIDATOR_SOURCE.is_file()):
        raise ValidationError(f"缺少 C 验证器源码：{C_VALIDATOR_SOURCE}")

    glslang = resolve_executable(args.glslang, "glslangValidator")
    cc = resolve_executable(args.cc, "C 编译器")
    pkg_config = resolve_executable(args.pkg_config, "pkg-config")

    shader_specs = (VERTEX_SPEC,) + FRAGMENT_SPECS
    sources = {spec.slug: extract_shader_source(spec) for spec in shader_specs}

    source_dir = build_dir / "sources"
    gles_dir = build_dir / "sources-es"
    raw_paths: dict[str, Path] = {}
    gles_paths: dict[str, Path] = {}
    for spec in shader_specs:
        raw_path = source_dir / f"{spec.slug}.{spec.stage}"
        gles_path = gles_dir / f"{spec.slug}.{spec.stage}"
        write_source(raw_path, sources[spec.slug])
        write_source(gles_path, build_gles_source(sources[spec.slug], spec.stage))
        raw_paths[spec.slug] = raw_path
        gles_paths[spec.slug] = gles_path

    print(f"已提取 {len(shader_specs)} 份着色器源码到 {source_dir}")

    results: dict[str, dict[str, bool]] = {}
    for spec in FRAGMENT_SPECS:
        results[spec.slug] = {
            "桌面编译": glslang_compile(glslang, "桌面 GLSL110", "frag", raw_paths[spec.slug]),
            "桌面链接": False,
            "GLES 编译": glslang_compile(glslang, "GLES100", "frag", gles_paths[spec.slug]),
            "GLES 链接": False,
        }

    desktop_vertex_ok = glslang_compile(glslang, "桌面 GLSL110", "vert", raw_paths["vertex"])
    gles_vertex_ok = glslang_compile(glslang, "GLES100", "vert", gles_paths["vertex"])

    for spec in FRAGMENT_SPECS:
        if desktop_vertex_ok and results[spec.slug]["桌面编译"]:
            results[spec.slug]["桌面链接"] = glslang_link(
                glslang, "桌面 GLSL110", raw_paths["vertex"], raw_paths[spec.slug]
            )
        if gles_vertex_ok and results[spec.slug]["GLES 编译"]:
            results[spec.slug]["GLES 链接"] = glslang_link(
                glslang, "GLES100", gles_paths["vertex"], gles_paths[spec.slug]
            )

    binary = build_dir / "validate_shader_gles"
    build_c_validator(cc, pkg_config, binary)
    runtime_ok, runtime_statuses = run_gles_validator(
        binary,
        gles_paths["vertex"],
        [(spec, gles_paths[spec.slug]) for spec in FRAGMENT_SPECS],
    )

    print("\n验证汇总：")
    all_passed = runtime_ok
    for spec in FRAGMENT_SPECS:
        entry = results[spec.slug]
        desktop_ok = entry["桌面编译"] and entry["桌面链接"]
        gles_ok = entry["GLES 编译"] and entry["GLES 链接"]
        program_runtime_ok = runtime_statuses.get(spec.slug, False)
        all_passed = all_passed and desktop_ok and gles_ok and program_runtime_ok
        print(
            f"  {spec.display_name}：桌面 GLSL110 {'通过' if desktop_ok else '失败'}"
            f"，GLES100 {'通过' if gles_ok else '失败'}"
            f"，GLES2 运行 {'通过' if program_runtime_ok else '失败'}"
        )
    if not all_passed:
        print("Shader 兼容性验证失败", file=sys.stderr)
        return 1
    print("Shader 兼容性验证通过")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ValidationError as error:
        print(f"验证无法执行：{error}", file=sys.stderr)
        sys.exit(2)
