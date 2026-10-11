# 手机端着色器与视觉效果
按需读取 upstream 中色彩、光照、噪声和图形技法资料。
交付包含着色器源码的完整 HTML，使用设备浏览器支持的 WebGL/WebGL2 和触摸交互；优先兼容 WebGL1，不假定设备支持 WebGPU。
先查询实际 WebGL 上下文及扩展；编译后检查 getShaderParameter(COMPILE_STATUS) 与 getProgramParameter(LINK_STATUS)，错误信息显示在页面中。
使用 777 HTML 文件预览运行，检查手机分辨率和帧率，提供适配分辨率与暂停控制。无可用图形上下文时页面显示真实原因。
不使用桌面 Unity、Unreal、独立 GLSL 编译器或原生 GPU 构建链。
