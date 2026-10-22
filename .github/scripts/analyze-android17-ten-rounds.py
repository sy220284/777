#!/usr/bin/env python3
"""Validate ten Android 17 rounds and write reproducible per-run/aggregate evidence."""
import csv
import json
import statistics
import sys
from pathlib import Path

FIELDS = ("round", "scenario", "api", "samples", "p50_ms", "p95_ms",
          "max_ms", "refresh_hz", "observed_fps_p50", "jank_pct",
          "peak_pss_kib", "peak_java_used_kib")
EXPECTED = {
    "work_2000_events_cold_projection",
    "work_2000_events_incremental_projection",
    "chat_10000_tool_rows_timeline_materialization",
    "oauth_intercepted_provider_full_protocol",
    "automation_real_workmanager_fail_closed_e2e",
    "work_timeline_scroll_frames",
}


def parse(path, number):
    rows = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        start = "INSTRUMENTATION_STATUS: stream=ANDROID_PERF "
        if start not in line:
            continue
        part = line.split(start, 1)[1].strip()
        values = dict(pair.split("=", 1) for pair in part.split() if "=" in pair)
        scenario = values.get("scenario")
        if scenario in rows:
            raise ValueError(f"round {number}: duplicate metric {scenario}")
        if values.get("api") != "37":
            raise ValueError(f"round {number}: wrong Android API {values.get('api')}")
        rows[scenario] = {"round": number, **values}
    if set(rows) != EXPECTED:
        raise ValueError(f"round {number}: metric mismatch missing={EXPECTED-set(rows)} unexpected={set(rows)-EXPECTED}")
    for scenario, row in rows.items():
        for field in ("samples", "p50_ms", "p95_ms"):
            if scenario != "work_timeline_scroll_frames" and field not in row:
                raise ValueError(f"round {number}: missing {field} in {scenario}")
        if scenario == "work_timeline_scroll_frames":
            if int(row.get("samples", 0)) < 60 or float(row.get("observed_fps_p50", 0)) <= 0:
                raise ValueError(f"round {number}: insufficient actual frame samples")
    return list(rows.values())


def summarize(rows, metric):
    data = [float(item[metric]) for item in rows]
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
    root = Path(sys.argv[1])
    records = []
    for run in range(1, 11):
        filename = root / f"round-{run:02d}.log"
        if not filename.is_file():
            raise SystemExit(f"missing run {run}: {filename}")
        records.extend(parse(filename, run))
    with (root / "metrics-10-rounds.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(records)

    by_scenario = {}
    table = [
        "| 场景 | 十轮 P50中位值 | 最快/最慢轮 P50 | P50变异系数 | 首末变化 |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    for scenario in sorted(EXPECTED):
        subset = sorted((r for r in records if r["scenario"] == scenario), key=lambda r: r["round"])
        fields = ("observed_fps_p50", "jank_pct", "peak_pss_kib", "peak_java_used_kib") if scenario == "work_timeline_scroll_frames" else ("p50_ms", "p95_ms")
        by_scenario[scenario] = {field: summarize(subset, field) for field in fields}
        metric = by_scenario[scenario][fields[0]]
        unit = "fps" if scenario == "work_timeline_scroll_frames" else "ms"
        table.append(f"| {scenario} | {metric['median']:.3f} {unit} | {metric['min']:.3f}–{metric['max']:.3f} {unit} | {metric['cv_percent']:.2f}% | {metric['last_vs_first_percent']:+.2f}% |")

    summary = {
        "android_api": 37,
        "rounds": 10,
        "performance_metrics": 60,
        "host_environment": (root / "environment.txt").read_text(encoding="utf-8") if (root / "environment.txt").exists() else None,
        "by_scenario": by_scenario,
    }
    (root / "summary-10-rounds.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    (root / "summary-10-rounds.md").write_text("\n".join(table) + "\n", encoding="utf-8")
    print("ANDROID17_TEN_ROUNDS_SUMMARY")
    print("\n".join(table))


if __name__ == "__main__":
    main()
