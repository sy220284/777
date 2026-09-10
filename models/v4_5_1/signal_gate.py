from __future__ import annotations

import argparse
import json
from pathlib import Path

import pandas as pd

WINDOW = 250
MIN_HISTORY = 50
RANDOM_TOP12_MEAN = 6 * 12 / 33
PRIZE_ORDER = {"一等奖": 1, "二等奖": 2, "三等奖": 3, "四等奖": 4, "五等奖": 5, "六等奖": 6, "未中奖": 99}
FIXED_PRIZE = {"一等奖": 0, "二等奖": 0, "三等奖": 3000, "四等奖": 200, "五等奖": 10, "六等奖": 5, "未中奖": 0}


def _numbers(text: str) -> set[int]:
    return {int(x) for x in str(text).split()}


def _validate(v43: pd.DataFrame, v45: pd.DataFrame) -> None:
    if len(v43) != len(v45):
        raise ValueError("V4.3 与 V4.5 回测长度不一致")
    if not v43["seq"].reset_index(drop=True).equals(v45["seq"].reset_index(drop=True)):
        raise ValueError("V4.3 与 V4.5 seq 未对齐")


def apply_signal_gate(v43: pd.DataFrame, v45: pd.DataFrame, window: int = WINDOW) -> pd.DataFrame:
    """只在V4前12近期覆盖仍高于随机基线时启用V4.5集中策略。

    当前期的开关只读取之前已经开奖的期次；当前期开奖结果只在开奖后进入后续窗口。
    """
    _validate(v43, v45)
    top12_hits = []
    for _, row in v45.iterrows():
        top12_hits.append(len(_numbers(row["rank_top12"]) & _numbers(row["actual_red"])))

    rows = []
    for i in range(len(v45)):
        left = max(0, i - window)
        past = top12_hits[left:i]
        rolling_mean = sum(past) / len(past) if past else float("nan")
        signal_active = len(past) >= MIN_HISTORY and rolling_mean >= RANDOM_TOP12_MEAN
        source = v45.iloc[i] if signal_active else v43.iloc[i]
        out = source.to_dict()
        out["selected_strategy"] = "V4.5" if signal_active else "V4.3"
        out["signal_active"] = bool(signal_active)
        out["signal_window"] = window
        out["signal_top12_mean"] = rolling_mean
        out["signal_random_baseline"] = RANDOM_TOP12_MEAN
        rows.append(out)
    return pd.DataFrame(rows)


def summarize(result: pd.DataFrame) -> dict:
    best_counts = result["best_prize"].value_counts().to_dict()
    ticket_prizes = pd.concat([result["prize1"], result["prize2"]], ignore_index=True)
    ticket_counts = ticket_prizes.value_counts().to_dict()
    fixed_return = int(sum(FIXED_PRIZE[p] for p in ticket_prizes))
    draws = len(result)
    return {
        "model": "V4.5.1 可预测性信号门控",
        "rule": {
            "window": WINDOW,
            "minimum_history": MIN_HISTORY,
            "metric": "目标期之前250期V4前12候选平均命中红球数",
            "random_baseline": RANDOM_TOP12_MEAN,
            "signal_on": "滚动均值 >= 6*12/33 时使用V4.5，否则回退V4.3",
        },
        "tested_draws": draws,
        "tickets": draws * 2,
        "best_prize_counts": {k: int(best_counts.get(k, 0)) for k in PRIZE_ORDER},
        "ticket_prize_counts": {k: int(ticket_counts.get(k, 0)) for k in PRIZE_ORDER},
        "winning_draw_rate": float((result["best_prize"] != "未中奖").mean()),
        "winning_ticket_rate": float((ticket_prizes != "未中奖").mean()),
        "fixed_return_yuan": fixed_return,
        "fixed_return_ratio": float(fixed_return / (draws * 4)),
        "signal_active_rate": float(result["signal_active"].mean()),
        "note": "一、二等奖奖金为浮动奖，本固定回报口径不计其金额；当前盲测未出现一、二等奖。",
    }


def main() -> None:
    p = argparse.ArgumentParser(description="V4.5.1 可预测性信号门控")
    p.add_argument("--v43", required=True, help="V4.3逐期回测CSV")
    p.add_argument("--v45", required=True, help="V4.5逐期回测CSV")
    p.add_argument("--out", required=True)
    p.add_argument("--summary", required=True)
    args = p.parse_args()

    v43 = pd.read_csv(args.v43)
    v45 = pd.read_csv(args.v45)
    result = apply_signal_gate(v43, v45)
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    result.to_csv(args.out, index=False, encoding="utf-8-sig")
    Path(args.summary).write_text(json.dumps(summarize(result), ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
