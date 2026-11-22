package com.argus.rag.finance.report;

import com.argus.rag.finance.report.model.FundAccountResult;
import com.argus.rag.finance.report.model.FundDailyResult;
import com.argus.rag.finance.report.model.FundGroupResult;
import com.argus.rag.finance.report.model.FundReportConfig;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 资金报表工作簿扫描器 —— fund-daily-report 技能 gen_fund_daily.py 核心逻辑的 Java 移植。
 *
 * <p>口径要点（与技能 2026-09-23 修复后一致，勿回退）：</p>
 * <ul>
 *   <li><b>双切片取数</b>：基准日当天行求和（收入/支出/末笔余额），
 *       基准日首行紧邻之上的前驱行余额 = 上日余额；无基准日流水则回退
 *       最近 prior 日收盘余额（结转账户），再无则期初种子，再无则 0；</li>
 *   <li><b>净额口径</b>：income/payout 已剔除内部转账与理财（各自成桶），
 *       判定只看「费用性质」列；理财行含「定期购买/赎回非空且费用性质为空」的兜底支路，
 *       而费用性质含转款关键词的行即使定期列非空也归内部转账（费用性质优先）；</li>
 *   <li><b>理财桶方向</b>（定期余额视角）：借方（定赎/到期）→ financePayout，
 *       贷方（买入理财）→ financeIncome；</li>
 *   <li><b>防御</b>：占位行（无摘要/借贷、余额 0 清零既有余额）忽略；
 *       升序早停在读取任何行状态之前，避免源表预填的未来日期行污染存量口径。</li>
 * </ul>
 */
@Component
public class FundWorkbookScanner {

    private static final Logger log = LoggerFactory.getLogger(FundWorkbookScanner.class);

    private static final int SAFE_COLS = 64;
    private static final int SAFE_ROWS = 2000;
    private static final int HEADER_SCAN_ROWS = 6;
    private static final int BLANK_STREAK_STOP = 200;

    private static final String[] HEADER_KEYS = {"日期", "时间", "借方", "贷方", "余额", "交易日期"};

    private final DataFormatter formatter = new DataFormatter(Locale.CHINA);

    /** 单 Sheet 布局：各关键列的列索引（null = 该列不存在）。 */
    record SheetLayout(Integer date, Integer debit, Integer credit, Integer balance,
                       Integer fixedDeposit, Integer fdBuy, Integer fdRedeem,
                       Integer summary, Integer nature) {
    }

    // ─────────────── 布局探测（detect_layout 移植） ───────────────

    /** 鲁棒探测表头与关键列索引（日期/借方/贷方/余额/摘要/费用性质）。 */
    SheetLayout detectLayout(Sheet sheet) {
        int headerRowIdx = -1;
        int best = -1;
        for (int r = 0; r < HEADER_SCAN_ROWS && r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            int score = 0;
            for (int c = 0; c < SAFE_COLS && c <= row.getLastCellNum(); c++) {
                Cell cell = row.getCell(c);
                String text = cell == null ? null : cellString(cell);
                if (text != null && containsAny(text, HEADER_KEYS)) {
                    score++;
                }
            }
            if (score > best) {
                best = score;
                headerRowIdx = r;
            }
        }
        if (headerRowIdx < 0) {
            return new SheetLayout(fallbackDateColumn(sheet), null, null, null, null, null, null, null, null);
        }
        Row header = sheet.getRow(headerRowIdx);
        int width = header == null ? 0 : header.getLastCellNum();
        List<String> cells = new ArrayList<>();
        for (int c = 0; c < Math.min(width, SAFE_COLS); c++) {
            Cell cell = header.getCell(c);
            cells.add(cell == null ? null : cellString(cell));
        }

        Integer dateI = firstIndexContaining(cells, "日期", "时间", "交易日期");
        if (dateI == null) {
            dateI = fallbackDateColumn(sheet);
        }
        Integer debitI = firstIndexContaining(cells, "借方", "借");
        Integer creditI = firstIndexContaining(cells, "贷方", "贷");
        Integer balI = firstIndexContaining(cells, "余额");
        Integer fixedDepositI = firstIndexContaining(cells, "定期余额");
        Integer fdBuyI = firstIndexContaining(cells, "定期购买");
        Integer fdRedeemI = firstIndexContaining(cells, "定期赎回");
        Integer summaryI = firstIndexContaining(cells, "摘要", "费用", "用途", "说明", "备注");
        Integer natureI = firstIndexContaining(cells, "费用性质", "性质", "费用类别", "类别");

        // 兜底：表头无「贷方」字样、但「借方」与「余额」之间恰有一列含数值 → 判为贷方。
        // 部分导出表把贷方列留成空表头（如 日期/摘要/借方//余额），漏读会整笔丢失支出。
        if (creditI == null && debitI != null && balI != null && balI - debitI > 1) {
            Map<Integer, Integer> numericCount = new LinkedHashMap<>();
            for (Row row : sheet) {
                for (int c = debitI + 1; c < balI && c < SAFE_COLS; c++) {
                    Cell cell = row.getCell(c);
                    if (cell != null && cell.getCellType() == CellType.NUMERIC) {
                        numericCount.merge(c, 1, Integer::sum);
                    }
                }
            }
            Integer bestCol = null;
            int bestCount = 0;
            for (Map.Entry<Integer, Integer> e : numericCount.entrySet()) {
                if (e.getValue() > bestCount) {
                    bestCount = e.getValue();
                    bestCol = e.getKey();
                }
            }
            if (bestCol != null && bestCount >= 1) {
                creditI = bestCol;
            }
        }
        return new SheetLayout(dateI, debitI, creditI, balI, fixedDepositI, fdBuyI, fdRedeemI, summaryI, natureI);
    }

    private Integer fallbackDateColumn(Sheet sheet) {
        Map<Integer, Integer> score = new LinkedHashMap<>();
        for (Row row : sheet) {
            for (int c = 0; c < SAFE_COLS && c < row.getLastCellNum(); c++) {
                Cell cell = row.getCell(c);
                if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                    score.merge(c, 1, Integer::sum);
                }
            }
        }
        Integer bestCol = null;
        int best = 0;
        for (Map.Entry<Integer, Integer> e : score.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                bestCol = e.getKey();
            }
        }
        return bestCol;
    }

    private Integer firstIndexContaining(List<String> cells, String... keys) {
        for (int i = 0; i < cells.size(); i++) {
            String text = cells.get(i);
            if (text != null) {
                for (String key : keys) {
                    if (text.contains(key)) {
                        return i;
                    }
                }
            }
        }
        return null;
    }

    private boolean containsAny(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, List<String> keys) {
        return keys != null && containsAny(text, keys.toArray(String[]::new));
    }

    // ─────────────── 单账户计算（calc_account 移植） ───────────────

    FundAccountResult calcAccount(Sheet sheet, SheetLayout layout, LocalDate benchmarkDate,
                                  String sourceFile, String sheetName, FundReportConfig config) {
        Integer di = layout.date();
        Integer debitI = layout.debit();
        Integer creditI = layout.credit();
        Integer balI = layout.balance();
        Integer fdI = layout.fixedDeposit();
        Integer fdBuyI = layout.fdBuy();
        Integer fdRedeemI = layout.fdRedeem();
        Integer summI = layout.summary();
        Integer natureI = layout.nature();
        boolean debitAsIncome = config.isDebitAsIncome();
        boolean assumeAsc = config.isAssumeDateAscending();

        BigDecimal income = BigDecimal.ZERO, payout = BigDecimal.ZERO;
        BigDecimal transferIncome = BigDecimal.ZERO, transferPayout = BigDecimal.ZERO;
        BigDecimal financeIncome = BigDecimal.ZERO, financePayout = BigDecimal.ZERO;
        BigDecimal balance = null;
        BigDecimal benchmarkRegular = null;
        BigDecimal running = null;
        BigDecimal openingBalance = null;
        BigDecimal fdRunning = BigDecimal.ZERO;
        BigDecimal fdOpening = null;
        BigDecimal predBalCell = null;
        BigDecimal predRunning = null;
        BigDecimal predFd = BigDecimal.ZERO;
        boolean seenBenchmark = false;
        LocalDate lastPriorDate = null;
        BigDecimal lastPriorBalance = null;
        BigDecimal lastPriorFd = BigDecimal.ZERO;
        List<String> placeholderNotes = new ArrayList<>();

        List<Integer> relIdx = new ArrayList<>();
        for (Integer i : new Integer[]{di, debitI, creditI, balI, fdI, summI}) {
            if (i != null) {
                relIdx.add(i);
            }
        }
        boolean enableRowStop = sheet.getLastRowNum() + 1 > SAFE_ROWS;
        int blankStreak = 0;

        for (Row row : sheet) {
            int rowNum = row == null ? -1 : row.getRowNum();
            if (rowNum >= SAFE_ROWS) {
                break;
            }
            if (row == null) {
                continue;
            }
            // 超长表下，连续 200 行全空且已越过基准日并取到上日余额 → 提前结束
            if (enableRowStop && !relIdx.isEmpty()) {
                boolean blank = true;
                for (int i : relIdx) {
                    Cell cell = row.getCell(i);
                    if (cell != null && cell.getCellType() != CellType.BLANK && !isBlankString(cell)) {
                        blank = false;
                        break;
                    }
                }
                if (blank) {
                    blankStreak++;
                    if (blankStreak >= BLANK_STREAK_STOP && seenBenchmark && (predBalCell != null || predRunning != null)) {
                        break;
                    }
                } else {
                    blankStreak = 0;
                }
            }
            if (di == null || di >= row.getLastCellNum()) {
                continue;
            }
            LocalDate d = cellToDate(row.getCell(di));
            // 早停（升序假设）：必须在读取任何行列状态之前——防止源表预填的
            // 未来日期行污染定期余额等存量口径（技能 2026-09-28 修复）。
            if (assumeAsc && d != null && d.isAfter(benchmarkDate) && seenBenchmark) {
                break;
            }
            BigDecimal inc = numAt(row, debitI);
            BigDecimal pay = numAt(row, creditI);
            Cell balCell = cellAt(row, balI);
            boolean balPresent = balCell != null && !isBlankValue(balCell);
            Cell fdCell = cellAt(row, fdI);
            boolean fdPresent = fdCell != null && !isBlankValue(fdCell);
            if (fdPresent) {
                fdRunning = toFloat(fdCell);
            }
            BigDecimal pk = absToFloat(cellAt(row, fdBuyI));
            BigDecimal rk = absToFloat(cellAt(row, fdRedeemI));

            if (benchmarkDate.equals(d) && !seenBenchmark) {
                seenBenchmark = true;
            }
            // 占位行防御（2026-09-30 技能修复，勿回退）：日期可解析 + 摘要/借贷/定期全空
            // + 本行余额为 0 + 已有非零滚动余额 → 视为占位行，不推进任何状态。
            // 结转行（余额非零）不能跳过，否则丢结转余额本身。
            if (d != null) {
                String summText = textAt(row, summI);
                if (summText.isEmpty() && inc.abs().compareTo(COMPARE_EPS) < 0 && pay.abs().compareTo(COMPARE_EPS) < 0
                        && !fdPresent && pk.compareTo(FD_EPS) <= 0 && rk.compareTo(FD_EPS) <= 0
                        && balPresent && toFloat(balCell).abs().compareTo(COMPARE_EPS) <= 0
                        && running != null && running.abs().compareTo(COMPARE_EPS) > 0) {
                    placeholderNotes.add(d + " 占位行(无摘要/借贷、余额 0) 将既有余额 "
                            + running.add(fdRunning).setScale(2, RoundingMode.HALF_UP) + " 清零，已忽略");
                    continue;
                }
            }

            if (d == null) {
                // 期初 / 非日期行：有余额则作开盘种子；同时是基准日之前的候选前驱行
                if (balPresent) {
                    running = toFloat(balCell);
                    openingBalance = running;
                }
                if (fdPresent) {
                    fdOpening = fdRunning;
                }
                if (!seenBenchmark) {
                    predBalCell = balPresent ? toFloat(balCell) : null;
                    predRunning = running;
                    predFd = fdRunning;
                }
                continue;
            }

            if (balPresent) {
                running = toFloat(balCell);
            } else if (running != null) {
                running = running.add(inc).subtract(pay).setScale(2, RoundingMode.HALF_UP);
            }
            // 结转跟踪：最近 prior 日（< 基准日）收盘余额（日期最大者；同日期取最后物理行）
            if (d.isBefore(benchmarkDate)) {
                if (lastPriorDate == null || !d.isBefore(lastPriorDate)) {
                    lastPriorDate = d;
                    lastPriorBalance = running;
                    lastPriorFd = fdRunning;
                }
            }
            if (!seenBenchmark) {
                predBalCell = balPresent ? toFloat(balCell) : null;
                predRunning = running;
                predFd = fdRunning;
            }

            if (benchmarkDate.equals(d)) {
                BigDecimal regularBalance = balPresent ? toFloat(balCell).setScale(2, RoundingMode.HALF_UP)
                        : running.setScale(2, RoundingMode.HALF_UP);
                benchmarkRegular = regularBalance;
                balance = regularBalance.add(fdRunning).setScale(2, RoundingMode.HALF_UP);
                String natureText = textAt(row, natureI);
                String summText = textAt(row, summI);
                // 剔除判定：主报表锁定口径只看「费用性质」列
                String decisionText = natureText;
                boolean natureHasFin = !decisionText.isEmpty() && containsAny(decisionText.toLowerCase(), config.getFinanceKeywords());
                boolean natureHasTr = !decisionText.isEmpty() && containsAny(decisionText.toLowerCase(), config.getTransferKeywords());
                boolean hasFd = pk.compareTo(FD_EPS) > 0 || rk.compareTo(FD_EPS) > 0;
                boolean isFin = natureHasFin || (hasFd && decisionText.isEmpty());
                boolean isTr = natureHasTr;
                if (isFin) {
                    // 理财剔除（定期余额视角）：借方→financePayout（定赎/到期），贷方→financeIncome（买入）
                    if (inc.compareTo(COMPARE_EPS) > 0 || pay.compareTo(COMPARE_EPS) > 0) {
                        financePayout = financePayout.add(inc);
                        financeIncome = financeIncome.add(pay);
                    } else {
                        financeIncome = financeIncome.add(pk);
                        financePayout = financePayout.add(rk);
                    }
                } else if (isTr) {
                    if (debitAsIncome) {
                        transferIncome = transferIncome.add(inc);
                        transferPayout = transferPayout.add(pay);
                    } else {
                        transferIncome = transferIncome.add(pay);
                        transferPayout = transferPayout.add(inc);
                    }
                } else {
                    if (debitAsIncome) {
                        income = income.add(inc);
                        payout = payout.add(pay);
                    } else {
                        income = income.add(pay);
                        payout = payout.add(inc);
                    }
                }
            }
        }

        FundAccountResult result = new FundAccountResult();
        result.setSourceFile(sourceFile);
        result.setCarriedForward(false);
        if (balance == null) {
            // 无基准日流水 → 回退最近 prior 日收盘余额；无 prior 日则回退期初种子；再无则 0。
            // 结转账户余额真实存在，纳入小计/合计（has_data=true），避免合计被低估。
            BigDecimal regularCarry;
            BigDecimal fdCarry;
            if (lastPriorBalance != null) {
                regularCarry = lastPriorBalance.setScale(2, RoundingMode.HALF_UP);
                fdCarry = lastPriorFd.setScale(2, RoundingMode.HALF_UP);
            } else if (openingBalance != null) {
                regularCarry = openingBalance.setScale(2, RoundingMode.HALF_UP);
                fdCarry = fdOpening == null ? BigDecimal.ZERO : fdOpening;
            } else {
                regularCarry = null;
                fdCarry = BigDecimal.ZERO;
            }
            if (regularCarry != null) {
                BigDecimal carry = regularCarry.add(fdCarry).setScale(2, RoundingMode.HALF_UP);
                fillCarried(result, carry, regularCarry, fdCarry);
                result.setHasData(true);
                result.setCarriedForward(true);
                return finalize(result, config, sourceFile, sheetName);
            }
            fillEmpty(result);
            result.setHasData(false);
            return finalize(result, config, sourceFile, sheetName);
        }
        // 上日余额 = 基准日首行紧邻之上那一行的余额：前驱显式余额 → 前驱滚动余额 → 期初种子 → 0
        BigDecimal regularPrev;
        if (predBalCell != null) {
            regularPrev = predBalCell.setScale(2, RoundingMode.HALF_UP);
        } else if (predRunning != null) {
            regularPrev = predRunning.setScale(2, RoundingMode.HALF_UP);
        } else if (openingBalance != null) {
            regularPrev = openingBalance.setScale(2, RoundingMode.HALF_UP);
        } else {
            regularPrev = BigDecimal.ZERO;
        }
        result.setPrev(regularPrev.add(predFd).setScale(2, RoundingMode.HALF_UP));
        result.setIncome(income.setScale(2, RoundingMode.HALF_UP));
        result.setPayout(payout.setScale(2, RoundingMode.HALF_UP));
        result.setBalance(balance);
        result.setRegularBalance(benchmarkRegular == null
                ? balance.setScale(2, RoundingMode.HALF_UP) : benchmarkRegular);
        result.setFixedDepositBalance(fdRunning.setScale(2, RoundingMode.HALF_UP));
        result.setRegularPrev(regularPrev);
        result.setFixedDepositPrev(predFd.setScale(2, RoundingMode.HALF_UP));
        result.setTransferIncome(transferIncome.setScale(2, RoundingMode.HALF_UP));
        result.setTransferPayout(transferPayout.setScale(2, RoundingMode.HALF_UP));
        result.setFinanceIncome(financeIncome.setScale(2, RoundingMode.HALF_UP));
        result.setFinancePayout(financePayout.setScale(2, RoundingMode.HALF_UP));
        result.setHasData(true);
        for (String note : placeholderNotes) {
            log.info("资金报表占位行: {} {}: {}", sourceFile, sheet.getSheetName(), note);
        }
        return finalize(result, config, sourceFile, sheetName);
    }

    private static final BigDecimal COMPARE_EPS = new BigDecimal("0.005");
    private static final BigDecimal FD_EPS = new BigDecimal("0.001");

    private void fillCarried(FundAccountResult r, BigDecimal carry, BigDecimal regular, BigDecimal fd) {
        r.setPrev(carry);
        r.setIncome(BigDecimal.ZERO);
        r.setPayout(BigDecimal.ZERO);
        r.setBalance(carry);
        r.setRegularBalance(regular);
        r.setFixedDepositBalance(fd);
        r.setRegularPrev(regular);
        r.setFixedDepositPrev(fd);
        r.setTransferIncome(BigDecimal.ZERO);
        r.setTransferPayout(BigDecimal.ZERO);
        r.setFinanceIncome(BigDecimal.ZERO);
        r.setFinancePayout(BigDecimal.ZERO);
    }

    private void fillEmpty(FundAccountResult r) {
        r.setPrev(BigDecimal.ZERO);
        r.setIncome(BigDecimal.ZERO);
        r.setPayout(BigDecimal.ZERO);
        r.setBalance(BigDecimal.ZERO);
        r.setRegularBalance(BigDecimal.ZERO);
        r.setFixedDepositBalance(BigDecimal.ZERO);
        r.setRegularPrev(BigDecimal.ZERO);
        r.setFixedDepositPrev(BigDecimal.ZERO);
        r.setTransferIncome(BigDecimal.ZERO);
        r.setTransferPayout(BigDecimal.ZERO);
        r.setFinanceIncome(BigDecimal.ZERO);
        r.setFinancePayout(BigDecimal.ZERO);
    }

    private FundAccountResult finalize(FundAccountResult r, FundReportConfig config,
                                       String sourceFile, String sheetName) {
        r.setCurrency(resolveCurrency(sourceFile, sheetName, config));
        return r;
    }

    // ─────────────── 通用辅助（cell_to_date / to_float / 币种 / 公司名） ───────────────

    LocalDate cellToDate(Cell cell) {
        if (cell == null) {
            return null;
        }
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                Date date = cell.getDateCellValue();
                return date == null ? null
                        : date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            }
            if (cell.getCellType() == CellType.NUMERIC) {
                double v = cell.getNumericCellValue();
                if (v > 20000 && v < 60000) {
                    return java.time.LocalDate.of(1899, 12, 30).plusDays((long) v);
                }
                return null;
            }
            if (cell.getCellType() == CellType.STRING || cell.getCellType() == CellType.FORMULA) {
                return textToDate(cellString(cell));
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** 常见文本日期写法：2026-09-29 / 2026/9/29 / 2026.09.29 / 2026年9月29日 / 20260929。 */
    LocalDate textToDate(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        s = s.replace("年", "-").replace("月", "-").replace("日", "")
                .replace("/", "-").replace(".", "-").replace("\\", "-");
        s = s.split("T")[0].split(" ")[0].trim();
        String[] parts = s.split("-");
        List<String> segments = new ArrayList<>();
        for (String p : parts) {
            if (!p.isEmpty()) {
                segments.add(p);
            }
        }
        if (segments.size() == 1 && segments.get(0).length() == 8 && segments.get(0).matches("\\d+")) {
            String t = segments.get(0);
            segments = List.of(t.substring(0, 4), t.substring(4, 6), t.substring(6));
        }
        if (segments.size() != 3 || segments.stream().anyMatch(p -> !p.matches("\\d+"))) {
            return null;
        }
        try {
            int y = Integer.parseInt(segments.get(0));
            int m = Integer.parseInt(segments.get(1));
            int day = Integer.parseInt(segments.get(2));
            if (y < 1900 || y > 2200) {
                return null;
            }
            return LocalDate.of(y, m, day);
        } catch (Exception e) {
            return null;
        }
    }

    BigDecimal toFloat(Cell cell) {
        if (cell == null) {
            return BigDecimal.ZERO;
        }
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return BigDecimal.valueOf(cell.getNumericCellValue());
            }
            if (cell.getCellType() == CellType.STRING) {
                String s = cellString(cell).replace(",", "").trim();
                if (s.isEmpty()) {
                    return BigDecimal.ZERO;
                }
                return new BigDecimal(s);
            }
            if (cell.getCellType() == CellType.FORMULA) {
                String s = cellString(cell).replace(",", "").trim();
                if (s.isEmpty()) {
                    return BigDecimal.ZERO;
                }
                return new BigDecimal(s);
            }
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal numAt(Row row, Integer idx) {
        Cell cell = cellAt(row, idx);
        return cell == null ? BigDecimal.ZERO : toFloat(cell);
    }

    private BigDecimal absToFloat(Cell cell) {
        return cell == null ? BigDecimal.ZERO : toFloat(cell).abs();
    }

    private Cell cellAt(Row row, Integer idx) {
        if (idx == null || row == null || idx >= row.getLastCellNum()) {
            return null;
        }
        return row.getCell(idx);
    }

    private String textAt(Row row, Integer idx) {
        Cell cell = cellAt(row, idx);
        return cell == null ? "" : cellString(cell).trim();
    }

    private String cellString(Cell cell) {
        if (cell == null) {
            return null;
        }
        try {
            return formatter.formatCellValue(cell).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isBlankString(Cell cell) {
        String s = cellString(cell);
        return s == null || s.isEmpty();
    }

    private boolean isBlankValue(Cell cell) {
        return cell.getCellType() == CellType.BLANK || isBlankString(cell);
    }

    /** 账户名 → 币种：覆盖表优先（"文件::Sheet"），再按账户名关键字，默认 RMB。 */
    String resolveCurrency(String fileName, String sheetName, FundReportConfig config) {
        String key = fileName + "::" + (sheetName == null ? "" : sheetName);
        String override = config.getCurrencyOverrides().get(key);
        if (override != null && !override.isEmpty()) {
            return override;
        }
        String n = sheetName == null ? "" : sheetName.toLowerCase();
        for (Map.Entry<String, List<String>> e : config.getCurrencyKeywords().entrySet()) {
            for (String kw : e.getValue()) {
                if (n.contains(kw.toLowerCase())) {
                    return e.getKey();
                }
            }
        }
        return "RMB";
    }

    /** 文件名 → 公司名（去前缀/后缀年 + 去尾缀），无法解析返回 null。 */
    String parseCompanyName(String fileName, FundReportConfig config) {
        String stem = fileName;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        if (config.isCompanyNameStripYear()) {
            stem = stem.replaceFirst("^\\d{4}年?", "").replaceAll("\\s*\\d{4}\\s*$", "");
        }
        for (String suffix : config.getCompanyNameSuffixes()) {
            if (suffix != null && !suffix.isEmpty()) {
                stem = stem.replace(suffix, "");
            }
        }
        String name = stem.trim();
        return name.isEmpty() ? null : name;
    }

    boolean isExcluded(String fileName, FundReportConfig config) {
        for (String pattern : config.getExcludeFiles()) {
            if (pattern == null || pattern.isEmpty()) {
                continue;
            }
            if (wildcardMatch(fileName, pattern)) {
                return true;
            }
            String stem = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
            if (wildcardMatch(stem, pattern)) {
                return true;
            }
        }
        return fileName.contains("汇率表");
    }

    private boolean wildcardMatch(String text, String pattern) {
        if (!pattern.contains("*")) {
            return text.equals(pattern);
        }
        String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
        return text.matches(regex);
    }

    // ─────────────── 多文件聚合 ───────────────

    /**
     * 扫描多个文件生成日报结果：每文件=一公司（文件名解析），每 Sheet=一账户。
     * 外币按 config.fxRates 固定率折算为人民币口径并入合计。
     */
    public FundDailyResult scan(List<Path> files, LocalDate benchmarkDate, FundReportConfig config) {
        FundDailyResult result = new FundDailyResult();
        result.setReportTitle(config.getReportTitle());
        result.setReportDate(benchmarkDate);
        result.setDisplayUnit(config.getDisplayUnit());
        List<FundAccountResult> allAccounts = new ArrayList<>();
        Map<String, String> companyByFile = new LinkedHashMap<>();

        List<Path> kept = new ArrayList<>();
        for (Path file : files) {
            if (isExcluded(file.getFileName().toString(), config)) {
                result.getWarnings().add("已排除文件：" + file.getFileName());
            } else {
                kept.add(file);
            }
        }
        if (kept.isEmpty()) {
            result.getWarnings().add("未找到任何可用的资金报表文件");
            result.setBalanceCheckOk(false);
            result.setBalanceCheckDiff(BigDecimal.ZERO);
            return result;
        }

        for (Path file : kept) {
            String fileName = file.getFileName().toString();
            String company = parseCompanyName(fileName, config);
            if (company == null) {
                company = "未归集";
                result.getWarnings().add(fileName + ": 文件名无法解析公司名，账户归为未归集");
            }
            companyByFile.put(fileName, company);
            try (InputStream in = Files.newInputStream(file);
                 Workbook workbook = WorkbookFactory.create(in)) {
                boolean hasOtherReal = false;
                for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                    String name = workbook.getSheetName(i);
                    Sheet sheet = workbook.getSheetAt(i);
                    if (!"sheet1".equalsIgnoreCase(name.trim())
                            && !config.getSkipSheetNames().contains(name.trim().toLowerCase())
                            && sheet.getLastRowNum() + 1 >= 2) {
                        hasOtherReal = true;
                        break;
                    }
                }
                for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                    String sheetName = workbook.getSheetName(i);
                    String low = sheetName.trim().toLowerCase();
                    boolean skipSheet1 = "sheet1".equals(low) && hasOtherReal;
                    if (skipSheet1 || config.getSkipSheetNames().contains(low)) {
                        continue;
                    }
                    Sheet sheet = workbook.getSheetAt(i);
                    if (sheet.getLastRowNum() + 1 < 2) {
                        continue;
                    }
                    try {
                        SheetLayout layout = detectLayout(sheet);
                        FundAccountResult account = calcAccount(sheet, layout, benchmarkDate, fileName, sheetName, config);
                        account.setName(sheetName);
                        account.setCompany(company);
                        allAccounts.add(account);
                    } catch (Exception e) {
                        result.getWarnings().add(fileName + "/" + sheetName + ": 解析失败已跳过 - " + e.getMessage());
                        log.warn("资金报表解析失败: {} / {}", fileName, sheetName, e);
                    }
                }
            } catch (Exception e) {
                result.getWarnings().add(fileName + ": 打开失败已跳过 - " + e.getMessage());
                log.warn("资金报表打开失败: {}", fileName, e);
            }
        }

        // 外币固定率折算为人民币口径（阶段一；月度汇率表后续接入）
        for (FundAccountResult account : allAccounts) {
            if (!account.isHasData()) {
                continue;
            }
            Double rate = config.getFxRates().get(account.getCurrency());
            if (rate != null && rate.compareTo(1.0) != 0) {
                account.setConverted(true);
                BigDecimal factor = BigDecimal.valueOf(rate);
                account.setPrev(account.getPrev().multiply(factor).setScale(2, RoundingMode.HALF_UP));
                account.setIncome(account.getIncome().multiply(factor).setScale(2, RoundingMode.HALF_UP));
                account.setPayout(account.getPayout().multiply(factor).setScale(2, RoundingMode.HALF_UP));
                account.setBalance(account.getBalance().multiply(factor).setScale(2, RoundingMode.HALF_UP));
                account.setFixedDepositBalance(account.getFixedDepositBalance().multiply(factor).setScale(2, RoundingMode.HALF_UP));
                account.setFixedDepositPrev(account.getFixedDepositPrev().multiply(factor).setScale(2, RoundingMode.HALF_UP));
                account.setTransferIncome(account.getTransferIncome().multiply(factor).setScale(2, RoundingMode.HALF_UP));
                account.setTransferPayout(account.getTransferPayout().multiply(factor).setScale(2, RoundingMode.HALF_UP));
                account.setFinanceIncome(account.getFinanceIncome().multiply(factor).setScale(2, RoundingMode.HALF_UP));
                account.setFinancePayout(account.getFinancePayout().multiply(factor).setScale(2, RoundingMode.HALF_UP));
            }
        }

        // 按文件分组（严格一个 Excel 文件 = 一个公司分组）；小计仅含该文件有数据账户
        Map<String, List<FundAccountResult>> groupsMap = new LinkedHashMap<>();
        for (FundAccountResult account : allAccounts) {
            groupsMap.computeIfAbsent(account.getSourceFile(), k -> new ArrayList<>()).add(account);
        }
        BigDecimal totalPrev = BigDecimal.ZERO, totalIncome = BigDecimal.ZERO;
        BigDecimal totalPayout = BigDecimal.ZERO, totalBalance = BigDecimal.ZERO;
        BigDecimal totalTransferIncome = BigDecimal.ZERO, totalTransferPayout = BigDecimal.ZERO;
        BigDecimal totalFinanceIncome = BigDecimal.ZERO, totalFinancePayout = BigDecimal.ZERO;
        for (Map.Entry<String, List<FundAccountResult>> entry : groupsMap.entrySet()) {
            FundGroupResult group = new FundGroupResult();
            group.setSourceFile(entry.getKey());
            group.setCompany(companyByFile.getOrDefault(entry.getKey(), "未归集"));
            group.setAccounts(entry.getValue());
            for (FundAccountResult a : entry.getValue()) {
                if (!a.isHasData()) {
                    continue;
                }
                group.setPrev(sum(group.getPrev(), a.getPrev()));
                group.setIncome(sum(group.getIncome(), a.getIncome()));
                group.setPayout(sum(group.getPayout(), a.getPayout()));
                group.setBalance(sum(group.getBalance(), a.getBalance()));
                group.setTransferIncome(sum(group.getTransferIncome(), a.getTransferIncome()));
                group.setTransferPayout(sum(group.getTransferPayout(), a.getTransferPayout()));
                group.setFinanceIncome(sum(group.getFinanceIncome(), a.getFinanceIncome()));
                group.setFinancePayout(sum(group.getFinancePayout(), a.getFinancePayout()));
            }
            group.setPrev(group.getPrev().setScale(2, RoundingMode.HALF_UP));
            group.setIncome(group.getIncome().setScale(2, RoundingMode.HALF_UP));
            group.setPayout(group.getPayout().setScale(2, RoundingMode.HALF_UP));
            group.setBalance(group.getBalance().setScale(2, RoundingMode.HALF_UP));
            group.setTransferIncome(group.getTransferIncome().setScale(2, RoundingMode.HALF_UP));
            group.setTransferPayout(group.getTransferPayout().setScale(2, RoundingMode.HALF_UP));
            group.setFinanceIncome(group.getFinanceIncome().setScale(2, RoundingMode.HALF_UP));
            group.setFinancePayout(group.getFinancePayout().setScale(2, RoundingMode.HALF_UP));
            result.getGroups().add(group);
            totalPrev = totalPrev.add(group.getPrev());
            totalIncome = totalIncome.add(group.getIncome());
            totalPayout = totalPayout.add(group.getPayout());
            totalBalance = totalBalance.add(group.getBalance());
            totalTransferIncome = totalTransferIncome.add(group.getTransferIncome());
            totalTransferPayout = totalTransferPayout.add(group.getTransferPayout());
            totalFinanceIncome = totalFinanceIncome.add(group.getFinanceIncome());
            totalFinancePayout = totalFinancePayout.add(group.getFinancePayout());
        }
        result.setTotalPrev(totalPrev.setScale(2, RoundingMode.HALF_UP));
        result.setTotalIncome(totalIncome.setScale(2, RoundingMode.HALF_UP));
        result.setTotalPayout(totalPayout.setScale(2, RoundingMode.HALF_UP));
        result.setTotalBalance(totalBalance.setScale(2, RoundingMode.HALF_UP));
        // 合计勾稽：本日结余 = 上日余额 + 收入 - 支出（transfer/finance 桶已在净额中剔除，天然抵消）
        BigDecimal expected = totalPrev.add(totalIncome).subtract(totalPayout).setScale(2, RoundingMode.HALF_UP);
        BigDecimal diff = totalBalance.subtract(expected).setScale(2, RoundingMode.HALF_UP);
        result.setBalanceCheckDiff(diff);
        result.setBalanceCheckOk(diff.abs().compareTo(new BigDecimal("0.01")) <= 0);
        return result;
    }

    private BigDecimal sum(BigDecimal base, BigDecimal add) {
        return (base == null ? BigDecimal.ZERO : base).add(add == null ? BigDecimal.ZERO : add);
    }
}
