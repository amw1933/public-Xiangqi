package com.sojourners.chess.linker;

import com.sojourners.chess.util.PathUtils;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 终局画面按钮模板库。
 *
 * <p>每遇到一种新的“再来一局/继续/下一轮”风格画面，把该画面的按钮区域截图放入
 * 程序目录 {@code buttonlib/} 下（例如 {@code 再来一局.png}、{@code 下一轮.png}），
 * 程序启动时全部加载；运行中对屏幕做模板匹配，命中即认为需要点击继续。</p>
 */
public final class ButtonLibrary {

    /** 匹配分数上限（0~255，越小越严格）：实测终局画面命中约 7~10，棋盘画面 53+。 */
    public static final double MATCH_THRESHOLD = 22.0;

    /** 屏幕搜索起始高度比例（按钮都在下方区域）。 */
    private static final double SEARCH_TOP_RATIO = 0.40;
    /** 优先模板（_开头）的搜索起始高度比例：关闭按钮可能在画面上部。 */
    private static final double PRIORITY_SEARCH_TOP_RATIO = 0.18;
    /** 普通按钮模板的横向搜索范围（只认底部中间这一溜，避免误触其他画面元素）。 */
    private static final double CENTER_BAND_X0_RATIO = 0.16;
    private static final double CENTER_BAND_X1_RATIO = 0.84;
    /** 普通按钮模板的起始高度比例（按钮都在画面下方）。 */
    private static final double CENTER_BAND_TOP_RATIO = 0.55;

    private static final String LIB_DIR_NAME = "buttonlib";
    private static volatile ButtonLibrary instance;

    private final List<Template> templates = new ArrayList<>();

    /** 自动采集开关：同一下载目录里出现的未命中画面，保存现场截图供训练模板。 */
    private static final String SHOT_DIR_NAME = "log/screenshots";

    public static ButtonLibrary getInstance() {
        ButtonLibrary local = instance;
        if (local == null) {
            synchronized (ButtonLibrary.class) {
                if (instance == null) {
                    instance = new ButtonLibrary();
                }
                local = instance;
            }
        }
        return local;
    }

    private ButtonLibrary() {
        File dir = new File(PathUtils.getJarPath(), LIB_DIR_NAME);
        if (dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            });
            if (files != null) {
                for (File f : files) {
                    try {
                        BufferedImage img = ImageIO.read(f);
                        if (img != null && img.getWidth() > 20 && img.getHeight() > 10) {
                            templates.add(new Template(f.getName(), img));
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    public int size() {
        return templates.size();
    }

    /** 重新从磁盘加载模板（自动采集新模板后调用）。 */
    public synchronized void reload() {
        loadFrom(new File(PathUtils.getJarPath(), LIB_DIR_NAME));
    }

    /** 从指定目录加载模板（默认目录为 程序目录/buttonlib）。 */
    public synchronized void loadFrom(File dir) {
        templates.clear();
        if (dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            });
            if (files != null) {
                for (File f : files) {
                    try {
                        BufferedImage img = ImageIO.read(f);
                        if (img != null && img.getWidth() > 20 && img.getHeight() > 10) {
                            templates.add(new Template(f.getName(), img));
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    /** 保存未命中的现场画面（自动采集数据，供制作新模板）。 */
    public static void saveSceneShot(BufferedImage screen) {
        try {
            File dir = new File(PathUtils.getJarPath(), SHOT_DIR_NAME);
            File[] files = dir.isDirectory() ? dir.listFiles() : null;
            if (files != null && files.length >= 500) {
                return;
            }
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            File out = new File(dir, "scene_" + System.currentTimeMillis() + ".png");
            ImageIO.write(screen, "png", out);
        } catch (Throwable ignored) {
        }
    }

    /** 命中返回按钮中心点 {x, y}，未命中返回 null。 */
    public int[] match(BufferedImage screen) {
        return match(screen, null);
    }

    /** 多尺度范围与步长（不同局面的按钮大小会变，用缩放匹配兜住）。 */
    private static final double SCALE_MIN = 0.6;
    private static final double SCALE_MAX = 1.6;
    private static final double SCALE_STEP = 0.125;

    /** 命中返回按钮中心点；nameOut[0] 为命中的模板文件名（可为 null）。 */
    public int[] match(BufferedImage screen, String[] nameOut) {
        if (screen == null || templates.isEmpty()) {
            return null;
        }
        // 先匹配优先模板（文件名以 _ 开头，例如 _关闭X.png）：
        // 用于“先关弹窗、再点继续”的场景，优先级高于普通按钮。
        for (Template t : templates) {
            if (t.name.startsWith("_")) {
                int[] r = matchMultiScale(screen, t.image, PRIORITY_SEARCH_TOP_RATIO);
                if (r != null) {
                    if (nameOut != null && nameOut.length > 0) {
                        nameOut[0] = t.name;
                    }
                    return r;
                }
            }
        }
        int[] best = null;
        String bestName = null;
        double bestScore = MATCH_THRESHOLD;
        for (Template t : templates) {
            if (t.name.startsWith("_")) {
                continue;
            }
            // 普通按钮（再来一局/继续…）只在底部中间区域搜索，容差可放宽
            int[] r = matchMultiScale(screen, t.image, CENTER_BAND_TOP_RATIO, true);
            if (r != null && r[2] < bestScore) {
                bestScore = r[2];
                best = r;
                bestName = t.name;
            }
        }
        if (best != null && nameOut != null && nameOut.length > 0) {
            nameOut[0] = bestName;
        }
        return best;
    }

    /** 多尺度匹配：对模板缩放后逐个尝试，取最优。 */
    private static int[] matchMultiScale(BufferedImage screen, BufferedImage template) {
        return matchMultiScale(screen, template, SEARCH_TOP_RATIO, false);
    }

    private static int[] matchMultiScale(BufferedImage screen, BufferedImage template, double topRatio) {
        return matchMultiScale(screen, template, topRatio, false);
    }

    private static int[] matchMultiScale(BufferedImage screen, BufferedImage template, double topRatio, boolean centerBand) {
        // 第一轮：大步长粗筛所有尺度，找出最有希望的两个尺度
        double[] scales = new double[9];
        int n = 0;
        for (double s = SCALE_MIN; s <= SCALE_MAX + 1e-6; s += SCALE_STEP) {
            scales[n++] = s;
        }
        double bestS1 = -1, bestS2 = -1;
        double c1 = Double.MAX_VALUE, c2 = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            BufferedImage scaled = scaleTemplate(template, scales[i], screen);
            if (scaled == null) {
                continue;
            }
            int[] r = matchTemplate(screen, scaled, 8, topRatio, centerBand);
            if (r == null) {
                continue;
            }
            double score = r[2];
            if (score < c1) {
                c2 = c1; bestS2 = bestS1;
                c1 = score; bestS1 = scales[i];
            } else if (score < c2) {
                c2 = score; bestS2 = scales[i];
            }
        }
        // 第二轮：对最优的两个尺度精查
        int[] best = null;
        double bestScore = MATCH_THRESHOLD;
        double[] fine = {bestS1, bestS2};
        for (double s : fine) {
            if (s <= 0) {
                continue;
            }
            BufferedImage scaled = scaleTemplate(template, s, screen);
            if (scaled == null) {
                continue;
            }
            int[] r = matchTemplate(screen, scaled, 4, topRatio, centerBand);
            if (r != null && r[2] < bestScore) {
                bestScore = r[2];
                best = r;
            }
        }
        return best;
    }

    private static BufferedImage scaleTemplate(BufferedImage template, double s, BufferedImage screen) {
        try {
            int nw = Math.max(8, (int) Math.round(template.getWidth() * s));
            int nh = Math.max(6, (int) Math.round(template.getHeight() * s));
            if (nw > screen.getWidth() || nh > screen.getHeight()) {
                return null;
            }
            BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(template, 0, 0, nw, nh, null);
            g.dispose();
            return scaled;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class Template {
        final String name;
        final BufferedImage image;

        Template(String name, BufferedImage image) {
            this.name = name;
            this.image = image;
        }
    }

    /**
     * 在屏幕下方区域做模板匹配（4 倍降采样粗定位 + 全分辨率精修）。
     *
     * @return {centerX, centerY, score}，未找到或分数超阈值返回 null
     */
    static int[] matchTemplate(BufferedImage screen, BufferedImage template) {
        return matchTemplate(screen, template, 4, SEARCH_TOP_RATIO);
    }

    static int[] matchTemplate(BufferedImage screen, BufferedImage template, int coarseStep) {
        return matchTemplate(screen, template, coarseStep, SEARCH_TOP_RATIO);
    }

    static int[] matchTemplate(BufferedImage screen, BufferedImage template, int coarseStep, double topRatio) {
        return matchTemplate(screen, template, coarseStep, topRatio, false);
    }

    static int[] matchTemplate(BufferedImage screen, BufferedImage template, int coarseStep, double topRatio, boolean centerBand) {
        int sw = screen.getWidth();
        int sh = screen.getHeight();
        int tw = template.getWidth();
        int th = template.getHeight();
        if (tw > sw || th > sh) {
            return null;
        }
        int y0 = (int) (sh * topRatio);
        int xBase = 0;
        int x1 = sw - tw;
        if (centerBand) {
            // 只在底部中间一溜里找：按钮基本都在中轴附近
            xBase = Math.max(0, (int) (sw * CENTER_BAND_X0_RATIO));
            x1 = Math.min(sw - tw, (int) (sw * CENTER_BAND_X1_RATIO) - tw / 2);
        }
        int y1 = sh - th;
        if (x1 < 0 || y1 < y0) {
            return null;
        }

        // 一次性取出像素到数组（避免逐点 getRGB 的高开销）
        int[] scr = screen.getRGB(0, 0, sw, sh, null, 0, sw);
        int[] tpl = template.getRGB(0, 0, tw, th, null, 0, tw);

        final int step = coarseStep;
        long bestSum = Long.MAX_VALUE;
        int bestX = -1;
        int bestY = -1;

        for (int y = y0; y <= y1; y += step) {
            for (int x = xBase; x <= x1; x += step) {
                long sum = 0;
                for (int ty = 0; ty < th; ty += step) {
                    int rowScr = (y + ty) * sw + x;
                    int rowTpl = ty * tw;
                    for (int tx = 0; tx < tw; tx += step) {
                        sum += diff(scr[rowScr + tx], tpl[rowTpl + tx]);
                    }
                }
                if (sum < bestSum) {
                    bestSum = sum;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        if (bestX < 0) {
            return null;
        }

        // 全分辨率精修（±4 像素内，逐像素）
        int rx0 = Math.max(xBase, bestX - step);
        int ry0 = Math.max(y0, bestY - step);
        int rx1 = Math.min(x1, bestX + step);
        int ry1 = Math.min(y1, bestY + step);
        bestSum = Long.MAX_VALUE;
        for (int y = ry0; y <= ry1; y++) {
            for (int x = rx0; x <= rx1; x++) {
                long sum = 0;
                for (int ty = 0; ty < th; ty++) {
                    int rowScr = (y + ty) * sw + x;
                    int rowTpl = ty * tw;
                    for (int tx = 0; tx < tw; tx++) {
                        sum += diff(scr[rowScr + tx], tpl[rowTpl + tx]);
                    }
                }
                if (sum < bestSum) {
                    bestSum = sum;
                    bestX = x;
                    bestY = y;
                }
            }
        }

        long samples = (long) tw * th * 3;
        double score = (double) bestSum / samples;
        if (score > MATCH_THRESHOLD) {
            return null;
        }
        return new int[]{bestX + tw / 2, bestY + th / 2, (int) Math.round(score)};
    }

    private static int diff(int c1, int c2) {
        int r1 = (c1 >> 16) & 0xFF;
        int g1 = (c1 >> 8) & 0xFF;
        int b1 = c1 & 0xFF;
        int r2 = (c2 >> 16) & 0xFF;
        int g2 = (c2 >> 8) & 0xFF;
        int b2 = c2 & 0xFF;
        return Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);
    }
}
