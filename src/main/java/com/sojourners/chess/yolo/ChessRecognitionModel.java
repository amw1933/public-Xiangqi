package com.sojourners.chess.yolo;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtSession;
import com.sojourners.chess.config.Properties;
import com.sojourners.chess.util.PathUtils;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * 基于 TheOne1006/chinese-chess-recognition 的两阶段识别模型。
 *
 * <p>模型文件：{@code model/4_v6-0301.onnx}、{@code model/nano_v3-0319.onnx}
 * （文件名与 TheOne1006/chinese-chess-recognition 开源仓库保持一致）</p>
 * <ol>
 *     <li>4_v6-0301.onnx（角点）：输入 256x256 RGB 图像，输出 4 个棋盘角点的 simcc 一维热力图。</li>
 *     <li>nano_v3-0319.onnx（识别）：输入棋盘区域图像，输出 90 个交叉点、每点 16 类的分类结果。</li>
 * </ol>
 *
 * 对外接口与 {@link OnnxModel} / {@link Yolo11Model} 完全一致，可直接替换使用。
 */
public class ChessRecognitionModel extends OnnxModel {

    /** pose 模型输入尺寸 */
    private static final int POSE_SIZE = 256;
    /** simcc 一维热力图长度（= 2 * POSE_SIZE） */
    private static final int SIMCC_LEN = 512;
    /** reg 模型输入尺寸（透视变换后的棋盘图） */
    private static final int REG_SIZE = 256;
    /** 棋盘交叉点数量：10 行 x 9 列 */
    private static final int NUM_POSITIONS = 90;
    /** reg 模型每个交叉点的分类数 */
    private static final int NUM_CLASSES = 16;
    /** 关键点数量：棋盘四个角点 */
    private static final int NUM_KEYPOINTS = 4;

    /** pose 模型会话 */
    private OrtSession poseSession;

    public ChessRecognitionModel() {
        // 父类构造会通过 getModelPath() 加载 nano_v3-0319.onnx 到 session 字段
        super();
        try {
            OrtSession.SessionOptions opt = new OrtSession.SessionOptions();
            opt.setIntraOpNumThreads(Properties.getInstance().getLinkThreadNum());
            String path = PathUtils.getJarPath() + "model/4_v6-0301.onnx";
            poseSession = env.createSession(path, opt);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getModelPath() {
        return "model/nano_v3-0319.onnx";
    }

    /**
     * 寻找棋盘范围，用于后续连线识别。
     *
     * @param img 完整截图
     * @return 棋盘区域（已包含 PADDING 边距），识别失败返回 null
     */
    @Override
    public java.awt.Rectangle findBoardPosition(BufferedImage img) {
        try {
            if (img == null) {
                return null;
            }

            float[][] corners = detectCorners(img);
            if (corners == null) {
                return null;
            }

            double minX = corners[0][0], maxX = corners[0][0];
            double minY = corners[0][1], maxY = corners[0][1];
            for (int i = 1; i < NUM_KEYPOINTS; i++) {
                minX = Math.min(minX, corners[i][0]);
                maxX = Math.max(maxX, corners[i][0]);
                minY = Math.min(minY, corners[i][1]);
                maxY = Math.max(maxY, corners[i][1]);
            }

            double gridWidth = maxX - minX;
            double gridHeight = maxY - minY;
            if (gridWidth <= 0 || gridHeight <= 0) {
                return null;
            }

            // 棋盘网格宽高比约为 8:9，异常比例说明关键点检测失败
            double ratio = gridWidth / gridHeight;
            if (ratio < 0.5d || ratio > 1.6d) {
                return null;
            }

            double pieceWidth = gridWidth / 8d;
            double pieceHeight = gridHeight / 9d;

            int x = (int) Math.round(minX - pieceWidth * PADDING);
            int y = (int) Math.round(minY - pieceHeight * PADDING);
            int width = (int) Math.round(gridWidth + pieceWidth * PADDING * 2);
            int height = (int) Math.round(gridHeight + pieceHeight * PADDING * 2);

            if (x < 0) {
                x = 0;
            }
            if (y < 0) {
                y = 0;
            }
            if (x + width > img.getWidth()) {
                width = img.getWidth() - x;
            }
            if (y + height > img.getHeight()) {
                height = img.getHeight() - y;
            }
            if (width <= 0 || height <= 0) {
                return null;
            }

            return new java.awt.Rectangle(x, y, width, height);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 根据图片识别棋子及其位置。
     *
     * @param img   棋盘区域截图
     * @param board 输出：10x9 的棋盘矩阵，' ' 表示空位
     * @return 是否识别成功
     */
    @Override
    public boolean findChessBoard(BufferedImage img, char[][] board) {
        try {
            if (img == null) {
                return false;
            }

            float[][] corners = detectCorners(img);
            if (corners == null) {
                return false;
            }

            // 只做判断，不修改 corners
            boolean flipVertical = alignCorners(corners);

            BufferedImage warped = warpPerspective(img, corners, REG_SIZE, REG_SIZE);
            float[][][][] input = toTensor(warped, REG_SIZE, REG_SIZE);

            try (OnnxTensor tensor = OnnxTensor.createTensor(env, input)) {
                Map<String, OnnxTensor> container = new HashMap<>();
                container.put("input", tensor);
                try (OrtSession.Result results = session.run(container)) {
                    float[] output = ((OnnxTensor) results.get(0)).getFloatBuffer().array();
                    boolean ok = decodeBoard(output, board);
                    if (flipVertical) {
                        flipBoardVertical(board);
                    }
                    return ok;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 判断识别出的棋盘是否需要上下翻转。
     *
     * <p>只根据原始角点做判断，不修改 corners 的值。</p>
     *
     * <p>当棋盘大致竖直（顶边与水平线夹角 ≤ 30 度）时：
     * 如果模型输出的“左上”角点实际位于图像下方，
     * 说明识别结果上下颠倒，需要翻转 board，使 board[9] 对应原图下方。</p>
     *
     * @param corners detectCorners 返回的四个角点，顺序：左上、右上、左下、右下
     * @return true 表示需要上下翻转 board；false 表示不需要
     */
    private boolean alignCorners(float[][] corners) {
        // 顶边（左上 -> 右上）与水平线的夹角
        double dx = corners[1][0] - corners[0][0];
        double dy = corners[1][1] - corners[0][1];
        double tilt = Math.toDegrees(Math.atan2(Math.abs(dy), Math.abs(dx)));

        // 倾斜超过 30 度，不是常规的将帅上下分布，不翻转
        if (tilt > 30) {
            return false;
        }

        // 正常情况：左上角点的 y 应小于左下角点的 y。
        // 如果相反，说明模型输出的棋盘在图像中上下颠倒。
        return corners[0][1] > corners[2][1];
    }

    /**
     * 旋转180度
     */
    private void flipBoardVertical(char[][] board) {
        int rows = board.length;
        int cols = board[0].length;
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                int ri = rows - 1 - i;
                int rj = cols - 1 - j;
                // 只处理对称对中“靠前”的那个位置，避免交换两次
                if (i < ri || (i == ri && j < rj)) {
                    char tmp = board[i][j];
                    board[i][j] = board[ri][rj];
                    board[ri][rj] = tmp;
                }
            }
        }
    }

    /**
     * 检测棋盘四个角点。
     *
     * @return 4 个角点坐标，顺序：左上、右上、左下、右下；失败返回 null
     */
    private float[][] detectCorners(BufferedImage img) {
        if (poseSession == null) {
            return null;
        }

        float[][][][] input = toTensor(img, POSE_SIZE, POSE_SIZE);
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input)) {
            Map<String, OnnxTensor> container = new HashMap<>();
            container.put("input", tensor);
            try (OrtSession.Result results = poseSession.run(container)) {

                float[] simccX = null;
                float[] simccY = null;
                for (Map.Entry<String, OnnxValue> entry : results) {
                    float[] data = ((OnnxTensor) entry.getValue()).getFloatBuffer().array();
                    if ("simcc_x".equals(entry.getKey())) {
                        simccX = data;
                    } else if ("simcc_y".equals(entry.getKey())) {
                        simccY = data;
                    }
                }
                if (simccX == null || simccY == null) {
                    return null;
                }

                float scaleX = img.getWidth() / (float) POSE_SIZE;
                float scaleY = img.getHeight() / (float) POSE_SIZE;
                float[][] corners = new float[NUM_KEYPOINTS][2];

                for (int k = 0; k < NUM_KEYPOINTS; k++) {
                    int start = k * SIMCC_LEN;
                    int end = start + SIMCC_LEN;
                    // simcc 长度为 512，即 2 倍输入尺寸，因此坐标 = 峰值位置 / 2
                    int localX = argmax(simccX, start, end) - start;
                    int localY = argmax(simccY, start, end) - start;
                    corners[k][0] = localX / 2.0f * scaleX;
                    corners[k][1] = localY / 2.0f * scaleY;
                }
                return corners;
            }

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 将 90 个交叉点的分类结果解码为 10x9 棋盘矩阵。
     */
    private boolean decodeBoard(float[] output, char[][] board) {
        if (output.length < NUM_POSITIONS * NUM_CLASSES) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 9; j++) {
                int base = (i * 9 + j) * NUM_CLASSES;
                int cls = argmax(output, base, base + NUM_CLASSES) - base;
                board[i][j] = classToChar(cls);
            }
        }
        return true;
    }

    /**
     * nano_v3-0319.onnx 分类索引转棋子字符。
     * 0 为空位，1 为棋盘背景（不会出现在交叉点），2~8 为红方，9~15 为黑方。
     */
    private char classToChar(int cls) {
        switch (cls) {
            case 2:  return 'K';
            case 3:  return 'A';
            case 4:  return 'B';
            case 5:  return 'N';
            case 6:  return 'R';
            case 7:  return 'C';
            case 8:  return 'P';
            case 9:  return 'k';
            case 10: return 'a';
            case 11: return 'b';
            case 12: return 'n';
            case 13: return 'r';
            case 14: return 'c';
            case 15: return 'p';
            default: return ' ';
        }
    }

    /**
     * 图像缩放为 w x h 并转为 CHW 的 float 张量（RGB，归一化到 0~1）。
     */
    private float[][][][] toTensor(BufferedImage img, int w, int h) {
        BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.drawImage(img, 0, 0, w, h, null);
        g2d.dispose();

        float[][][][] arr = new float[1][3][h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = resized.getRGB(x, y);
                arr[0][0][y][x] = ((rgb >> 16) & 0xff) / 255.0f;
                arr[0][1][y][x] = ((rgb >> 8) & 0xff) / 255.0f;
                arr[0][2][y][x] = (rgb & 0xff) / 255.0f;
            }
        }
        return arr;
    }

    /**
     * 透视变换：把四个角点包围的棋盘网格变换为 w x h 的矩形。
     */
    private BufferedImage warpPerspective(BufferedImage src, float[][] corners, int w, int h) {
        double[] srcPoints = {
                corners[0][0], corners[0][1],
                corners[1][0], corners[1][1],
                corners[2][0], corners[2][1],
                corners[3][0], corners[3][1]
        };
        // 目标顺序：左上、右上、左下、右下
        double[] dstPoints = {0, 0, w, 0, 0, h, w, h};

        double[][] homography = homography(srcPoints, dstPoints);
        double[][] inverse = invert(homography);

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double denominator = inverse[2][0] * x + inverse[2][1] * y + inverse[2][2];
                double sx = (inverse[0][0] * x + inverse[0][1] * y + inverse[0][2]) / denominator;
                double sy = (inverse[1][0] * x + inverse[1][1] * y + inverse[1][2]) / denominator;
                out.setRGB(x, y, sample(src, sx, sy));
            }
        }
        return out;
    }

    private int sample(BufferedImage src, double sx, double sy) {
        int x0 = (int) Math.floor(sx);
        int y0 = (int) Math.floor(sy);
        if (x0 < 0 || y0 < 0 || x0 + 1 >= src.getWidth() || y0 + 1 >= src.getHeight()) {
            return 0x727272; // 灰色，与现有模型填充色一致
        }
        float fx = (float) (sx - x0);
        float fy = (float) (sy - y0);

        int c00 = src.getRGB(x0, y0);
        int c10 = src.getRGB(x0 + 1, y0);
        int c01 = src.getRGB(x0, y0 + 1);
        int c11 = src.getRGB(x0 + 1, y0 + 1);

        int r = (int) bilinear((c00 >> 16) & 0xff, (c10 >> 16) & 0xff, (c01 >> 16) & 0xff, (c11 >> 16) & 0xff, fx, fy);
        int g = (int) bilinear((c00 >> 8) & 0xff, (c10 >> 8) & 0xff, (c01 >> 8) & 0xff, (c11 >> 8) & 0xff, fx, fy);
        int b = (int) bilinear(c00 & 0xff, c10 & 0xff, c01 & 0xff, c11 & 0xff, fx, fy);
        return (r << 16) | (g << 8) | b;
    }

    private float bilinear(int a, int b, int c, int d, float fx, float fy) {
        return (1 - fx) * (1 - fy) * a + fx * (1 - fy) * b + (1 - fx) * fy * c + fx * fy * d;
    }

    private double[][] homography(double[] src, double[] dst) {
        double[][] a = new double[8][8];
        double[] b = new double[8];
        for (int i = 0; i < 4; i++) {
            double x = src[2 * i];
            double y = src[2 * i + 1];
            double u = dst[2 * i];
            double v = dst[2 * i + 1];
            int row = 2 * i;
            a[row][0] = x; a[row][1] = y; a[row][2] = 1;
            a[row][3] = 0; a[row][4] = 0; a[row][5] = 0;
            a[row][6] = -u * x; a[row][7] = -u * y;
            b[row] = u;

            row = 2 * i + 1;
            a[row][0] = 0; a[row][1] = 0; a[row][2] = 0;
            a[row][3] = x; a[row][4] = y; a[row][5] = 1;
            a[row][6] = -v * x; a[row][7] = -v * y;
            b[row] = v;
        }
        double[] h = solve(a, b);
        return new double[][]{
                {h[0], h[1], h[2]},
                {h[3], h[4], h[5]},
                {h[6], h[7], 1}
        };
    }

    private double[][] invert(double[][] m) {
        double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        double[][] inv = new double[3][3];
        inv[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / det;
        inv[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det;
        inv[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det;
        inv[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / det;
        inv[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det;
        inv[1][2] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / det;
        inv[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / det;
        inv[2][1] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / det;
        inv[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det;
        return inv;
    }

    private double[] solve(double[][] a, double[] b) {
        int n = b.length;
        for (int p = 0; p < n; p++) {
            int max = p;
            for (int i = p + 1; i < n; i++) {
                if (Math.abs(a[i][p]) > Math.abs(a[max][p])) {
                    max = i;
                }
            }
            double[] tmp = a[p];
            a[p] = a[max];
            a[max] = tmp;
            double t = b[p];
            b[p] = b[max];
            b[max] = t;

            double pivot = a[p][p];
            for (int j = p; j < n; j++) {
                a[p][j] /= pivot;
            }
            b[p] /= pivot;

            for (int i = 0; i < n; i++) {
                if (i != p) {
                    double factor = a[i][p];
                    for (int j = p; j < n; j++) {
                        a[i][j] -= factor * a[p][j];
                    }
                    b[i] -= factor * b[p];
                }
            }
        }
        return b;
    }

    private int argmax(float[] arr, int from, int to) {
        int max = from;
        for (int i = from + 1; i < to; i++) {
            if (arr[i] > arr[max]) {
                max = i;
            }
        }
        return max;
    }
}
