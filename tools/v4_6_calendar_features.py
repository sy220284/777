from __future__ import annotations

import importlib.util
import json
import urllib.request
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
SOURCE_A = "https://raw.githubusercontent.com/yangxb919/lottery-data/main/data/ssq.json"
SOURCE_B = "https://raw.githubusercontent.com/Justdoitfor/my-lottery-data/main/data/ssq.json"
LIVE_106 = {"seq": 3503, "red1": 6, "red2": 11, "red3": 13, "red4": 14, "red5": 22, "red6": 30, "blue": 14}
START = 750
BLOCK = 250
MODEL_START = 500
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


v4 = _load("v46cal_v4", ROOT / "models" / "v4" / "predictor.py")
history_tool = _load("v46cal_history", ROOT / "tools" / "load_history.py")


def _fetch(url: str) -> list[dict]:
    req = urllib.request.Request(url, headers={"User-Agent":"777-v46-calendar/1.0"})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.loads(r.read().decode("utf-8"))
    rows = []
    for item in data:
        issue_raw = str(item["issue"])
        issue = int("20" + issue_raw) if len(issue_raw) == 5 else int(issue_raw)
        if issue > 2026106:
            continue
        blue_raw = item["blue"]
        blue = int(blue_raw[0] if isinstance(blue_raw, list) else blue_raw)
        rows.append({
            "issue": issue,
            "date": str(item["date"]),
            "red": sorted(int(x) for x in item["red"]),
            "blue": blue,
        })
    rows.sort(key=lambda r:r["issue"])
    return rows


def load_dataframe() -> pd.DataFrame:
    a, b = _fetch(SOURCE_A), _fetch(SOURCE_B)
    if len(a) != len(b) or len(a) != 3503:
        raise RuntimeError(f"外部日期源期数异常: A={len(a)} B={len(b)}")
    for i,(ra,rb) in enumerate(zip(a,b),start=1):
        if (ra["issue"],ra["date"],ra["red"],ra["blue"]) != (rb["issue"],rb["date"],rb["red"],rb["blue"]):
            raise RuntimeError(f"两个日期源不一致: seq={i}")

    repo = history_tool.load_history(history_tool.BASE_SNAPSHOT, history_tool.CURRENT_INCREMENT)
    repo_records = [{"seq":int(r["seq"]),"red":[int(x) for x in r["red"]],"blue":int(r["blue"])} for r in repo]
    if len(repo_records) != 3502:
        raise RuntimeError(f"仓库当前历史期数异常: {len(repo_records)}")
    for i,r in enumerate(repo_records,start=1):
        ext = a[i-1]
        if r["red"] != ext["red"] or r["blue"] != ext["blue"]:
            raise RuntimeError(f"外部日期源与仓库历史不一致: seq={i}")
    if a[-1]["red"] != [6,11,13,14,22,30] or a[-1]["blue"] != 14:
        raise RuntimeError("2026106现场留出号码与预期不一致")

    rows=[]
    for seq,item in enumerate(a,start=1):
        rows.append({
            "seq":seq,
            **{f"red{i}":item["red"][i-1] for i in range(1,7)},
            "blue":item["blue"],
            "date":item["date"],
            "issue":item["issue"],
        })
    return pd.DataFrame(rows)


def calendar_feature_cube(df: pd.DataFrame, occ: np.ndarray, min_history: int=200):
    dates = pd.to_datetime(df["date"], format="%Y-%m-%d")
    weekdays = dates.dt.weekday.to_numpy(int)
    n = len(df)
    out = {}
    history_by_weekday = {w:[] for w in range(7)}

    for t in range(n):
        w = int(weekdays[t])
        prior = history_by_weekday[w]
        if t >= min_history:
            row=[]
            for n0 in range(v4.RED_COUNT):
                if prior:
                    idx=np.asarray(prior,dtype=int)
                    same_long=float(occ[idx,n0].mean())
                    same12=float(occ[idx[-12:],n0].mean())
                    same36=float(occ[idx[-36:],n0].mean())
                    prev_same=float(occ[idx[-1],n0])
                else:
                    same_long=same12=same36=prev_same=0.0
                overall50=float(occ[max(0,t-50):t,n0].mean())
                overall200=float(occ[max(0,t-200):t,n0].mean())
                row.append([
                    same12,
                    same36,
                    same_long,
                    same12-overall50,
                    same36-overall200,
                    prev_same,
                ])
            out[t]=np.asarray(row,dtype=float)
        history_by_weekday[w].append(t)
    return out


def build_augmented_features(df: pd.DataFrame):
    base_x,y,t_index,occ=v4.build_red_features(df)
    cal=calendar_feature_cube(df,occ)
    extras=np.stack([cal[int(t)] for t in t_index])
    return np.concatenate([base_x,extras],axis=-1),y,t_index,occ


def walk_forward_augmented(df: pd.DataFrame):
    x,y,t_index,occ=build_augmented_features(df)
    n=len(df)
    model_defs={"all":None,"w1000":1000,"w500":500}
    raw={name:np.full((n,v4.RED_COUNT),np.nan) for name in model_defs}
    for bstart in range(MODEL_START,n,BLOCK):
        bend=min(n,bstart+BLOCK)
        test=(t_index>=bstart)&(t_index<bend)
        for name,window in model_defs.items():
            train=t_index<bstart
            if window is not None:
                train &= t_index>=max(200,bstart-window)
            model=v4._logistic()
            model.fit(x[train].reshape(-1,x.shape[-1]),y[train].reshape(-1))
            p=model.predict_proba(x[test].reshape(-1,x.shape[-1]))[:,1]
            raw[name][t_index[test]]=p.reshape(-1,v4.RED_COUNT)

    ensemble=np.full((n,v4.RED_COUNT),np.nan)
    names=list(model_defs)
    hit_cumsum={}
    for name in names:
        hits=np.zeros(n,dtype=float)
        for u in range(MODEL_START,n):
            hits[u]=v4._topk_hits(raw[name][u],occ[u],10)
        hit_cumsum[name]=np.concatenate([[0.0],np.cumsum(hits)])
    for t in range(MODEL_START+BLOCK,n):
        left=max(MODEL_START,t-BLOCK)
        perf=np.asarray([(hit_cumsum[name][t]-hit_cumsum[name][left])/max(1,t-left) for name in names])
        baseline=v4.RED_PICK*10/v4.RED_COUNT
        z=np.clip((perf-baseline)/0.08,-3.0,3.0)
        weights=np.exp(z-z.max()); weights/=weights.sum()
        ensemble[t]=sum(weights[i]*v4._rank_score(raw[name][t]) for i,name in enumerate(names))
    return ensemble,raw,occ


def _rank_matrix(pred):
    out=np.full_like(pred,np.nan,dtype=float)
    for t in range(len(pred)):
        if np.isfinite(pred[t]).all(): out[t]=v4._rank_score(pred[t])
    return out


def blend(base,aug,w):
    b,a=_rank_matrix(base),_rank_matrix(aug)
    out=(1-w)*b+w*a
    out[~(np.isfinite(b).all(axis=1)&np.isfinite(a).all(axis=1))]=np.nan
    return out


def metrics(pred,occ,seqs,lo,hi):
    idx=np.where((seqs>=lo)&(seqs<=hi))[0]
    vals=[]
    for t in idx:
        if not np.isfinite(pred[t]).all(): continue
        order=np.argsort(pred[t])[::-1]
        vals.append((int(occ[t,order[:6]].sum()),int(occ[t,order[:12]].sum())))
    a=np.asarray(vals,int)
    return {
        "draws":int(len(a)),
        "top6_mean":float(a[:,0].mean()),
        "top12_mean":float(a[:,1].mean()),
        "top12_4plus":int((a[:,1]>=4).sum()),
        "top12_5plus":int((a[:,1]>=5).sum()),
        "top12_6":int((a[:,1]>=6).sum()),
    }


def main():
    df=load_dataframe()
    seqs=df["seq"].to_numpy(int)
    base,_,occ=v4.walk_forward_red(df)
    aug,_,occ2=walk_forward_augmented(df)
    if not np.array_equal(occ,occ2): raise RuntimeError("occ不一致")
    configs={
        "base":base,
        "calendar":aug,
        "base75_calendar25":blend(base,aug,0.25),
        "base50_calendar50":blend(base,aug,0.50),
    }
    report={"experiment":"V4.6开奖日期/星期因果特征","live2_excluded_from_selection":True,"configs":{},"promotion":{}}
    for name,pred in configs.items():
        report["configs"][name]={seg:metrics(pred,occ,seqs,*rng) for seg,rng in SEGMENTS.items()}
    b=report["configs"]["base"]
    for name in ("calendar","base75_calendar25","base50_calendar50"):
        cur=report["configs"][name]
        checks={}
        all_nonworse=True; positive=False
        for seg in ("validation_1","validation_2","forward55"):
            d12=cur[seg]["top12_mean"]-b[seg]["top12_mean"]
            d6=cur[seg]["top6_mean"]-b[seg]["top6_mean"]
            d4=cur[seg]["top12_4plus"]-b[seg]["top12_4plus"]
            d5=cur[seg]["top12_5plus"]-b[seg]["top12_5plus"]
            nonworse=d12>=-1e-12 and d5>=0
            checks[seg]={"top12_delta":float(d12),"top6_delta":float(d6),"top12_4plus_delta":int(d4),"top12_5plus_delta":int(d5),"nonworse":bool(nonworse)}
            all_nonworse &= nonworse
            positive |= d12>1e-12 or d4>0 or d5>0
        report["promotion"][name]={"pass":bool(all_nonworse and positive),"checks":checks}
    out=ROOT/"backtests"/"v4_6"/"calendar_features.json"
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__=="__main__":
    main()
