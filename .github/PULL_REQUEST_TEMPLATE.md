<!--
提交前请阅读 AGENTS.md 与 CONTRIBUTING.md。
PR 标题和描述使用中文。
-->

## 改动内容

<!-- 说明改了什么、为什么改。 -->

## 关联影响

<!--
至少检查真正相关的横向 / 纵向链路：
Chat / Work、主 / 子代理、Session、Memory、Automation、Web / Vision、
Token、UI、Runtime、协议、持久化、性能、安全。
-->

## 验证

<!-- 写实际执行过的验证，不写“应该没问题”。 -->

- [ ] 单元测试
- [ ] 官方 Harness conformance
- [ ] Lint
- [ ] optimized APK
- [ ] Android 16
- [ ] Android 17
- [ ] 专项验证（如适用）

补充说明：

<!-- 命令、设备、复现场景、关键结果。 -->

## Checklist

- [ ] 修改基于当前 main，或已经重新同步最新 main
- [ ] 没有通过提高架构 / 性能 / APK 预算绕过门禁
- [ ] 新增或修复没有破坏关联功能
- [ ] 用户可见文本使用字符串资源
- [ ] UI 复用现有 Design System
- [ ] 异步 / 后台 / Agent 逻辑考虑取消、超时、重试、恢复和幂等
- [ ] 日志可定位问题，但不记录不必要的敏感正文
- [ ] 需要文档同步的改动已更新当前文档
- [ ] 用户可见变化已更新 CHANGELOG
- [ ] UI 改动附必要截图 / 录屏
- [ ] 最终放行依据对应当前 main + 当前 PR head

## 截图 / 录屏

<!-- UI 改动时提供。 -->

## 备注

<!-- 已知边界、刻意不处理的范围、需要 reviewer 特别关注的风险。 -->
