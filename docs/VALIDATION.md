# 本地构建与调试参考

按问题实际需要选择构建、单元测试和设备调试，直接定位并修复。

开发者按当前问题选择必要的调试方式，例如：

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleOptimized
```

需要排查 Android 设备行为时，可运行对应测试。发布安装包应正常构建、使用有效签名并保持版本递增。
