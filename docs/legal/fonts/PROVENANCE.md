# 流体玻璃字体来源

字体是用户指定设计的产品资源，不是开发工具链依赖；APK 内嵌，不在手机运行时下载 CDN 字体。

- Outfit：官方 `Outfitio/Outfit-Fonts`，提交 `902773808eb372f70fb34e8946dd1ffe604efc79`，原始 `fonts/ttf/Outfit-{Regular,Medium,SemiBold,Bold,ExtraBold}.ttf`，未改字体字节。授权全文见 Outfit-OFL.txt。
- JetBrains Mono：官方 `JetBrains/JetBrainsMono`，提交 `19371302b95d218af43299bce79ddbddd0bc364d`，原始 `fonts/ttf/JetBrainsMono-{Regular,Italic}.ttf`，未改字体字节。授权全文见 JetBrainsMono-OFL.txt。
- 中文界面保留已打包的 Noto Sans SC 子集；动态正文走 Android CJK 回退，中文衬线使用补充源码所声明的 `FontFamily.Serif`。
