# #889 功能与多代理剩余验收：集中核对

按同一提交集中补充 82 个有对应 `@Test` 方法的局部测试映射。功能树共 456 项；目前 271 项有自动化**局部**证据索引，另外 185 项在 `docs/ACCEPTANCE-OPEN-GAPS.json` 中逐项登记源码线索、未验收原因及验证路径，避免漏项。全部功能的最终签收仍为 0。

U01–U10 保留原有完整验收要求和未签收状态。原始 84 项 Codex／Claude／777 三方同题差分的原文未入仓，尚须恢复原始题目并逐项完成，严禁据局部单测推算三方差分通过率。

本批仅登记生产代码已有的测试方法引用，不伪造测试、运行结果或生产功能。完整 Kotlin、Android 16/17、四组合矩阵的最新提交执行结果另行核对。

以后只需批量修改 `docs/ACCEPTANCE-MATRIX.json`，运行 `python3 .github/scripts/check-acceptance-matrix.py --sync-open-gaps` 同步剩余缺口，再执行 `python3 .github/scripts/check-acceptance-matrix.py` 校验一次并统一提交。验收门禁会强制已映射和未映射覆盖全部功能树叶节点。
