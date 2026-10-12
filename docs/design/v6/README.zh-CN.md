# 神言神语 V6 · 素材补齐与原生同步

V6 沿用 V5 的 38 屏布局、暖纸 / 烟熏玻璃 / 哑光黑、五级材质与响应式规则。它是本次素材包版本；PR #654 的全页面视觉验收仍独立进行。

## 这次补齐了什么

- 文件列表新增音频、视频、压缩包三类图标：40dp 矢量画布、圆角线条，浅色 / 深色共六份 Android XML 与六份同源 SVG；哑光复用深色图标。文件列表已按真实扩展名使用这些资源。
- 将原生组件迁移产生的 4 份新增源码和 8 份修改源码全部同步到 `compose-v6/`，同时保留当前业务适配层及 Feather 矢量图标源。`compose-v5/` 继续作为历史参考。
- 修正浅色 L4 为暖纸色 88% 不透明度，页面 push/pop 为 360ms；哑光或全实色表面不执行背景模糊，也不增加玻璃嵌套层数。
- 补齐 App 实际使用的字体、文件图标和文案资源。网页设计另生成覆盖设计包中文字符的五档字体子集，避免离线打开时依赖远程字体。动态业务正文仍使用宿主系统字体回退。
- 原 V5 的 20 组 P0/P1 设计样例与状态矩阵完整保留；设计样例不等同于这些能力已经在 App 全部接入。

## 查看顺序

1. `app.html`：完整 38 屏设计场景；继承的 CSS / JS 文件名仍带 V5，避免破坏已有链接，包内当前版本标记为 V6。
2. `assets/v6/file-icons-preview.svg`：新增图标与三主题、五级材质的可编辑预览。
3. `compose-v6/`：本次同步的原生源码；`android-v6/res/`：实际资源。
4. `semantic-design-tokens.json`：本次同步后的令牌；`V6-VERIFICATION.json`：当前验证记录。

旧 V3/V4/V5 验证记录和预览均为历史资料，不能替代 V6 的真实构建、测试和设备验收结果。

## 本地编译与再次同步

按仓库 `docs/DEVELOPMENT.md` 执行已经验证的安装路径：

先完成主线合并并固定源码，再启动构建；构建运行期间不要更换源码。若出现旧类构造签名或重复 DEX，而源码本身没有重复定义，先执行 `:app:clean` 再重跑，避免混用不同源码时点的增量产物。

```sh
bash tools/dev/ai-toolchain.sh plan --profile build
# 只下载 main 最新成功「开发工具链」运行中缺失的组件，解压到同一个目录。
bash tools/dev/ai-toolchain.sh bootstrap --profile build --artifacts-dir /绝对路径/组件目录
bash tools/dev/ai-toolchain.sh gradle --max-workers=2 :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleOptimized
```

素材同步只使用当前生产源，不手工维护第二份原生绘制：

```sh
python3 docs/design/v6/generate-file-icons.py
python3 docs/design/v6/create-preview.py
PYTHONPATH="$PWD/.gradle/runtime-cache/ui-fonttools-python" python3 docs/design/v6/sync-design-package.py \
  --source /绝对路径/已解压V5包 --output /绝对路径/新V6目录
```

打包前更新 `docs/design/v6/verification.json`。同步脚本保留 V5 全部内容，复制实际源码和资源，生成字体授权信息、源码快照、完整 SHA-256 清单和 ZIP；输出目录必须是新目录。

## 验证边界

原生源码已通过仓库真实 Gradle 构建和单元测试；具体结果见当前验证记录。此包没有独立 Gradle 模块，业务适配层需要同版本宿主、资源和业务回调，不能当作零依赖库。

新增矢量资源已实际渲染检查。完整 38 屏多主题、字体缩放、IME、交互和动画尚未完成 Android 16/17 逐屏验收。本机没有 KVM / 已连接设备，设备测试 APK 的编译通过不能称为设备测试通过。

Figma 本次实际返回 Starter 调用额度已满；未修改任何画板或发布设计系统。离线源码与 SVG 是本次可交付的设计资产。

本轮没有新增业务功能或改变功能归属，无需更新功能树；文件图标分类只调整已有文件列表的视觉呈现。
