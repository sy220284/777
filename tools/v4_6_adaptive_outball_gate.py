from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
START = 750
WINDOW = 250
MIN_LOW_HISTORY = 30
SEGMENTS = {
    "validation_1": (2447, 2946),
    "validation_2": (2947, 3446),
    "forward55": (3447, 3501),
    "live2": (3502, 3503),
}
FIXED_PRIZE = {"一等奖": 0, "二等奖": 0, "三等奖": 3000, "四等奖": 200, "五等奖": 10, "六等奖": 5, "未中奖": 0}
PRIZE_ORDER = {"一等奖": 1, "二等奖": 2, "三等奖": 3, "四等奖": 4, "五等奖": 5, "六等奖": 6, "未中奖": 99}


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


base_exp = _load("v46_adapt_low", ROOT / "tools" / "v4_6_low_signal_outball.py")
poollock = base_exp.poollock
order_exp = base_exp.order_exp
v4 = base_exp.v4
v45 = base_exp.v45


def _best(a: str, b: str) -> str:
    return a if PRIZE_ORDER[a] <= PRIZE_ORDER[b] else b


def _strategy_stats(df, groups, red_occ, blue_base):
    actual_blue = df["blue"].to_numpy(int)
    rows = []
    for t in range(START, len(df)):
        a, b = groups[t]
        bo = np.argsort(blue_base[t])[::-1]
        b1, b2 = int(bo[0] + 1), int(bo[1] + 1)
        h1, h2 = int(red_occ[t, a].sum()), int(red_occ[t, b].sum())
        p1 = v4.prize_level(h1, b1 == actual_blue[t]); p2 = v4.prize_level(h2, b2 == actual_blue[t])
        rows.append({"t": t, "max_hit": max(h1,h2), "red4": int(max(h1,h2)>=4), "red5": int(max(h1,h2)>=5), "fixed": FIXED_PRIZE[p1] + FIXED_PRIZE[p2]})
    return pd.DataFrame(rows).set_index("t")


def _adaptive_mask(signal, base_stats, cand_stats):
    use = np.zeros(len(signal), dtype=bool)
    for t in range(START, len(signal)):
        if signal[t]:
            continue
        idx = [u for u in range(max(START, t-WINDOW), t) if not signal[u] and u in base_stats.index]
        if len(idx) < MIN_LOW_HISTORY:
            continue
        b = base_stats.loc[idx]; c = cand_stats.loc[idx]
        use[t] = bool(
            c.max_hit.mean() >= b.max_hit.mean() - 1e-12
            and c.red4.sum() >= b.red4.sum()
            and c.red5.sum() >= b.red5.sum()
            and c.fixed.sum() >= b.fixed.sum()
        )
    return use


def evaluate(df, signal, use_cand, base_low, cand_low, high, red_occ, blue_base, blue_high):
    actual = df["blue"].to_numpy(int); rows=[]
    for t in range(START,len(df)):
        if signal[t]:
            a,b=high[t]; bs=blue_high[t]; source="V4.5"
        else:
            a,b=(cand_low if use_cand[t] else base_low)[t]; bs=blue_base[t]; source="OUTBALL" if use_cand[t] else "V4.3"
        bo=np.argsort(bs)[::-1]; b1,b2=int(bo[0]+1),int(bo[1]+1); h1,h2=int(red_occ[t,a].sum()),int(red_occ[t,b].sum()); p1=v4.prize_level(h1,b1==actual[t]); p2=v4.prize_level(h2,b2==actual[t])
        rows.append({"seq":int(df.iloc[t]["seq"]),"signal_active":bool(signal[t]),"use_outball":bool(use_cand[t]),"source":source,"max_red_hit":max(h1,h2),"prize1":p1,"prize2":p2,"best_prize":_best(p1,p2)})
    return pd.DataFrame(rows)


def summary(result,lo,hi):
    p=result[(result.seq>=lo)&(result.seq<=hi)]; tickets=pd.concat([p.prize1,p.prize2],ignore_index=True)
    return {"draws":int(len(p)),"low_signal_draws":int((~p.signal_active).sum()),"outball_active_draws":int(p.use_outball.sum()),"outball_active_rate":float(p.use_outball.mean()),"max_red_hit_mean":float(p.max_red_hit.mean()),"red_4plus":int((p.max_red_hit>=4).sum()),"red_5plus":int((p.max_red_hit>=5).sum()),"fixed_return":int(sum(FIXED_PRIZE[x] for x in tickets)),"winning_draw_rate":float((p.best_prize!="未中奖").mean())}


def main():
    df=order_exp.load_dataframe(); order=order_exp.load_order_matrix(df); base_pred,_,red_occ=v4.walk_forward_red(df); outball_pred,_,occ2=order_exp.walk_forward_augmented(df,order)
    if not np.array_equal(red_occ,occ2): raise RuntimeError("红球发生矩阵不一致")
    signal=base_exp._signal_mask(base_pred,red_occ); base_low,_,_=v45._baseline_red_groups(base_pred,red_occ,START); cand_pred=poollock.lock_pool_rerank(base_pred,outball_pred,1.0); cand_low,_,_=v45._baseline_red_groups(cand_pred,red_occ,START); high,_,_=v45.build_red_portfolio(base_pred,red_occ,START)
    blue_base,_=v4.walk_forward_blue(df); blue_models=v45.build_blue_models(df); actual=df["blue"].to_numpy(int); blue_high,_=v45.fuse_blue_predictions(blue_models,high,red_occ,actual,START)
    bstats=_strategy_stats(df,base_low,red_occ,blue_base); cstats=_strategy_stats(df,cand_low,red_occ,blue_base); use=_adaptive_mask(signal,bstats,cstats)
    base_use=np.zeros(len(signal),dtype=bool); base=evaluate(df,signal,base_use,base_low,cand_low,high,red_occ,blue_base,blue_high); adaptive=evaluate(df,signal,use,base_low,cand_low,high,red_occ,blue_base,blue_high)
    report={"experiment":"V4.5.1低信号出球顺序因果表现门","window":WINDOW,"minimum_low_signal_history":MIN_LOW_HISTORY,"gate":"过去低信号期候选平均命中、4红、5红、固定回报四项均不弱于V4.3才允许下一期接管","configs":{},"promotion":{}}
    for name,res in (("base",base),("adaptive",adaptive)): report["configs"][name]={seg:summary(res,*rng) for seg,rng in SEGMENTS.items()}
    ok=True; pos=0; checks={}
    for seg in ("validation_1","validation_2","forward55"):
        b=report["configs"]["base"][seg]; c=report["configs"]["adaptive"][seg]; dm=c["max_red_hit_mean"]-b["max_red_hit_mean"]; d4=c["red_4plus"]-b["red_4plus"]; d5=c["red_5plus"]-b["red_5plus"]; dr=c["fixed_return"]-b["fixed_return"]; nw=dm>=-1e-12 and d5>=0 and dr>=0; imp=dm>1e-12 or d4>0 or d5>0 or dr>0; ok &= nw; pos += int(imp); checks[seg]={"active_draws":int(c["outball_active_draws"]),"max_red_hit_mean_delta":float(dm),"red_4plus_delta":int(d4),"red_5plus_delta":int(d5),"fixed_return_delta":int(dr),"nonworse":bool(nw),"improved":bool(imp)}
    report["promotion"]={"pass":bool(ok and pos>=1),"positive_segments":int(pos),"checks":checks}
    out=ROOT/"backtests"/"v4_6"/"adaptive_outball_gate.json"; out.parent.mkdir(parents=True,exist_ok=True); out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8"); print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__ == "__main__": main()
