from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


history = _load("ssq_history_cli", ROOT / "tools" / "load_history.py")
final_model = _load("ssq_final_cli", ROOT / "models" / "v4_5_2" / "predictor.py")
append_tool = _load("ssq_append_cli", ROOT / "tools" / "append_draw.py")


def cmd_verify(_: argparse.Namespace) -> None:
    rows = history.validate_current_state()
    manifest = history.load_manifest()
    print(json.dumps({
        "status": "ok",
        "draws": len(rows),
        "last_seq": int(rows[-1]["seq"]),
        "last_issue": int(manifest["current_last_issue"]),
        "cutoff": manifest["current_cutoff"],
    }, ensure_ascii=False, indent=2))


def cmd_predict(args: argparse.Namespace) -> None:
    history.validate_current_state()
    df = final_model.load_current_dataframe()
    result = final_model.predict_next(df)
    out = Path(args.out)
    if not out.is_absolute():
        out = ROOT / out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


def cmd_rebuild(args: argparse.Namespace) -> None:
    history.validate_current_state()
    out = Path(args.out)
    if not out.is_absolute():
        out = ROOT / out
    digest = history.write_current_csv(out)
    print(json.dumps({"out": str(out), "sha256": digest}, ensure_ascii=False, indent=2))


def cmd_append(args: argparse.Namespace) -> None:
    result = append_tool.append_draw(
        issue=args.issue,
        draw_date=args.date,
        reds=args.red,
        blue=args.blue,
        verified_against=args.verified_against,
    )
    history_reloaded = _load("ssq_history_cli_after_append", ROOT / "tools" / "load_history.py")
    history_reloaded.validate_current_state()
    print(json.dumps(result, ensure_ascii=False, indent=2))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="双色球历史数据与V4.5.2最终研究版")
    sub = parser.add_subparsers(dest="command", required=True)

    verify = sub.add_parser("verify", help="校验当前数据、哈希与清单")
    verify.set_defaults(func=cmd_verify)

    predict = sub.add_parser("predict", help="用当前冻结主版本生成下一期研究输出")
    predict.add_argument("--out", default="predictions/latest.json")
    predict.set_defaults(func=cmd_predict)

    rebuild = sub.add_parser("rebuild", help="由冻结基线+当前增量重建完整CSV")
    rebuild.add_argument("--out", default="data/history_current.csv")
    rebuild.set_defaults(func=cmd_rebuild)

    append = sub.add_parser("append", help="追加一期已核实开奖并同步数据清单")
    append.add_argument("--issue", type=int, required=True)
    append.add_argument("--date", required=True)
    append.add_argument("--red", type=int, nargs=6, required=True)
    append.add_argument("--blue", type=int, required=True)
    append.add_argument("--verified-against", action="append", default=[])
    append.set_defaults(func=cmd_append)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
