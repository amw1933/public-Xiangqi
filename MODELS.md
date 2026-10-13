# 识别模型说明与来源

本分支在官方 TCHESS V1.9 基础上完成了以下改造：

1. **双识别**：主力模型 + 备援模型（识别失败时自动救场）；
2. **终局自动继续**：自动点击“再来一局 / 关闭X”等按钮（模板可扩充）；
3. **引擎掉线自愈**：引擎意外退出自动重启并续算。

所有识别模型**不加密、明文加载**，统一放在程序目录 `model\` 下。
仓库内含的模型文件如下（`src/main/resources/model/` 与发行包 `model\` 一致）：

## 一、模型清单

| 文件 | 大小 | SHA256（前16位） | 用途 | 来源 |
| --- | --- | --- | --- | --- |
| `yolov11-12m.onnx` | 12,338,549 | `eded3e0406ef553a` | **主力（优先加载）**：棋盘定位 + 棋子检测 | 社区整理增强版（见下） |
| `yolov11.onnx` | 10,575,683 | `099c4ef0cbfbd07f` | 兜底：官方内置原版模型 | 官方仓库自带 |
| `4_v6-0301.onnx` | 10,704,184 | `fbd895d40ab81b41` | 备援：棋盘四角关键点检测 | TheOne1006/chinese-chess-recognition |
| `nano_v3-0319.onnx` | 31,101,356 | `da66ba9809f15127` | 备援：90 交叉点棋子分类 | 同上 |

## 二、加载规则

- **棋子检测**：程序优先加载 `yolov11-12m.onnx`（存在即用）；
  没有该文件时自动回退 `yolov11.onnx`（官方原版）。两者都不加密，
  想换哪个都行，文件名保持不变即可。
- **双识别协作**：`yolo11` 为默认主力，负责全部识别；
  `pose`（4_v6-0301 + nano_v3-0319）平时待命，**只在 yolo11 识别失败的
  瞬间被自动拉来救场**；也可在“连线设置 → 识别模型”里手动切换全程使用。
  两个方向互为兜底：任一模型单帧失败，另一模型立刻接手。

## 三、来源与许可

### 4_v6-0301.onnx 与 nano_v3-0319.onnx

来自开源项目 **TheOne1006/chinese-chess-recognition**（MIT）：

- GitHub：https://github.com/TheOne1006/chinese-chess-recognition
- 在线演示与模型下载（Hugging Face）：
  https://huggingface.co/spaces/yolo12138/Chinese_Chess_Recognition
  - 角点模型：`onnx/pose/4_v6-0301.onnx`
  - 分类模型：`onnx/layout_recognition/nano_v3-0319.onnx`
- 两个文件均按**官方原始文件名**使用，未做任何改名或修改；
- 上游采用 RTMPose（MMPose）训练方案，数据集未公开。

### yolov11-12m.onnx（增强版 12M）

社区整理的 12M 量化版本，相对官方 10.5M 模型对深色棋盘等场景识别更稳。

```
SHA256 eded3e0406ef553a3c34bdfc34157ff120ae7b522bda6dc5a994918e2ba28490
SIZE   12338549
```

### 合规提示

- TCHESS 本体：GPLv3（见 LICENSE）；
- TheOne1006/chinese-chess-recognition：MIT（保留上述来源说明即可）。
