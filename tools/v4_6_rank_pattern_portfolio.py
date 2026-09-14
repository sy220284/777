from __future__ import annotations

import importlib.util
import itertools
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
START = 750
WINDOW = 1000
MIN_HISTORY = 50
RANDOM_TOP12_MEAN = 6 * 12 / 33
SEGMENTS = {
    "validation_1": (2447, 2946),
    "validation_2": (2947, 3446),
    "forward55": (3447, 3501),
    "live2": (3502, 3503),
}
LIVE_106 = {"seq": 3503, "red1": 6, "red2": 11, "red3": 13, "red4": 14, "red5": 22, "red6": 30, "blue": 14}
FIXED_PRIZE = {"一等奖": 0, "二等奖": 0, "三等奖": 3000, "四等奖": 200, "五等奖": 10, "六等奖": 5, "未中奖": 0}
PRIZE_ORDER = {"一等奖": 1, "二等奖": 2, "三等奖": 3, "四等奖": 4, "五等奖": 5, "六等奖": 6, "未中奖": 99}
COMB4 = np.asarray([0, 0, 0, 0, 1, 5, 15], dtype=float)


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v4 = _load("v46_rankpat_v4", ROOT / "models" / "v4" / "predictor.py")
v45 = _load("v46_rankpat_v45", ROOT / "models" / "v4_5" / "predictor.py")
history_tool = _load("v46_rankpat_history", ROOT / "tools" / "load_history.py")


def load_dataframe() -> pd.DataFrame:
    rows = history_tool.load_history(history_tool.BASE_SNAPSHOT, history_tool.CURRENT_INCREMENT)
    df = pd.DataFrame([{
        "seq": int(r["seq"]),
        **{f"red{i}": int(r["red"][i - 1]) for i in range(1, 7)},
        "blue": int(r["blue"]),
    } for r in rows])
    if int(df.iloc[-1]["seq"]) == 3502:
        df = pd.concat([df, pd.DataFrame([LIVE_106])], ignore_index=True)
    return df


def _partition_masks() -> np.ndarray:
    masks = []
    for choice in itertools.combinations(range(12), 6):
        if 0 not in choice:
            continue
        m = np.zeros(12, dtype=np.int8)
        m[list(choice)] = 1
        masks.append(m)
    return np.asarray(masks, dtype=np.int8)


MASKS = _partition_masks()


def _signal_mask(pred: np.ndarray, occ: np.ndarray) -> np.ndarray:
    hits = np.full(len(pred), np.nan)
    active = np.zeros(len(pred), dtype=bool)
    for t in range(START, len(pred)):
        if np.isfinite(pred[t]).all():
            order = np.argsort(pred[t])[::-1][:12]
            hits[t] = int(occ[t, order].sum())
    for t in range(START, len(pred)):
        past = hits[max(START, t - 250):t]
        past = past[np.isfinite(past)]
        active[t] = len(past) >= 50 and float(past.mean()) >= RANDOM_TOP12_MEAN
    return active


def build_rank_hit_history(pred: np.ndarray, occ: np.ndarray) -> np.ndarray:
    out = np.full((len(pred), 12), -1, dtype=np.int8)
    for t in range(START, len(pred)):
        if np.isfinite(pred[t]).all():
            order = np.argsort(pred[t])[::-1][:12]
            out[t] = occ[t, order]
    return out


def build_rank_pattern_groups(pred: np.ndarray, occ: np.ndarray) -> dict[int, tuple[np.ndarray, np.ndarray]]:
    """只学习过去开奖中V4前12“排名位置”的共同命中模式，当前期结果绝不参与。"""
    hit_hist = build_rank_hit_history(pred, occ)
    groups: dict[int, tuple[np.ndarray, np.ndarray]] = {}
    for t in range(START, len(pred)):
        order = np.argsort(pred[t])[::-1]
        top12 = order[:12]
        left = max(START, t - WINDOW)
        past = hit_hist[left:t]
        past = past[(past[:, 0] >= 0)]
        if len(past) < MIN_HISTORY:
            groups[t] = (order[:6], order[6:12])
            continue
        totals = past.sum(axis=1)
        h_a = past @ MASKS.T
        h_b = totals[:, None] - h_a
        utility = COMB4[h_a] + COMB4[h_b]
        scores = utility.mean(axis=0)
        best = int(np.argmax(scores))
        mask = MASKS[best].astype(bool)
        a, b = top12[mask], top12[~mask]
        if pred[t, a].sum() < pred[t, b].sum():
            a, b = b, a
        groups[t] = (a, b)
    return groups


def _best(a: str, b: str) -> str:
    return a if PRIZE_ORDER[a] <= PRIZE_ORDER[b] else b


def evaluate(df: pd.DataFrame, pred: np.ndarray, occ: np.ndarray, low_groups, high_groups, signal, blue_base, blue_high) -> pd.DataFrame:
    actual = df["blue"].to_numpy(int)
    rows = []
    for t in range(START, len(df)):
        high = bool(signal[t])
        a, b = (high_groups if high else low_groups)[t]
        bs = blue_high[t] if high else blue_base[t]
        bo = np.argsort(bs)[::-1]
        b1, b2 = int(bo[0] + 1), int(bo[1] + 1)
        h1, h2 = int(occ[t, a].sum()), int(occ[t, b].sum())
        p1 = v4.prize_level(h1, b1 == actual[t]); p2 = v4.prize_level(h2, b2 == actual[t])
        rows.append({"seq": int(df.iloc[t]["seq"]), "signal_active": high, "hit1": h1, "hit2": h2, "max_red_hit": max(h1,h2), "prize1": p1, "prize2": p2, "best_prize": _best(p1,p2)})
    return pd.DataFrame(rows)


def summary(result: pd.DataFrame, lo: int, hi: int) -> dict:
    part = result[(result.seq >= lo) & (result.seq <= hi)]
    low = part[~part.signal_active]
    tickets = pd.concat([part.prize1, part.prize2], ignore_index=True)
    return {
        "draws": int(len(part)),
        "low_signal_draws": int(len(low)),
        "max_red_hit_mean": float(part.max_red_hit.mean()),
        "low_signal_max_red_hit_mean": float(low.max_red_hit.mean()) if len(low) else None,
        "red_4plus": int((part.max_red_hit >= 4).sum()),
        "red_5plus": int((part.max_red_hit >= 5).sum()),
        "fixed_return": int(sum(FIXED_PRIZE[x] for x in tickets)),
        "winning_draw_rate": float((part.best_prize != "未中奖").mean()),
    }


def main() -> None:
    df = load_dataframe()
    pred, _, occ = v4.walk_forward_red(df)
    signal = _signal_mask(pred, occ)
    base_low, _, _ = v45._baseline_red_groups(pred, occ, START)
    rank_low = build_rank_pattern_groups(pred, occ)
    high, _, _ = v45.build_red_portfolio(pred, occ, START)
    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)
    actual = df["blue"].to_numpy(int)
    blue_high, _ = v45.fuse_blue_predictions(blue_models, high, occ, actual, START)

    base = evaluate(df, pred, occ, base_low, high, signal, blue_base, blue_high)
    cand = evaluate(df, pred, occ, rank_low, high, signal, blue_base, blue_high)
    report = {"experiment": "V4.5.1低信号排名位置聚集组合", "window": WINDOW, "utility": "C(hit,4)两票合计", "configs": {}, "promotion": {}}
    for name, result in (("base",base),("rank_pattern",cand)):
        report["configs"][name] = {seg: summary(result,*rng) for seg,rng in SEGMENTS.items()}
    ok=True; positive=0; checks={}
    for seg in ("validation_1","validation_2","forward55"):
        b=report["configs"]["base"][seg]; c=report["configs"]["rank_pattern"][seg]
        dm=c["max_red_hit_mean"]-b["max_red_hit_mean"]; d4=c["red_4plus"]-b["red_4plus"]; d5=c["red_5plus"]-b["red_5plus"]; dr=c["fixed_return"]-b["fixed_return"]
        nw=dm>=-1e-12 and d5>=0 and dr>=0; imp=dm>1e-12 or d4>0 or d5>0 or dr>0; ok &= nw; positive += int(imp)
        checks[seg]={"max_red_hit_mean_delta":float(dm),"red_4plus_delta":int(d4),"red_5plus_delta":int(d5),"fixed_return_delta":int(dr),"nonworse":bool(nw),"improved":bool(imp)}
    report["promotion"]={"pass":bool(ok and positive>=1),"positive_segments":int(positive),"checks":checks}
    out=ROOT/"backtests"/"v4_6"/"rank_pattern_portfolio.json"; out.parent.mkdir(parents=True,exist_ok=True); out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8"); print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__ == "__main__": main()
