package com.sojourners.chess.enginee;


import com.sojourners.chess.config.Properties;
import com.sojourners.chess.model.BookData;
import com.sojourners.chess.model.EngineConfig;
import com.sojourners.chess.model.ThinkData;
import com.sojourners.chess.openbook.OpenBookManager;
import com.sojourners.chess.util.PathUtils;
import com.sojourners.chess.util.StringUtils;

import java.io.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 引擎封装
 */
public class Engine {

    private Process process;

    private String protocol;

    private AnalysisModel analysisModel;
    private long analysisValue;

    private volatile boolean threadNumChange;
    private int threadNum;

    private volatile boolean hashSizeChange;
    private int hashSize;

    /**
     * 停止标志位
     */
    private volatile boolean stopFlag;
    private volatile long time;

    private BufferedReader reader;

    private BufferedWriter writer;

    private EngineCallBack cb;

    private Thread thread;

    private Random random;

    private int multiPV;

    // ===== 引擎掉线自愈（仅此一项，保持最小改动） =====
    /** 引擎配置，重启时复用 */
    private EngineConfig engineConfig;
    /** 主动关闭标记（退出/切换引擎），关闭后不再自动重启 */
    private volatile boolean closed;
    /** 重启单飞标记 */
    private volatile boolean restarting;
    /** 最近一次分析请求，用于重启后重新发出 */
    private volatile String lastFen;
    private volatile List<String> lastMoves;
    private volatile List<String> lastTactics;

    public enum AnalysisModel {
        FIXED_TIME,
        FIXED_STEPS,
        FIXED_NODES,
        INFINITE;
    }

    public Engine(EngineConfig ec, EngineCallBack cb) throws IOException {
        this.engineConfig = ec;
        this.protocol = ec.getProtocol();
        this.cb = cb;
        this.random = new SecureRandom();

        this.time = Integer.MAX_VALUE;

        if (ec.getOptions().get("MultiPV") != null) {
            multiPV = Integer.parseInt(ec.getOptions().get("MultiPV"));
        } else {
            multiPV = 1;
        }

        startProcess();
        cmd(protocol);

        for (Map.Entry<String, String> entry : ec.getOptions().entrySet()) {
            if ("uci".equals(this.protocol)) {
                cmd("setoption name " + entry.getKey() + " value " + entry.getValue());
            } else if ("ucci".equals(this.protocol)) {
                cmd("setoption " + entry.getKey() + " " + entry.getValue());
            }
        }
    }

    /** 启动引擎进程并挂上读取线程（初始化与自动重启共用）。 */
    private void startProcess() throws IOException {
        EngineConfig ec = this.engineConfig;
        process = Runtime.getRuntime().exec(ec.getPath(), null, PathUtils.getParentDir(ec.getPath()));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

        thread = Thread.startVirtualThread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    if (line.contains("depth") || line.contains("nps")) {
                        thinkDetail(line);
                    } else if (line.contains("bestmove")) {
                        bestMove(line);
                    }
                }
                // 读循环结束 = 引擎进程退出
                onEngineGone("引擎进程已退出");
            } catch (Exception e) {
                onEngineGone("引擎读取异常：" + e);
            }
        });
    }

    /** 引擎掉线处理：自动重启并把最近一次分析请求重新发出。 */
    private void onEngineGone(String reason) {
        if (closed || restarting) {
            return;
        }
        System.out.println("【引擎自愈】检测到引擎掉线：" + reason + "，准备自动重启…");
        restarting = true;
        Thread.startVirtualThread(() -> {
            try {
                for (int attempt = 1; attempt <= 5 && !closed; attempt++) {
                    try {
                        restartProcess();
                        System.out.println("【引擎自愈】引擎已自动重启成功（第" + attempt + "次尝试）。");
                        resumeAnalysis();
                        return;
                    } catch (Throwable t) {
                        System.out.println("【引擎自愈】第" + attempt + "次重启失败：" + t);
                        sleep(1000L * attempt);
                    }
                }
                System.out.println("【引擎自愈】多次重启失败，已放弃。请在界面中重新选择引擎。");
            } finally {
                restarting = false;
            }
        });
    }

    private synchronized void restartProcess() throws Exception {
        if (closed) {
            throw new IOException("引擎已关闭");
        }
        try {
            if (process != null) {
                process.destroyForcibly();
            }
        } catch (Exception ignore) {
        }
        try {
            reader.close();
        } catch (Exception ignore) {
        }
        try {
            writer.close();
        } catch (Exception ignore) {
        }

        startProcess();
        cmd(protocol);
        for (Map.Entry<String, String> entry : engineConfig.getOptions().entrySet()) {
            if ("uci".equals(this.protocol)) {
                cmd("setoption name " + entry.getKey() + " value " + entry.getValue());
            } else if ("ucci".equals(this.protocol)) {
                cmd("setoption " + entry.getKey() + " " + entry.getValue());
            }
        }
        if (threadNum > 0) {
            cmd(("uci".equals(this.protocol) ? "setoption name Threads value " : "setoption Threads ") + threadNum);
        }
        if (hashSize > 0) {
            cmd(("uci".equals(this.protocol) ? "setoption name Hash value " : "setoption Hash ") + hashSize);
        }
        // 给引擎一点启动时间，确保后续命令能正常接收
        sleep(300L);
    }

    /** 引擎重启后，把最近一次分析请求重新发出。 */
    private void resumeAnalysis() {
        String fen = lastFen;
        if (fen == null || fen.isEmpty()) {
            return;
        }
        List<String> moves = lastMoves == null ? null : new ArrayList<>(lastMoves);
        List<String> tactics = lastTactics == null ? null : new ArrayList<>(lastTactics);
        System.out.println("【引擎自愈】重新接管当前局面分析：" + fen);
        this.analysis(fen, moves, tactics);
    }

    public int getMultiPV() {
        return multiPV;
    }

    private void sleep(long t) {
        try {
            Thread.sleep(t);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static String test(String filePath, LinkedHashMap<String, String> options) {
        Process p = null;
        Thread h = null;
        BufferedWriter bw = null;
        BufferedReader br = null;
        try {
            p = Runtime.getRuntime().exec(filePath);
            bw = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
            br = new BufferedReader(new InputStreamReader(p.getInputStream()));

            AtomicBoolean f = new AtomicBoolean(false);
            BufferedReader finalBr = br;
            (h = Thread.ofVirtual().unstarted(() -> {
                try {
                    String line;
                    while ((line = finalBr.readLine()) != null) {
                        if ("uciok".equals(line) || "ucciok".equals(line) ) {
                            f.set(true);
                        }
                        if (line.startsWith("option") && line.contains("name") && line.contains("type") && line.contains("default")
                                && !line.contains("Threads") && !line.contains("Hash")) {

                            String[] str = line.split("name|type|default");
                            String key = str[1].trim();
                            String value = str[3].trim().split(" ")[0];
                            options.put(key, value);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            })).start();

            bw.write("uci" + System.getProperty("line.separator"));
            bw.flush();
            Thread.sleep(1000);
            if (f.get()) {
                return "uci";
            }

            bw.write("ucci" + System.getProperty("line.separator"));
            bw.flush();
            Thread.sleep(1000);
            if (f.get()) {
                return "ucci";
            }

            return null;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (p != null) {
                p.destroy();
            }
            if (h.isAlive()) {
                h.interrupt();
            }
            try {
                if (bw != null) {
                    bw.close();
                }
                if (br != null) {
                    br.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private boolean validateMove(String move) {
        if (StringUtils.isEmpty(move) || move.length() != 4) {
            return false;
        }
        if (move.charAt(0) < 'a' || move.charAt(0) > 'i' || move.charAt(2) < 'a' || move.charAt(2) > 'i') {
            return false;
        }
        if (move.charAt(1) < '0' || move.charAt(1) > '9' || move.charAt(3) < '0' || move.charAt(3) > '9') {
            return false;
        }
        return true;
    }
    private void bestMove(String msg) {
        if (stopFlag) {
            stopFlag = false;
            return;
        }

        String[] str = msg.split(" ");
        if (str.length < 2 || !validateMove(str[1])) {
            return;
        }
        if (Properties.getInstance().getEngineDelayEnd() > 0 && Properties.getInstance().getEngineDelayEnd() >= Properties.getInstance().getEngineDelayStart()) {
            int t = random.nextInt(Properties.getInstance().getEngineDelayStart(), Properties.getInstance().getEngineDelayEnd());
            sleep(t);
        }
        cb.bestMove(str[1], str.length == 4 ? str[3] : null);
    }
    private void thinkDetail(String msg) {
        String[] str = msg.split(" ");
        ThinkData td = new ThinkData();
        List<String> detail = new ArrayList<>();
        td.setDetail(detail);
        int flag = 0;
        for (int i = 0; i < str.length; i++) {
            if (flag != 0) {
                if (flag == 6) {
                    detail.add(str[i]);
                } else {
                    if (StringUtils.isDigit(str[i])) {
                        if (flag == 1) {
                            td.setNps(Long.parseLong(str[i]));

                        } else if (flag == 2) {
                            td.setTime(Long.parseLong(str[i]));

                        } else if (flag == 3) {
                            td.setDepth(Integer.parseInt(str[i]));

                        } else if (flag == 4) {
                            td.setMate(Integer.parseInt(str[i]));

                        } else if (flag == 5) {
                            td.setScore(Integer.parseInt(str[i]));

                        } else if (flag == 7) {
                            td.setPv(Integer.parseInt(str[i]));
                        }
                        flag = 0;
                    } else {
                        continue;
                    }
                }
            } else {
                if ("depth".equals(str[i])) {
                    flag = 3;
                } else if ("score".equals(str[i])) {
                    if ("mate".equals(str[i + 1])) {
                        flag = 4;
                    } else {
                        flag = 5;
                    }
                } else if ("mate".equals(str[i])) {
                    flag = 4;
                } else if ("nps".equals(str[i])) {
                    flag = 1;
                } else if ("time".equals(str[i])) {
                    flag = 2;
                } else if ("pv".equals(str[i])) {
                    flag = 6;
                } else if ("multipv".equals(str[i])) {
                    flag = 7;
                }
            }
        }

        if (td.getDepth() != null && td.getDepth() < 5) {
            stopFlag = false;
        }
        if (td.getTime() != null) {
            if (td.getTime() < this.time || td.getTime() > 0 && td.getTime() < 70) {
                stopFlag = false;
            }
            this.time = td.getTime();
        }

        if (td.getDetail().size() > 0) {
            cb.thinkDetail(td);
        }
    }

    public void analysis(String fenCode, List<String> moves, char[][] board, boolean redGo) {
        Thread.startVirtualThread(() -> {
            if (Properties.getInstance().getBookSwitch()) {
                long s = System.currentTimeMillis();
                List<BookData> results = OpenBookManager.getInstance().queryBook(board, redGo, moves.size() / 2 >= Properties.getInstance().getOffManualSteps());
                System.out.println("查询库时间" + (System.currentTimeMillis() - s));
                this.cb.showBookResults(results);
                if (results.size() > 0 && this.analysisModel != AnalysisModel.INFINITE) {
                    if (Properties.getInstance().getBookDelayEnd() > 0 && Properties.getInstance().getBookDelayEnd() >= Properties.getInstance().getBookDelayStart()) {
                        int t = random.nextInt(Properties.getInstance().getBookDelayStart(), Properties.getInstance().getBookDelayEnd());
                        sleep(t);
                    }
                    this.cb.bestMove(results.get(0).getMove(), null);
                    return;
                }

            }
            this.analysis(fenCode, moves, null);
        });
    }

    public void analysis(String fenCode, List<String> moves, List<String> tacticList) {
        // 记录最近一次分析请求（引擎掉线自动重启后重新发出）
        this.lastFen = fenCode;
        this.lastMoves = moves == null ? null : new ArrayList<>(moves);
        this.lastTactics = tacticList == null ? null : new ArrayList<>(tacticList);
        stop();

        if (threadNumChange) {
            cmd(("uci".equals(this.protocol) ? "setoption name Threads value " : "setoption Threads ") + threadNum);
            this.threadNumChange = false;
        }
        if (hashSizeChange) {
            cmd(("uci".equals(this.protocol) ? "setoption name Hash value " : "setoption Hash ") + hashSize);
            this.hashSizeChange = false;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("position fen ").append(fenCode);
        if (moves != null && moves.size() > 0) {
            sb.append(" moves");
            for (String move : moves) {
                sb.append(" ").append(move);
            }
        }
        cmd(sb.toString());

        boolean hasTactics = tacticList != null && !tacticList.isEmpty();
        if (hasTactics) {
            sb = new StringBuilder();
            sb.append(" searchmoves");
            for (String tactic : tacticList) {
                sb.append(" ").append(tactic);
            }
        }
        if (analysisModel == AnalysisModel.FIXED_STEPS) {
            cmd("go depth " + analysisValue + (hasTactics ? sb.toString() : ""));
        } else if (analysisModel == AnalysisModel.FIXED_TIME) {
            cmd("go movetime " + analysisValue + (hasTactics ? sb.toString() : ""));
        } else if (analysisModel == AnalysisModel.FIXED_NODES) {
            cmd("go nodes " + analysisValue + (hasTactics ? sb.toString() : ""));
        } else {
            cmd("go infinite" + (hasTactics ? sb.toString() : ""));
        }
    }

    public void moveNow() {
        cmd("stop");
    }

    public void stop() {
        stopFlag = true;
        cmd("stop");
    }

    private void cmd(String command) {
        System.out.println(command);
        try {
            writer.write(command + System.getProperty("line.separator"));
            writer.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setThreadNum(int threadNum) {
        if (threadNum != this.threadNum) {
            this.threadNum = threadNum;
            this.threadNumChange = true;
        }

    }

    public void setHashSize(int hashSize) {
        if (hashSize != this.hashSize) {
            this.hashSize = hashSize;
            this.hashSizeChange = true;
        }
    }

    public void setAnalysisModel(AnalysisModel model, long v) {
        this.analysisModel = model;
        this.analysisValue = v;
    }

    public void close() {
        try {
            if (process.isAlive()) {
                cmd("quit");
            }

            if (thread.isAlive()) {
                thread.interrupt();
            }

            if (process.isAlive()) {
                process.destroy();
            }

            if (reader != null) {
                reader.close();
            }
            if (writer != null) {
                writer.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
