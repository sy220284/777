#!/usr/bin/env python3
"""Analyze Android 16/17 ten-round data without fabricating insufficient frame metrics."""
import csv
import json
import statistics
import sys
from pathlib import Path

FIELDS = ("round", "scenario", "api", "samples", "p50_ms", "p95_ms", "max_ms",
          "refresh_hz", "observed_fps_p50", "jank_pct", "peak_pss_kib",
          "peak_java_used_kib", "sample_pss_kib", "sample_java_used_kib",
          "sample_quality", "gesture_source", "valid_vsync_intervals",
          "max_visible_index")
EXPECTED = {
    "work_2000_events_cold_projection",
    "work_2000_events_incremental_projection",
    "chat_10000_tool_rows_timeline_materialization",
    "oauth_intercepted_provider_full_protocol",
    "automation_real_workmanager_fail_closed_e2e",
    "work_timeline_scroll_frames",
}


def parse(path, round_number, api):
    rows = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        prefix = "INSTRUMENTATION_STATUS: stream=ANDROID_PERF "
        if prefix not in line:
            continue
        part = line.split(prefix, 1)[1].strip()
        values = dict(pair.split("=", 1) for pair in part.split() if "=" in pair)
        scenario = values.get("scenario")
        if scenario in rows:
            raise ValueError(f"round {round_number}: duplicate metric {scenario}")
        if values.get("api") != str(api):
            raise ValueError(f"round {round_number}: wrong Android API {values.get('api')}")
        rows[scenario] = {"round": round_number, **values}
    if set(rows) != EXPECTED:
        raise ValueError(
            f"round {round_number}: metric mismatch missing={EXPECTED-set(rows)} "
            f"unexpected={set(rows)-EXPECTED}"
        )
    for scenario, row in rows.items():
        if scenario != "work_timeline_scroll_frames":
            for field in ("samples", "p50_ms", "p95_ms"):
                if field not in row:
                    raise ValueError(f"round {round_number}: missing {field} in {scenario}")
            if scenario in {
                "work_2000_events_cold_projection",
                "work_2000_events_incremental_projection",
                "chat_10000_tool_rows_timeline_materialization",
            }:
                for field in ("sample_pss_kib", "sample_java_used_kib"):
                    if int(row.get(field, "0")) <= 0:
                        raise ValueError(
                            f"round {round_number}: missing/invalid {field} in {scenario}")
            continue
        quality = row.get("sample_quality")
        if row.get("gesture_source") != "android_input_realtime_touch":
            raise ValueError(f"round {round_number}: scrolling must use actual wall-clock touch events")
        frames = int(row.get("samples", -1))
        intervals = int(row.get("valid_vsync_intervals", -1))
        if quality not in {"valid", "insufficient"} or frames < 0 or intervals < 0:
            raise ValueError(f"round {round_number}: invalid sample quality data")
        expected_valid = frames >= 60 and intervals >= 40
        if (quality == "valid") != expected_valid:
            raise ValueError(f"round {round_number}: quality inconsistent with real frame counts")
        if int(row.get("max_visible_index", -1)) <= 2:
            raise ValueError(f"round {round_number}: real scrolling did not advance")
        if int(row.get("peak_pss_kib", 0)) <= 0:
            raise ValueError(f"round {round_number}: invalid PSS")
        if quality == "valid":
            if float(row.get("observed_fps_p50", 0)) <= 0:
                raise ValueError(f"round {round_number}: invalid FPS")
            if not 0 <= float(row.get("jank_pct", -1)) <= 100:
                raise ValueError(f"round {round_number}: invalid jank")
        elif row.get("observed_fps_p50") != "NA" or row.get("jank_pct") != "NA":
            raise ValueError(f"round {round_number}: insufficient frames must report NA")
    return list(rows.values())


def summarize(rows, metric):
    data = [float(item[metric]) for item in rows]
    if len(data) < 2:
        return None
    mean = statistics.mean(data)
    return {
        "metric": metric,
        "values_by_round": [round(x, 3) for x in data],
        "min": round(min(data), 3),
        "max": round(max(data), 3),
        "mean": round(mean, 3),
        "median": round(statistics.median(data), 3),
        "standard_deviation": round(statistics.stdev(data), 3),
        "cv_percent": round(100 * statistics.stdev(data) / mean, 2) if mean else None,
        "last_vs_first_percent": round((data[-1] / data[0] - 1) * 100, 2) if data[0] else None,
    }


def main():
    if len(sys.argv) != 3 or sys.argv[2] not in {"36", "37"}:
        raise SystemExit("usage: analyze-android17-ten-rounds.py <folder> <36|37>")
    root = Path(sys.argv[1])
    api = int(sys.argv[2])
    records = []
    for round_number in range(1, 11):
        filename = root / f"round-{round_number:02d}.log"
        if not filename.is_file():
            raise SystemExit(f"missing round {round_number}: {filename}")
        records.extend(parse(filename, round_number, api))

    with (root / "metrics-10-rounds.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=FIELDS, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(records)

    by_scenario = {}
    table = [
        "| 场景 | 十轮 P50 中位值 | 最快/最慢轮 P50 | P50 变异系数 | 首末变化 |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    for scenario in sorted(EXPECTED):
        subset = sorted((r for r in records if r["scenario"] == scenario),
                        key=lambda r: r["round"])
        if scenario == "work_timeline_scroll_frames":
            valid = [r for r in subset if r["sample_quality"] == "valid"]
            by_scenario[scenario] = {
                "sample_quality_by_round": [r["sample_quality"] for r in subset],
                "valid_frame_rounds": len(valid),
                "insufficient_frame_rounds": len(subset) - len(valid),
                "frames_by_round": [int(r["samples"]) for r in subset],
                "observed_fps_p50": summarize(valid, "observed_fps_p50"),
                "jank_pct": summarize(valid, "jank_pct"),
                "peak_pss_kib": summarize(subset, "peak_pss_kib"),
                "peak_java_used_kib": summarize(subset, "peak_java_used_kib"),
            }
            metric = by_scenario[scenario]["observed_fps_p50"]
            unit = "fps"
            if metric is None:
                table.append(f"| {scenario} | 不可评估（有效 {len(valid)}/10） | — | — | — |")
                continue
        else:
            by_scenario[scenario] = {
                field: summarize(subset, field) for field in ("p50_ms", "p95_ms")
            }
            if scenario in {
                "work_2000_events_cold_projection",
                "work_2000_events_incremental_projection",
                "chat_10000_tool_rows_timeline_materialization",
            }:
                by_scenario[scenario]["sample_pss_kib"] = summarize(subset, "sample_pss_kib")
                by_scenario[scenario]["sample_java_used_kib"] = summarize(
                    subset, "sample_java_used_kib")
            metric = by_scenario[scenario]["p50_ms"]
            unit = "ms"
        cv = f"{metric['cv_percent']:.2f}%" if metric["cv_percent"] is not None else "—"
        change = metric["last_vs_first_percent"]
        change_label = f"{change:+.2f}%" if change is not None else "—"
        table.append(
            f"| {scenario} | {metric['median']:.3f} {unit} | "
            f"{metric['min']:.3f}–{metric['max']:.3f} {unit} | {cv} | {change_label} |"
        )

    table.extend([
        "",
        "### Work/Chat 运行结束时内存快照（10 轮中位数；非峰值）",
        "| 场景 | 进程 PSS KiB | Java 堆占用 KiB |",
        "| --- | ---: | ---: |",
    ])
    for scenario in (
        "work_2000_events_cold_projection",
        "work_2000_events_incremental_projection",
        "chat_10000_tool_rows_timeline_materialization",
    ):
        memory = by_scenario[scenario]
        table.append(
            f"| {scenario} | {memory['sample_pss_kib']['median']:.0f} "
            f"| {memory['sample_java_used_kib']['median']:.0f} |"
        )
    scroll = by_scenario["work_timeline_scroll_frames"]
    summary = {
        "android_api": api,
        "rounds": 10,
        "performance_metrics": len(records),
        "frame_rate_verified": scroll["valid_frame_rounds"] >= 2,
        "frame_rate_note": (
            "Insufficient emulator FrameMetrics never become FPS/jank numbers; "
            "even valid SwiftShader samples are not physical-device benchmarks."
        ),
        "host_environment": (
            (root / "environment.txt").read_text(encoding="utf-8")
            if (root / "environment.txt").exists() else None
        ),
        "by_scenario": by_scenario,
    }
    (root / "summary-10-rounds.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    (root / "summary-10-rounds.md").write_text(
        "\n".join(table) + "\n", encoding="utf-8"
    )
    print(f"ANDROID{api}_TEN_ROUNDS_SUMMARY")
    print("\n".join(table))


if __name__ == "__main__":
    main()
