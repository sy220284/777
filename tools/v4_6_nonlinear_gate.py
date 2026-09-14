from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
WINDOW = 250
MIN_HISTORY = 50
MARGIN = 0.03
RANDOM_TOP12 = 6 * 12 / 33
START = 750
SEGMENTS = {
    "validation_1": (2447, 2946),
    "validation_2": (2947, 3446),
    "forward55": (3447, 3501),
    "live2": (3502, 3503),
}


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


exp = _load("v46ng_exp", ROOT / "tools" / "v4_6_nonlinear_experiment.py")
v4 = exp.v4


def top12_hits(pred, occ):
    hits=np.full(len(pred),np.nan)
    for t in range(START,len(pred)):
        if np.isfinite(pred[t]).all():
            hits[t]=int(occ[t,np.argsort(pred[t])[::-1][:12]].sum())
    return hits


def causal_gate(base, challenger, occ):
    base_hits=top12_hits(base,occ)
    ch_hits=top12_hits(challenger,occ)
    out=base.copy()
    active=np.zeros(len(base),dtype=bool)
    stats=[]
    for t in range(START,len(base)):
        left=max(START,t-WINDOW)
        b=base_hits[left:t]; c=ch_hits[left:t]
        valid=np.isfinite(b)&np.isfinite(c)
        if valid.sum()<MIN_HISTORY:
            continue
        bm=float(b[valid].mean()); cm=float(c[valid].mean())
        use=cm>=RANDOM_TOP12 and cm>=bm+MARGIN
        if use:
            out[t]=challenger[t]
            active[t]=True
        stats.append((t,bm,cm,use))
    return out,active,stats


def main():
    df=exp.load_dataframe(include_live106=True)
    seqs=df["seq"].to_numpy(int)
    base,_,occ=v4.walk_forward_red(df)
    h1000,_=exp.walk_forward_hgb(df,train_window=1000)
    h500,_=exp.walk_forward_hgb(df,train_window=500)
    challenger=exp.average_rank(h1000,h500)
    gated,active,stats=causal_gate(base,challenger,occ)

    report={
        "experiment":"非线性挑战者因果门控",
        "window":WINDOW,
        "margin":MARGIN,
        "random_top12":RANDOM_TOP12,
        "segments":{},
        "promotion":{},
    }
    for seg,rng in SEGMENTS.items():
        lo,hi=rng
        base_m=exp.candidate_metrics(base,occ,seqs,lo,hi)
        gate_m=exp.candidate_metrics(gated,occ,seqs,lo,hi)
        idx=(seqs>=lo)&(seqs<=hi)
        report["segments"][seg]={
            "base":base_m,
            "gated":gate_m,
            "active_draws":int(active[idx].sum()),
            "top12_delta":float(gate_m["top12_mean"]-base_m["top12_mean"]),
            "top6_delta":float(gate_m["top6_mean"]-base_m["top6_mean"]),
            "top12_4plus_delta":int(gate_m["top12_4plus"]-base_m["top12_4plus"]),
            "top12_5plus_delta":int(gate_m["top12_5plus"]-base_m["top12_5plus"]),
        }

    all_nonworse=True; positive=False
    for seg in ("validation_1","validation_2","forward55"):
        r=report["segments"][seg]
        nonworse=r["top12_delta"]>=-1e-12 and r["top12_5plus_delta"]>=0
        report["promotion"][seg]={"nonworse":bool(nonworse)}
        all_nonworse &= nonworse
        positive |= r["top12_delta"]>1e-12 or r["top12_4plus_delta"]>0 or r["top12_5plus_delta"]>0
    report["promotion"]["pass"]=bool(all_nonworse and positive)

    out=ROOT/"backtests"/"v4_6"/"nonlinear_gate.json"
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__=="__main__":
    main()
