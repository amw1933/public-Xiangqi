# TCHESS V1.9.1 深色棋盘修复版 (Dark Board Fix Edition)

> **中文** | [English](#english)

This project is based on [public-Xiangqi](https://github.com/sojourners/public-Xiangqi) (TCHESS V1.9) with deep modifications, focusing on fixing **unstable board recognition on dark themes (e.g. JJ Chess)**. It also improves linking, recognition and engine features.

本项目基于 [public-Xiangqi](https://github.com/sojourners/public-Xiangqi)（TCHESS V1.9）源码深度修改，重点解决 **JJ象棋 等深色棋盘识别不稳定** 的问题，并完善了连线、识别、引擎等多项功能。

---

# 中文说明

## 主要功能

+ 加载 UCI / UCCI 协议引擎（皮卡鱼、小虫、旋风等）
+ 人机对弈、引擎对战、分析模式
+ 棋谱管理（保存 / 打开 / 编辑 / 分支）
+ 图形连线（JJ象棋、QQ象棋、天天象棋等，支持前后台模式）
+ 开局库（内置 / 自定义，支持 pfBook）
+ 深色棋盘识别（VinYolo5 模型 + YOLOv11 双模型自动切换）

## 本次修改内容

### 1. 深色棋盘识别（核心修复）

针对 JJ象棋 深色棋盘（雷电模拟器 / 手机端）识别率低的问题：

- 接入 VinXiangQi 训练的 YOLOv5 模型（`yolo5-vin.onnx`），主模型（YOLOv11）识别失败时自动切换
- 深色棋盘专用置信度阈值：棋盘 0.5 / 棋子 0.5，重试 0.4，补棋 0.35
- **相位（红黑方向）修复**：开局红先判断不再依赖 FEN 精确匹配，深色棋盘识别偶发漏子不再导致红黑翻转错误
- **多棋盘棋子分组**：同一画面出现多个棋盘时，棋子按所属棋盘分组填充，避免混入其它棋盘棋子

### 2. 中局 / 残局少子补棋

- 棋子数容错放宽（比引擎局面少 2 子以内可接受）
- 新增**引擎局面补棋**：识别少子时，模型补棋失败后直接用引擎局面补缺失棋子，残局漏识别不再卡住不走棋

### 3. 截图与窗口修复

- 修复后台模式截图 DPI 缩放导致的裁剪区域错位 / 黑图（统一"全窗口截图 + 统一裁剪"）
- 窗口 DPI / 缩放坐标处理（DwmapiExtra）

### 4. 连线设置与稳定性

- 新增连线方案（LinkScheme）：按窗口标题 / 类名自动匹配游戏窗口
- 连续识别失败自动重新定位棋盘并初始化局面
- 新局面智能确认：能明确配对成一步合法棋时立即接受，不明确时连续两帧相同才接受

### 5. 引擎路径自动回退

- 配置的引擎绝对路径失效时（软件移动到其它目录），自动从程序目录下的 `Windows/` 查找皮卡鱼引擎

## 使用说明

详细使用说明请参考 [MANUAL.md](MANUAL.md)。

下载最新版本请访问 [Releases](https://github.com/amw1933/public-Xiangqi/releases)。

### 快速开始

1. 解压 zip 到任意文件夹（建议路径不要带空格）
2. 运行 `tchess.exe`
3. 点击"引擎"加载皮卡鱼引擎（默认已配置 `Windows/pikafish-bmi2.exe`）
4. 点击"连线"，按提示点选游戏窗口（JJ象棋 深色棋盘）
5. 自动识别棋盘并开始对弈

## 参考项目

本项目深度参考并引用了以下开源项目，感谢原作者：

+ [public-Xiangqi (TCHESS)](https://github.com/sojourners/public-Xiangqi) — 上游象棋界面程序，本项目的代码基础
+ [VinXiangQi](https://github.com/Vincentzyx/VinXiangQi) — YOLOv5 深色棋盘识别模型（`yolo5-vin.onnx`）的训练来源
+ [Pikafish](https://github.com/official-pikafish/Pikafish) — 内置象棋引擎（`pikafish-*.exe` + `pikafish.nnue`）

### 第三方依赖库

+ [ONNX Runtime](https://github.com/microsoft/onnxruntime) — 深度学习推理引擎（`onnxruntime 1.19.2`）
+ [JavaFX](https://openjfx.io/) — 图形界面框架（`23.0.1`）
+ [JNA](https://github.com/java-native-access/jna) — Windows API 调用（`jna-platform 5.15.0`）
+ [JNativeHook](https://github.com/kwhat/jnativehook) — 全局键盘鼠标钩子（`2.1.0`）
+ [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) — 开局库数据库访问（`3.45.2.0`）

## 运行环境和依赖

+ JDK 21
+ JavaFX 23（已随包发布）

## 交流反馈

欢迎提交 Issue / PR 反馈问题。

## 声明

本项目基于 [GPLv3](LICENSE) 协议开源。你可以自由下载、使用、复制修改，但需遵守开源协议内容，禁止未经授权用于商业用途！

---

# <a name="english"></a>English

## Features

+ Load UCI / UCCI protocol engines (Pikafish, Xiaochong, Xuanfeng, etc.)
+ Human vs engine, engine vs engine, analysis mode
+ Game record management (save / open / edit / branches)
+ Screen linking (JJ Chess, QQ Chess, Tian Tian Chess, etc., foreground & background modes)
+ Opening books (built-in / custom, pfBook support)
+ Dark board recognition (VinYolo5 model + YOLOv11 dual-model auto-switch)

## Changes in This Version

### 1. Dark Board Recognition (Core Fix)

Addresses low recognition rate on dark-themed boards (JJ Chess on emulator / mobile):

- Integrated YOLOv5 model trained by VinXiangQi (`yolo5-vin.onnx`), auto-switches when the main model (YOLOv11) fails
- Dark-board confidence thresholds: board 0.5 / piece 0.5, retry 0.4, recovery 0.35
- **Orientation (red/black direction) fix**: first-move detection no longer depends on exact FEN match, so occasional recognition errors no longer cause wrong red/black flipping
- **Multi-board piece grouping**: when multiple boards appear on screen, pieces are grouped by their own board to avoid mixing

### 2. Midgame / Endgame Piece Recovery

- Loosened piece-count tolerance (up to 2 pieces fewer than engine board accepted)
- Added **engine-board recovery**: when pieces are missing, uses the engine board to fill missing pieces after model recovery fails, so endgame recognition no longer stalls

### 3. Screenshot & Window Fixes

- Fixed background-mode screenshot DPI scaling causing cropped region misalignment / black images (unified full-window capture + unified crop)
- Window DPI / scaled coordinate handling (DwmapiExtra)

### 4. Linking Settings & Stability

- Added linking schemes (LinkScheme): auto-match game windows by title / class
- Auto re-locate board and re-initialize position after consecutive recognition failures
- Smart confirmation for new positions: accept immediately when a clear legal move is matched, otherwise require two identical frames

### 5. Engine Path Fallback

- If the configured engine absolute path becomes invalid (e.g. software moved to another folder), automatically searches for Pikafish in the program's `Windows/` directory

## Usage

See [MANUAL.md](MANUAL.md) for details.

Download the latest release from [Releases](https://github.com/amw1933/public-Xiangqi/releases).

### Quick Start

1. Extract the zip to any folder (avoid spaces in the path)
2. Run `tchess.exe`
3. Click "Engine" to load Pikafish (default: `Windows/pikafish-bmi2.exe`)
4. Click "Link" and select the game window (JJ Chess dark board)
5. The board is recognized automatically and play begins

## References

This project deeply references the following open-source projects. Thanks to the original authors:

+ [public-Xiangqi (TCHESS)](https://github.com/sojourners/public-Xiangqi) — upstream chess GUI, code base of this project
+ [VinXiangQi](https://github.com/Vincentzyx/VinXiangQi) — source of the YOLOv5 dark-board model (`yolo5-vin.onnx`)
+ [Pikafish](https://github.com/official-pikafish/Pikafish) — bundled chess engine (`pikafish-*.exe` + `pikafish.nnue`)

### Third-party Dependencies

+ [ONNX Runtime](https://github.com/microsoft/onnxruntime) — deep learning inference engine (`onnxruntime 1.19.2`)
+ [JavaFX](https://openjfx.io/) — GUI framework (`23.0.1`)
+ [JNA](https://github.com/java-native-access/jna) — Windows API access (`jna-platform 5.15.0`)
+ [JNativeHook](https://github.com/kwhat/jnativehook) — global keyboard/mouse hooks (`2.1.0`)
+ [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) — opening book database access (`3.45.2.0`)

## Requirements

+ JDK 21
+ JavaFX 23 (bundled)

## Feedback

Issues and PRs are welcome.

## License

This project is open source under [GPLv3](LICENSE). You are free to download, use, copy and modify it under the license terms. Commercial use without authorization is prohibited!
