# 朝夕图标系统（E8.7 审计定稿）

## 网格规格

- 画布：24 × 24，统一 `viewBox="0 0 24 24"`
- 描边：`stroke-width="1.7"`，`stroke-linecap="round"`，`stroke-linejoin="round"`
- 颜色：一律 `stroke="currentColor"`（或 `fill="currentColor"` 的实心变体），不内嵌色值，随 token 着色
- 视觉重心：几何居中，关键笔画落在偶数像素上

## 状态

- 44 个文件（43 枚单图标 + `sprite.svg` 合并精灵），E8.7 已逐一审计：网格、描边、端点全部一致，无 V6 遗留风格混入。
- 新增图标必须遵守上方规格；接入 Compose 时由 `ZxIcon` 按 sprite 引用，禁止散引 PNG。

## 清单

导航：menu / back / chevron-right / settings · 会话：send / stop / mic / plus / copy / quote / regen / flag / locate / dots · 功能：persona（user/people/group）/ diary / tasks / tools / files / file / reader / usage / quota / palette · 媒体：camera / photo / video / voice / transcribe / sparkle · 状态：check / alert / info / clock / pin / edit / refresh / bulb / trash / location。
