# 人物预设立绘

本目录用于存放人物预设的内置形象图。

约束：

- 不使用游戏官方立绘、宣传图、截图或直接下载的网络素材。
- 资源必须为项目自行制作并确认可用于项目分发的图片。
- 不保留游戏 Logo、水印、签名、UI、宣传文字。
- 构图优先半身或 3/4 身，人物主体清晰，适合人物图集卡片和聊天头像裁切。
- 建议尺寸 1024×1536 或同等 2:3 竖图；优先 WebP，单张尽量控制在 1 MB 以内。
- 文件名必须与 PersonaPreset 稳定 ID 对应，例如：
  - genshin-kamisato-ayaka.webp
  - hsr-kafka.webp
  - wwm-zhao-er.webp
  - love-deepspace-li-shen.webp

接入时在 PersonaPreset.artwork 中声明：

```kotlin
artwork = PersonaPresetArtwork(
    assetPath = "persona-presets/genshin-kamisato-ayaka.webp",
)
```

安装人物预设时，应用会把该资产复制到现有的 App 私有人物立绘目录并写入 portraitPath；之后聊天头像、群聊头像、侧边栏会话头像和人物图集继续共用现有链路。
