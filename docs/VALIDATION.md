# 本地构建与调试参考

本仓库已取消合并验收、发布验收、验收矩阵和强制提交验收依据的机制。

开发者按当前问题选择必要的调试方式，例如：

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleOptimized
```

需要排查 Android 设备行为时可自行运行相关测试。命令及其结果只帮助定位问题，不作为 PR、发布或工作完成的前置资格。实际发布仍要求安装包正常构建、使用有效签名并保持版本递增。
