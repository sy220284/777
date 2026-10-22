#!/usr/bin/env python3
"""Compare four Android page-size experiments from one CI runner and one APK."""
import csv
import json
import sys
from pathlib import Path

CELLS = ((16, 36, 4), (16, 36, 16), (17, 37, 4), (17, 37, 16))
SCENARIOS = (
    "work_2000_events_cold_projection",
    "work_2000_events_incremental_projection",
    "chat_10000_tool_rows_timeline_materialization",
    "oauth_intercepted_provider_full_protocol",
    "automation_real_workmanager_fail_closed_e2e",
)
NAME = {
    "work_2000_events_cold_projection": "Work cold projection",
    "work_2000_events_incremental_projection": "Work incremental projection",
    "chat_10000_tool_rows_timeline_materialization": "Chat 10k tool rows",
    "oauth_intercepted_provider_full_protocol": "MCP OAuth protocol",
    "automation_real_workmanager_fail_closed_e2e": "Automation / WorkManager",
}


def metadata(text):
    return dict(line.split("=", 1) for line in text.splitlines() if "=" in line)


def delta(a, b):
    """Percent change in execution time from baseline a to measurement b."""
    return round((b / a - 1) * 100, 2) if a > 0 else None


def compare(root, ci):
    status_file = root / "cell-status.tsv"
    with status_file.open(encoding="utf-8", newline="") as stream:
        statuses = list(csv.DictReader(stream, delimiter="\t"))
    expected = {(release, page) for release, _, page in CELLS}
    actual = {(int(row["android_release"]), int(row["page_kb"])) for row in statuses}
    if len(statuses) != 4 or actual != expected or any(r["status"] != "passed" for r in statuses):
        raise ValueError("All 4 real emulator test cells must finish successfully")

    results, envs = {}, {}
    for release, api, page in CELLS:
        folder = ci / f"android{release}-{page}k-ten-rounds"
        report_path = folder / "summary-10-rounds.json"
        if not report_path.is_file():
            raise ValueError(f"Missing ten-round summary: {report_path}")
        report = json.loads(report_path.read_text(encoding="utf-8"))
        if report.get("android_api") != api or report.get("rounds") != 10:
            raise ValueError(f"Android API or round count mismatch: {report_path}")
        env = metadata(report.get("host_environment") or "")
        if env.get("requested_api") != str(api) or env.get("device_api") != str(api):
            raise ValueError(f"Requested/actual API differ on Android {release}/{page}KB")
        if env.get("device_page_size") != str(page * 1024):
            raise ValueError(f"Page size verification failed on Android {release}/{page}KB")
        results[(release, page)] = report
        envs[(release, page)] = env
    for invariant in ("git_sha", "runner_name", "runner_os", "runner_arch", "host_cpu"):
        values = {env[invariant] for env in envs.values() if invariant in env}
        if len(values) != 1 or any(invariant not in env for env in envs.values()):
            raise ValueError(f"Same host/APK invariant failed: {invariant}={values}")

    rows = []
    markdown = [
        "# 777 Android 16 / 17 × 4KB / 16KB 同宿主对照",
        "",
        "这份数据来自同一 CI runner、同一 Git 提交的 APK 和一致的测试口径。"
        "系统镜像、内存页与运行顺序仍可能影响结果；数据为模拟器参考值。",
        "",
        "| 场景（十轮 P50 中位，ms） | 16/4KB | 16/16KB | 17/4KB | 17/16KB | 16 页差异 | 17 页差异 | 4KB 跨系统 | 16KB 跨系统 |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for scenario in SCENARIOS:
        values = {}
        for release, _, page in CELLS:
            detail = results[(release, page)]["by_scenario"][scenario]["p50_ms"]
            if detail is None or detail["median"] <= 0:
                raise ValueError(f"Missing positive ten-round P50: {release}/{page} {scenario}")
            values[(release, page)] = detail["median"]
        row = {
            "scenario": scenario,
            "android16_4k_ms": values[(16, 4)],
            "android16_16k_ms": values[(16, 16)],
            "android17_4k_ms": values[(17, 4)],
            "android17_16k_ms": values[(17, 16)],
            "android16_page_effect_pct": delta(values[(16, 4)], values[(16, 16)]),
            "android17_page_effect_pct": delta(values[(17, 4)], values[(17, 16)]),
            "same4k_android_effect_pct": delta(values[(16, 4)], values[(17, 4)]),
            "same16k_android_effect_pct": delta(values[(16, 16)], values[(17, 16)]),
        }
        rows.append(row)
        percent = [row[k] for k in (
            "android16_page_effect_pct", "android17_page_effect_pct",
            "same4k_android_effect_pct", "same16k_android_effect_pct",
        )]
        markdown.append(
            "| " + NAME[scenario] + " | " +
            " | ".join(f"{values[key]:.3f}" for key in ((16, 4), (16, 16), (17, 4), (17, 16))) +
            " | " + " | ".join(f"{v:+.2f}%" for v in percent) + " |"
        )
    frame_info = {}
    for release, _, page in CELLS:
        detail = results[(release, page)]["by_scenario"]["work_timeline_scroll_frames"]
        frame_info[f"android{release}_{page}k"] = {
            "valid_frame_rounds": detail["valid_frame_rounds"],
            "insufficient_frame_rounds": detail["insufficient_frame_rounds"],
            "median_fps": detail["observed_fps_p50"]["median"] if detail["observed_fps_p50"] else None,
            "peak_pss_kib_median": detail["peak_pss_kib"]["median"],
        }
    markdown.extend([
        "",
        "说明：百分比为相对基准的耗时变化；正数代表更慢，负数代表更快。",
        "滚动功能通过不代表 FPS 可评估；若 FrameMetrics 样本不足，FPS 始终记录 NA。",
        "即便四组共用宿主，系统镜像等差异仍存在；不能将相关性直接视为因果。",
        "",
        "## 滚动真实性与内存采样",
        "| 环境 | 有效 FPS 轮数 | FPS 中位数 | 峰值 PSS 中位数 KiB |",
        "| --- | ---: | ---: | ---: |",
    ])
    for release, _, page in CELLS:
        item = frame_info[f"android{release}_{page}k"]
        fps = f"{item['median_fps']:.2f}" if item["median_fps"] is not None else "NA"
        markdown.append(
            f"| Android {release} / {page}KB | {item['valid_frame_rounds']}/10 | "
            f"{fps} | {item['peak_pss_kib_median']:.0f} |"
        )
    out = {
        "source_git_sha": envs[(16, 4)]["git_sha"],
        "host_cpu": envs[(16, 4)]["host_cpu"],
        "all_four_verified": True,
        "comparison_rows": rows,
        "frame_evidence": frame_info,
        "environment_by_cell": {
            f"android{release}_{page}k": envs[(release, page)] for release, _, page in CELLS
        },
    }
    (root / "comparison.json").write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    (root / "comparison.md").write_text("\n".join(markdown) + "\n", encoding="utf-8")
    with (root / "comparison.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print("\n".join(markdown))
    return out


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: compare-android-page-matrix.py <matrix-folder> <ci-folder>")
    try:
        compare(Path(sys.argv[1]), Path(sys.argv[2]))
    except (ValueError, KeyError, OSError, json.JSONDecodeError) as error:
        raise SystemExit(f"Four-way Android comparison incomplete: {error}") from error


if __name__ == "__main__":
    main()
