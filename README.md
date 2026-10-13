# TCHESS
TCHESS 是一款支持 uci 和 ucci 协议引擎的跨平台象棋界面程序，具有功能：
+ 加载引擎
+ 对弈
+ 分析
+ 棋谱
+ 连线
+ 开局库

使用说明请参考 [MANUAL.md](https://github.com/sojourners/public-Xiangqi/blob/master/MANUAL.md)，访问 [Release](https://github.com/sojourners/public-Xiangqi/releases) 下载最新版本。

# 运行环境和依赖
+ JDK 21  
+ JavaFx 23

# 交流反馈
Q群：1094058444

# 本分支改动（相对官方 V1.9）
+ 双识别：`yolov11-12m.onnx`（主力，优先加载）+ 官方 `yolov11.onnx`（兜底）；
  另有 `4_v6-0301.onnx` + `nano_v3-0319.onnx` 两阶段识别在主力失败时自动救场
+ 终局自动继续：自动点击“再来一局 / 关闭X”等按钮（模板库 buttonlib\ 可扩充）
+ 引擎掉线自愈：引擎意外退出自动重启并重新分析当前局面
+ 识别模型全部明文、不加密；模型来源与许可见 [MODELS.md](MODELS.md)

# 声明
本项目基于 [GPLv3](https://github.com/sojourners/public-Xiangqi/blob/master/LICENSE) 协议开源，你可以自由下载、使用、复制修改，但需遵守开源协议内容，禁止未经授权用于商业用途！

识别模型之一来自开源项目 [TheOne1006/chinese-chess-recognition](https://github.com/TheOne1006/chinese-chess-recognition)（MIT 协议），在此致谢。
