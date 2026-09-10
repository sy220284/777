from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd


def _numbers(text: str) -> set[int]:
    return {int(x) for x in str(text).split()}


def main() -> None:
    p = argparse.ArgumentParser(description="V4前12时间信息打乱检验")
    p.add_argument("--backtest", required=True, help="含rank_top12和actual_red的回测CSV")
    p.add_argument("--permutations", type=int, default=2000)
    p.add_argument("--seed", type=int, default=20260910)
    p.add_argument("--out", required=True)
    args = p.parse_args()

    df = pd.read_csv(args.backtest)
    pred = [_numbers(x) for x in df["rank_top12"]]
    actual = [_numbers(x) for x in df["actual_red"]]

    def metrics(targets):
        h = np.array([len(a & b) for a, b in zip(pred, targets)])
        return np.array([h.mean(), (h >= 4).mean(), (h >= 5).mean(), (h >= 6).mean()])

    real = metrics(actual)
    rng = np.random.default_rng(args.seed)
    null = np.empty((args.permutations, 4), dtype=float)
    idx = np.arange(len(actual))
    for i in range(args.permutations):
        perm = rng.permutation(idx)
        null[i] = metrics([actual[j] for j in perm])

    names = ("top12_mean", "top12_4plus", "top12_5plus", "top12_6")
    out = {
        "permutations": args.permutations,
        "seed": args.seed,
        "interpretation": "若真实顺序没有显著超过时间打乱结果，则现有候选池优势不能归因于可利用的时间信息。",
        "metrics": {
            name: {
                "real": float(real[j]),
                "shuffle_mean": float(null[:, j].mean()),
                "shuffle_sd": float(null[:, j].std()),
                "p_ge_real": float((null[:, j] >= real[j]).mean()),
                "shuffle_q95": float(np.quantile(null[:, j], 0.95)),
            }
            for j, name in enumerate(names)
        },
    }
    Path(args.out).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
