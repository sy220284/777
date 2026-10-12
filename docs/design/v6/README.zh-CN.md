# 神言神语 V6 · 素材补齐与原生同步

V6 沿用 V5 的 38 屏布局、暖纸 / 烟熏玻璃 / 哑光黑、五级材质与响应式规则。它是本次素材包版本；PR #654 的全页面视觉验收仍独立进行。

## 这次补齐了什么

- 文件列表新增音频、视频、压缩包三类图标：40dp 矢量画布、圆角线条，浅色 / 深色共六份 Android XML 与六份同源 SVG；哑光复用深色图标。文件列表已按真实扩展名使用这些资源。
- 将当前 14 份原生绘制源码（相对 V5 为 6 新增、8 修改）同步到 `compose-v6/`，同时保留业务适配层、触觉工具与 Feather 矢量图标源。`compose-v5/` 继续作为历史参考。
- 修正浅色 L4 为暖纸色 88% 不透明度，页面 push/pop 为 360ms；哑光或全实色表面不执行背景模糊，也不增加玻璃嵌套层数。
- 补齐 App 实际使用的字体、文件图标和文案资源。网页设计另生成覆盖设计包中文字符的五档字体子集，避免离线打开时依赖远程字体。动态业务正文仍使用宿主系统字体回退。
- 原 V5 的 20 组 P0/P1 设计样例与状态矩阵完整保留；设计样例不等同于这些能力已经在 App 全部接入。

## 查看顺序

1. `app.html`：完整 38 屏设计场景；继承的 CSS / JS 文件名仍带 V5，避免破坏已有链接，包内当前版本标记为 V6。
2. `assets/v6/file-icons-preview.svg`：新增图标与三主题、五级材质的可编辑预览。
3. `compose-v6/`：本次同步的原生源码；`android-v6/res/`：实际资源。
4. `semantic-design-tokens.json`：本次同步后的令牌；`V6-VERIFICATION.json`：当前验证记录。

旧 V3/V4/V5 验证记录和预览均为历史资料，不能替代 V6 的真实构建、测试和设备验收结果。

本轮替换进度见 `V6-UI-RETIREMENT.zh-CN.md`：201 处旧组件调用已清零，旧绘制源码退出；字体偏好、壁纸分析和业务回调保留。顶栏副标题落在按钮行下方，交互标签满足 48dp 宽高，工具详情操作拥有独立测试定位。完整 38 屏视觉验收仍独立进行。

## 分类行和字体尺寸的正式支持边界（本次调整）

- **最小支持宽度：393dp**，Android Manifest 声明 `requiresSmallestWidthDp=393`；360dp 已退出适配及设备验收矩阵。该 Manifest 属性主要用于设备兼容性筛选，**不会在运行时禁止小窗口或强制限制布局**。
- **渲染字体倍率：最高 1.5 倍**。应用内阅读滑块为 0.9～1.5 倍；与 Android 系统字体缩放组合后，Compose UI 的最终 `fontScale` 仍不超过 1.5。高于 1.5 的系统字体设置不承诺保持其原始倍率，1.8 倍视觉适配已退出支持。
- `SyCategoryRow` 保留 V5 材质、图标和整个列表行的操作语义。正常字号优先横排；在放大字体或实测空间不足时，数量改为标题和副标题下方独立一行，标题容器填满可用宽度并允许换行，不截短真实业务文案。

Android 16/17 回归范围相应改为 **393/412dp、1.3/1.5 倍**，检查顶栏及列表的显示边界、点击和互不重叠；另外单独校验系统与应用倍率叠加时的 1.5 上限。最终设备测试需以本次提交的新 CI 为准，不能沿用此前的测试结论。设计源以本仓库当前 Compose 为准；离线 V6 ZIP 尚未因本次修改重新导出，Figma Starter 额度已满，不能声称云端画板已同步。

## 本地编译与再次同步

按仓库 `docs/DEVELOPMENT.md` 执行已经验证的安装路径：

先完成主线合并并固定源码，再启动构建；构建运行期间不要更换源码。若出现旧类构造签名或重复 DEX，而源码本身没有重复定义，先执行 `:app:clean` 再重跑，避免混用不同源码时点的增量产物。

```sh
bash tools/dev/ai-toolchain.sh plan --profile build
# 只下载 main 最新成功「开发工具链」运行中缺失的组件，解压到同一个目录。
bash tools/dev/ai-toolchain.sh bootstrap --profile build --artifacts-dir /绝对路径/组件目录
bash tools/dev/ai-toolchain.sh gradle --no-configuration-cache --no-build-cache --max-workers=2 \
  -Pkotlin.incremental=false -Pksp.incremental=false \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleOptimized
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
