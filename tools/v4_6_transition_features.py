from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
MODEL_START = 500
START = 750
BLOCK = 250
TRANSITION_WINDOW = 500
SEGMENTS = {
    "validation_1": (2447, 2946),
    "validation_2": (2947, 3446),
    "forward55": (3447, 3501),
    "live2": (3502, 3503),
}
LIVE_106 = {"seq": 3503, "red1": 6, "red2": 11, "red3": 13, "red4": 14, "red5": 22, "red6": 30, "blue": 14}
FIXED_PRIZE = {"一等奖": 0, "二等奖": 0, "三等奖": 3000, "四等奖": 200, "五等奖": 10, "六等奖": 5, "未中奖": 0}
PRIZE_ORDER = {"一等奖": 1, "二等奖": 2, "三等奖": 3, "四等奖": 4, "五等奖": 5, "六等奖": 6, "未中奖": 99}


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v4 = _load("v46_trans_v4", ROOT / "models" / "v4" / "predictor.py")
v45 = _load("v46_trans_v45", ROOT / "models" / "v4_5" / "predictor.py")
history_tool = _load("v46_trans_history", ROOT / "tools" / "load_history.py")


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


def build_transition_features(occ: np.ndarray, min_history: int = 200) -> dict[int, np.ndarray]:
    """真正的跨期开奖特征：只统计 s期号码 -> s+1期号码，目标t仅使用截至t-1已完成的转移。"""
    n = len(occ)
    trans_draw = np.zeros((n, v4.RED_COUNT, v4.RED_COUNT), dtype=np.int16)
    source_draw = np.zeros((n, v4.RED_COUNT), dtype=np.int8)
    for k in range(1, n):
        trans_draw[k] = np.outer(occ[k - 1], occ[k]).astype(np.int16)
        source_draw[k] = occ[k - 1]
    trans_cs = np.concatenate([np.zeros((1, v4.RED_COUNT, v4.RED_COUNT), dtype=np.int32), np.cumsum(trans_draw, axis=0, dtype=np.int32)], axis=0)
    source_cs = np.concatenate([np.zeros((1, v4.RED_COUNT), dtype=np.int32), np.cumsum(source_draw, axis=0, dtype=np.int32)], axis=0)

    out: dict[int, np.ndarray] = {}
    for t in range(min_history, n):
        # prefix t 恰好排除未知的 t-1 -> t 转移。
        all_counts = (trans_cs[t] - trans_cs[1]).astype(float)
        all_sources = (source_cs[t] - source_cs[1]).astype(float)
        left = max(1, t - TRANSITION_WINDOW)
        win_counts = (trans_cs[t] - trans_cs[left]).astype(float)
        win_sources = (source_cs[t] - source_cs[left]).astype(float)
        all_cond = (all_counts + 1.0) / (all_sources[:, None] + 5.5)
        win_cond = (win_counts + 1.0) / (win_sources[:, None] + 5.5)
        prev_idx = np.flatnonzero(occ[t - 1])
        if len(prev_idx) != 6:
            raise RuntimeError(f"上一期红球数量异常: t={t}")
        all_mean = all_cond[prev_idx].mean(axis=0)
        win_mean = win_cond[prev_idx].mean(axis=0)
        all_max = all_cond[prev_idx].max(axis=0)
        win_max = win_cond[prev_idx].max(axis=0)
        out[t] = np.column_stack([all_mean, win_mean, all_max, win_max])
    return out


def build_augmented(df: pd.DataFrame):
    base_x, y, t_index, occ = v4.build_red_features(df)
    trans = build_transition_features(occ)
    extra = np.stack([trans[int(t)] for t in t_index])
    return np.concatenate([base_x, extra], axis=-1), y, t_index, occ


def walk_forward_augmented(df: pd.DataFrame):
    x, y, t_index, occ = build_augmented(df)
    n = len(df)
    model_defs = {"all": None, "w1000": 1000, "w500": 500}
    raw = {name: np.full((n, v4.RED_COUNT), np.nan) for name in model_defs}
    for bstart in range(MODEL_START, n, BLOCK):
        bend = min(n, bstart + BLOCK)
        test = (t_index >= bstart) & (t_index < bend)
        for name, window in model_defs.items():
            train = t_index < bstart
            if window is not None:
                train &= t_index >= max(200, bstart - window)
            model = v4._logistic()
            model.fit(x[train].reshape(-1, x.shape[-1]), y[train].reshape(-1))
            p = model.predict_proba(x[test].reshape(-1, x.shape[-1]))[:, 1]
            raw[name][t_index[test]] = p.reshape(-1, v4.RED_COUNT)

    ensemble = np.full((n, v4.RED_COUNT), np.nan)
    names = list(model_defs)
    hit_cs = {}
    for name in names:
        hits = np.zeros(n, dtype=float)
        for u in range(MODEL_START, n):
            hits[u] = v4._topk_hits(raw[name][u], occ[u], 10)
        hit_cs[name] = np.concatenate([[0.0], np.cumsum(hits)])
    for t in range(MODEL_START + BLOCK, n):
        left = max(MODEL_START, t - BLOCK)
        perf = np.asarray([(hit_cs[name][t] - hit_cs[name][left]) / max(1, t - left) for name in names])
        baseline = v4.RED_PICK * 10 / v4.RED_COUNT
        z = np.clip((perf - baseline) / 0.08, -3.0, 3.0)
        w = np.exp(z - z.max()); w /= w.sum()
        ensemble[t] = sum(w[i] * v4._rank_score(raw[name][t]) for i, name in enumerate(names))
    return ensemble, occ


def _rank(row: np.ndarray) -> np.ndarray:
    o = np.argsort(row); r = np.empty(len(row), dtype=float); r[o] = np.arange(len(row), dtype=float)
    return r / max(1, len(row) - 1)


def blend(base: np.ndarray, aug: np.ndarray, weight: float) -> np.ndarray:
    out = np.full_like(base, np.nan, dtype=float)
    for t in range(len(base)):
        if np.isfinite(base[t]).all() and np.isfinite(aug[t]).all():
            out[t] = (1 - weight) * _rank(base[t]) + weight * _rank(aug[t])
    return out


def candidate(pred, occ, seqs, lo, hi):
    rows = []
    for t in np.where((seqs >= lo) & (seqs <= hi))[0]:
        if np.isfinite(pred[t]).all():
            o = np.argsort(pred[t])[::-1]
            rows.append((int(occ[t, o[:6]].sum()), int(occ[t, o[:12]].sum())))
    a = np.asarray(rows, dtype=int)
    return {"draws": int(len(a)), "top6_mean": float(a[:,0].mean()), "top12_mean": float(a[:,1].mean()), "top12_4plus": int((a[:,1]>=4).sum()), "top12_5plus": int((a[:,1]>=5).sum())}


def _signal(pred, occ):
    hits = np.full(len(pred), np.nan); active = np.zeros(len(pred), dtype=bool); threshold = 6*12/33
    for t in range(START, len(pred)):
        if np.isfinite(pred[t]).all(): hits[t] = int(occ[t, np.argsort(pred[t])[::-1][:12]].sum())
    for t in range(START, len(pred)):
        p = hits[max(START,t-250):t]; p = p[np.isfinite(p)]
        active[t] = len(p)>=50 and float(p.mean())>=threshold
    return active


def _best(a,b): return a if PRIZE_ORDER[a] <= PRIZE_ORDER[b] else b


def portfolio(df, pred, occ, blue_base, blue_models):
    low,_,_ = v45._baseline_red_groups(pred, occ, START); high,_,_ = v45.build_red_portfolio(pred, occ, START); active = _signal(pred, occ)
    actual = df["blue"].to_numpy(int); blue_high,_ = v45.fuse_blue_predictions(blue_models, high, occ, actual, START); rows=[]
    for t in range(START,len(df)):
        a,b = (high if active[t] else low)[t]; bs = blue_high[t] if active[t] else blue_base[t]; bo=np.argsort(bs)[::-1]; b1,b2=int(bo[0]+1),int(bo[1]+1)
        h1,h2=int(occ[t,a].sum()),int(occ[t,b].sum()); p1=v4.prize_level(h1,b1==actual[t]); p2=v4.prize_level(h2,b2==actual[t])
        rows.append({"seq":int(df.iloc[t]["seq"]),"max_red_hit":max(h1,h2),"prize1":p1,"prize2":p2,"best_prize":_best(p1,p2)})
    return pd.DataFrame(rows)


def psum(result,lo,hi):
    p=result[(result.seq>=lo)&(result.seq<=hi)]; tickets=pd.concat([p.prize1,p.prize2],ignore_index=True)
    return {"max_red_hit_mean":float(p.max_red_hit.mean()),"red_4plus":int((p.max_red_hit>=4).sum()),"red_5plus":int((p.max_red_hit>=5).sum()),"fixed_return":int(sum(FIXED_PRIZE[x] for x in tickets)),"winning_draw_rate":float((p.best_prize!="未中奖").mean())}


def main():
    df=load_dataframe(); seqs=df["seq"].to_numpy(int); base,_,occ=v4.walk_forward_red(df); aug,occ2=walk_forward_augmented(df)
    if not np.array_equal(occ,occ2): raise RuntimeError("红球发生矩阵不一致")
    configs={"base":base,"transition":aug,"base75_transition25":blend(base,aug,.25),"base50_transition50":blend(base,aug,.50)}
    blue_base,_=v4.walk_forward_blue(df); blue_models=v45.build_blue_models(df); report={"experiment":"V4.6真实跨期红球转移特征","configs":{},"promotion":{}}
    for name,pred in configs.items():
        port=portfolio(df,pred,occ,blue_base,blue_models); report["configs"][name]={seg:{"candidate":candidate(pred,occ,seqs,*rng),"portfolio":psum(port,*rng)} for seg,rng in SEGMENTS.items()}
    base_cfg=report["configs"]["base"]
    for name in ("transition","base75_transition25","base50_transition50"):
        checks={}; ok=True; positive=0
        for seg in ("validation_1","validation_2","forward55"):
            b=base_cfg[seg]; c=report["configs"][name][seg]; d12=c["candidate"]["top12_mean"]-b["candidate"]["top12_mean"]; d5=c["candidate"]["top12_5plus"]-b["candidate"]["top12_5plus"]; dret=c["portfolio"]["fixed_return"]-b["portfolio"]["fixed_return"]; pr5=c["portfolio"]["red_5plus"]-b["portfolio"]["red_5plus"]
            nw=d12>=-1e-12 and d5>=0 and dret>=0 and pr5>=0; imp=d12>1e-12 or d5>0 or dret>0 or pr5>0; ok &= nw; positive += int(imp); checks[seg]={"top12_mean_delta":float(d12),"top12_5plus_delta":int(d5),"fixed_return_delta":int(dret),"portfolio_red5_delta":int(pr5),"nonworse":bool(nw),"improved":bool(imp)}
        report["promotion"][name]={"pass":bool(ok and positive>=2),"positive_segments":int(positive),"checks":checks}
    out=ROOT/"backtests"/"v4_6"/"transition_features.json"; out.parent.mkdir(parents=True,exist_ok=True); out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8"); print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__ == "__main__": main()
