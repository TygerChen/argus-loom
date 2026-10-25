package com.argus.rag.excel.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ErrorCodes;
import com.argus.rag.common.util.JsonCompat;
import com.argus.rag.excel.entity.ExcelSheet;
import com.argus.rag.excel.entity.ExcelSheetChunk;
import com.argus.rag.excel.entity.ExcelSheetChunkDelta;
import com.argus.rag.excel.mapper.ExcelSheetChunkDeltaMapper;
import com.argus.rag.excel.mapper.ExcelSheetChunkMapper;
import com.argus.rag.excel.mapper.ExcelSheetMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Excel Sheet 与数据分块的读写服务 —— 表格在线编辑的"数据仓库"。
 *
 * <p><b>先懂三个名词，再读代码</b>（这是本类的设计核心，也是 05 篇的底层）：</p>
 * <ul>
 *   <li><b>Sheet（excel_sheet 表）</b>：一张工作表的基本信息（名字、行列数、
 *       合并单元格配置等），不含单元格数据；</li>
 *   <li><b>Chunk（excel_sheet_chunk 表）</b>：把一张大表按 500 行（CHUNK_SIZE）
 *       切成一块块存的"基数快照"。解析导入时生成，之后基本不再改动——
 *       它保证 80 万行的表也能按需加载一小段，而不是整表进内存；</li>
 *   <li><b>Delta（excel_sheet_chunk_delta 表）</b>：每次单元格编辑产生的"增量"——
 *       只记"第 r 行第 c 列的值变成了 v"。编辑从不改写基数，增量越攒越多。</li>
 * </ul>
 *
 * <p><b>读 = 基数 + 增量合并</b>（SheetChunkMerger.merge）：查数据时永远先取
 * Chunk 快照，再把属于它的 Delta 按顺序叠上去，得到"最新事实"。</p>
 *
 * <p><b>为什么这么设计？</b>编辑高频而读取低频（对单格而言）。如果每次编辑
 * 都重写整块 500 行的 JSON：写放大严重、并发冲突面大。只追加增量，写入
 * 成本接近零；代价是读要合并、增量表会变大——所以有
 * {@link #compactSheetDeltas}：增量攒到 500 条就把合并结果"压实"回基数，
 * 删掉全部增量。这本质上就是<b>LSM-Tree / Redis AOF 重写的思想</b>：
 * 顺序追加 + 周期性压实，面试数据库存储引擎时可以直接讲。</p>
 *
 * <p><b>缓存与锁</b>：合并结果按 (documentId, sheetId) 放 Redis 缓存
 * （RedisChunkCacheService），任何写入后立刻失效对应缓存；批量写单元格前
 * 先拿文档级分布式锁（ExcelDocumentLockService），防止两个请求并发写
 * 同一批次产生交错的增量顺序。</p>
 */
@Service
public class ExcelSheetService {

    private final ExcelSheetMapper sheetMapper;
    private final ExcelSheetChunkMapper chunkMapper;
    private final ExcelSheetChunkDeltaMapper deltaMapper;
    private final ExcelDocumentService documentService;
    private final RedisChunkCacheService cacheService;
    private final ExcelDocumentLockService lockService;
    private static final Logger log = LoggerFactory.getLogger(ExcelSheetService.class);

    public ExcelSheetService(ExcelSheetMapper sheetMapper,
                             ExcelSheetChunkMapper chunkMapper,
                             ExcelSheetChunkDeltaMapper deltaMapper,
                             ExcelDocumentService documentService,
                             RedisChunkCacheService cacheService,
                             ExcelDocumentLockService lockService) {
        this.sheetMapper = sheetMapper;
        this.chunkMapper = chunkMapper;
        this.deltaMapper = deltaMapper;
        this.documentService = documentService;
        this.cacheService = cacheService;
        this.lockService = lockService;
    }

    /** 列出文档下的全部工作表（状态 1 = 正常；3 = 已删除的软删标记，见 deleteByDocumentId）。 */
    public List<ExcelSheet> listSheetsByDocumentId(Long documentId) {
        try {
            QueryWrapper<ExcelSheet> qw = new QueryWrapper<>();
            qw.eq("document_id", documentId)
              .eq("status", 1)
              .orderByAsc("sheet_index");
            return sheetMapper.selectList(qw);
        } catch (Exception e) {
            log.error("查询 Sheet 列表失败: documentId={}", documentId, e);
            throw new BusinessException(ErrorCodes.EXCEL_PARSE_ERROR, "查询 Sheet 列表失败");
        }
    }

    /**
     * 读一张表的全部分块（最终一致数据）——"缓存 → 基数 + 增量合并 → 回填缓存"的标准三段：
     * 1. 先查 Redis 缓存，命中直接用；
     * 2. 未命中查库（基数），合并增量后回填缓存（下次就快了）；
     * 3. 返回前一定再合并一次增量——因为写入只失效缓存，数据库里的增量
     *    永远比缓存新，最后一步合并兜底"刚写完马上读"的场景。
     */
    public List<ExcelSheetChunk> listChunksBySheetId(Long sheetId) {
        ExcelSheet sheet = sheetMapper.selectById(sheetId);
        Long documentId = sheet != null ? sheet.getDocumentId() : null;
        List<ExcelSheetChunk> chunks = documentId == null
                ? null
                : cacheService.getSheetChunks(documentId, sheetId);
        if (chunks == null || chunks.isEmpty()) {
            chunks = listRawChunksBySheetId(sheetId);
            if (documentId != null && !chunks.isEmpty()) {
                cacheService.putSheetChunks(documentId, sheetId, chunks);
            }
        }
        if (chunks.isEmpty()) {
            return chunks;
        }
        mergeDeltasInto(chunks);
        return chunks;
    }

    /** 打开整个工作簿时用：一次查全文档的分块，按 sheetId 分组返回（省 N 次往返）。 */
    public Map<Long, List<ExcelSheetChunk>> listChunksByDocumentGrouped(Long documentId) {
        if (documentId == null) {
            return Map.of();
        }
        QueryWrapper<ExcelSheetChunk> qw = new QueryWrapper<>();
        qw.eq("document_id", documentId)
          .orderByAsc("sheet_id")
          .orderByAsc("chunk_index");
        List<ExcelSheetChunk> all = chunkMapper.selectList(qw);
        if (all.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ExcelSheetChunk>> grouped = new LinkedHashMap<>();
        for (ExcelSheetChunk chunk : all) {
            grouped.computeIfAbsent(chunk.getSheetId(), k -> new ArrayList<>()).add(chunk);
        }
        Map<Long, Map<Integer, List<ExcelSheetChunkDelta>>> deltasBySheet = loadDeltasByDocument(documentId);
        Map<Long, List<ExcelSheetChunk>> result = new LinkedHashMap<>();
        for (Map.Entry<Long, List<ExcelSheetChunk>> entry : grouped.entrySet()) {
            List<ExcelSheetChunk> chunks = entry.getValue();
            cacheService.putSheetChunks(documentId, entry.getKey(), chunks);
            mergeDeltasInto(chunks, deltasBySheet.getOrDefault(entry.getKey(), Map.of()));
            result.put(entry.getKey(), chunks);
        }
        return result;
    }

    private List<ExcelSheetChunk> listRawChunksBySheetId(Long sheetId) {
        QueryWrapper<ExcelSheetChunk> qw = new QueryWrapper<>();
        qw.eq("sheet_id", sheetId).orderByAsc("chunk_index");
        return chunkMapper.selectList(qw);
    }

    private Map<Integer, List<ExcelSheetChunkDelta>> loadDeltasBySheet(Long sheetId) {
        QueryWrapper<ExcelSheetChunkDelta> qw = new QueryWrapper<>();
        qw.eq("sheet_id", sheetId);
        Map<Integer, List<ExcelSheetChunkDelta>> byChunk = new HashMap<>();
        for (ExcelSheetChunkDelta delta : deltaMapper.selectList(qw)) {
            byChunk.computeIfAbsent(delta.getChunkIndex(), k -> new ArrayList<>()).add(delta);
        }
        return byChunk;
    }

    private List<ExcelSheetChunkDelta> loadDeltasByChunk(Long sheetId, int chunkIndex) {
        QueryWrapper<ExcelSheetChunkDelta> qw = new QueryWrapper<>();
        qw.eq("sheet_id", sheetId).eq("chunk_index", chunkIndex);
        return deltaMapper.selectList(qw);
    }

    private Map<Long, Map<Integer, List<ExcelSheetChunkDelta>>> loadDeltasByDocument(Long documentId) {
        QueryWrapper<ExcelSheetChunkDelta> qw = new QueryWrapper<>();
        qw.eq("document_id", documentId);
        Map<Long, Map<Integer, List<ExcelSheetChunkDelta>>> bySheet = new HashMap<>();
        for (ExcelSheetChunkDelta delta : deltaMapper.selectList(qw)) {
            bySheet.computeIfAbsent(delta.getSheetId(), k -> new HashMap<>())
                    .computeIfAbsent(delta.getChunkIndex(), k -> new ArrayList<>())
                    .add(delta);
        }
        return bySheet;
    }

    private void mergeDeltasInto(List<ExcelSheetChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        mergeDeltasInto(chunks, loadDeltasBySheet(chunks.get(0).getSheetId()));
    }

    private void mergeDeltasInto(List<ExcelSheetChunk> chunks,
                                 Map<Integer, List<ExcelSheetChunkDelta>> byChunk) {
        if (chunks == null || chunks.isEmpty() || byChunk == null || byChunk.isEmpty()) {
            return;
        }
        for (ExcelSheetChunk chunk : chunks) {
            List<ExcelSheetChunkDelta> deltas = byChunk.getOrDefault(chunk.getChunkIndex(), List.of());
            if (!deltas.isEmpty()) {
                chunk.setCelldataJson(JsonCompat.toJSONString(
                        SheetChunkMerger.merge(chunk.getCelldataJson(), deltas)));
            }
        }
    }

    /** 取单个 Sheet；status=3（已删除）对外表现为"不存在"。 */
    public ExcelSheet getSheetById(Long sheetId) {
        if (sheetId == null) return null;
        ExcelSheet sheet = sheetMapper.selectById(sheetId);
        return sheet != null && Integer.valueOf(3).equals(sheet.getStatus()) ? null : sheet;
    }

    /** 前端滚动到某段行区间时按范围加载：选的是"与区间有交集"的块（le/ge 条件）。 */
    public List<ExcelSheetChunk> getSheetChunksByRange(Long sheetId, int rowStart, int rowEnd) {
        QueryWrapper<ExcelSheetChunk> qw = new QueryWrapper<>();
        qw.eq("sheet_id", sheetId)
                .le("row_start", rowEnd)
                .ge("row_end", rowStart)
                .orderByAsc("chunk_index");
        List<ExcelSheetChunk> chunks = chunkMapper.selectList(qw);
        mergeDeltasInto(chunks);
        return chunks;
    }

    public List<ExcelSheetChunk> loadChunksByIndexRange(Long sheetId, int startChunk, int endChunk) {
        QueryWrapper<ExcelSheetChunk> qw = new QueryWrapper<>();
        qw.eq("sheet_id", sheetId)
                .ge("chunk_index", Math.max(startChunk, 0))
                .le("chunk_index", Math.max(endChunk, startChunk))
                .orderByAsc("chunk_index");
        List<ExcelSheetChunk> chunks = chunkMapper.selectList(qw);
        mergeDeltasInto(chunks);
        return chunks;
    }

    @SuppressWarnings("unchecked")
    /**
     * 取一个单元格的最新值（协同编辑冲突检测时用）。
     * 行号除以 CHUNK_SIZE 直接定位到块，只合并这一个块的增量——
     * 单格读取的成本是 O(块内行数)，与整表大小无关。
     */
    public Map<String, Object> loadCellSnapshot(Long documentId, Long sheetId, int r, int c) {
        ExcelSheet sheet = getSheetById(sheetId);
        if (sheet == null) return null;
        int chunkIndex = r / ExcelParserService.CHUNK_SIZE;
        QueryWrapper<ExcelSheetChunk> qw = new QueryWrapper<>();
        qw.eq("sheet_id", sheetId).eq("chunk_index", chunkIndex);
        ExcelSheetChunk chunk = chunkMapper.selectOne(qw);
        if (chunk == null) {
            return null;
        }
        List<ExcelSheetChunkDelta> deltas = deltaMapper.selectList(
                new QueryWrapper<ExcelSheetChunkDelta>()
                        .eq("sheet_id", sheetId)
                        .eq("chunk_index", chunkIndex));
        List<Object> cells = SheetChunkMerger.merge(chunk.getCelldataJson(), deltas);
        for (Object obj : cells) {
            if (obj instanceof Map) {
                Map<String, Object> cell = (Map<String, Object>) obj;
                int cellR = intValue(cell.get("r"), -1);
                int cellC = intValue(cell.get("c"), -1);
                if (cellR == r && cellC == c) {
                    return cell;
                }
            }
        }
        return null;
    }

    /** 加载 Sheet 全部分块的最终数据（基数 + 增量合并后）。 */
    public List<Object> loadMergedCelldata(Long sheetId) {
        List<Object> all = new ArrayList<>();
        for (ExcelSheetChunk chunk : listChunksBySheetId(sheetId)) {
            if (chunk.getCelldataJson() == null || chunk.getCelldataJson().isEmpty()) {
                continue;
            }
            all.addAll(JsonCompat.parseArray(chunk.getCelldataJson()));
        }
        return all;
    }

    @Transactional(rollbackFor = Exception.class)
    /** 物理删除（区分 deleteByDocumentId 的软删）：分块和增量真删，Sheet 打删除标记。 */
    public void hardDeleteByDocumentId(Long documentId) {
        QueryWrapper<ExcelSheetChunk> chunkQw = new QueryWrapper<>();
        chunkQw.eq("document_id", documentId);
        chunkMapper.delete(chunkQw);
        QueryWrapper<ExcelSheetChunkDelta> deltaQw = new QueryWrapper<>();
        deltaQw.eq("document_id", documentId);
        deltaMapper.delete(deltaQw);
        cacheService.invalidateDocument(documentId);

        ExcelSheet deleted = new ExcelSheet();
        deleted.setStatus(3);
        QueryWrapper<ExcelSheet> sheetQw = new QueryWrapper<>();
        sheetQw.eq("document_id", documentId);
        sheetMapper.update(deleted, sheetQw);
    }

    @Transactional(rollbackFor = Exception.class)
    public ExcelSheet insertSheetWithoutHistory(ExcelSheet sheet) {
        if (sheet.getStatus() == null) sheet.setStatus(1);
        if (sheet.getChunkCount() == null) sheet.setChunkCount(0);
        sheetMapper.insert(sheet);
        return sheet;
    }

    @Transactional(rollbackFor = Exception.class)
    public ExcelSheetChunk insertChunkWithoutHistory(Long documentId, Long sheetId,
                                                      Integer chunkIndex, Integer rowStart,
                                                      Integer rowEnd, String celldataJson) {
        ExcelSheetChunk chunk = new ExcelSheetChunk();
        chunk.setDocumentId(documentId);
        chunk.setSheetId(sheetId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setRowStart(rowStart);
        chunk.setRowEnd(rowEnd);
        chunk.setCelldataJson(celldataJson == null ? "[]" : celldataJson);
        chunkMapper.insert(chunk);
        cacheService.invalidateSheet(documentId, sheetId);
        return chunk;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateChunkCount(Long sheetId, int chunkCount) {
        ExcelSheet update = new ExcelSheet();
        update.setId(sheetId);
        update.setChunkCount(chunkCount);
        sheetMapper.updateById(update);
    }

    @Transactional(rollbackFor = Exception.class)
    public void applyHistoryBatch(Long documentId, List<Map<String, Object>> updates) {
        if (updates == null || updates.isEmpty()) return;
        batchUpdateCells(documentId, updates);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocumentId(Long documentId) {
        ExcelSheet update = new ExcelSheet();
        update.setStatus(3);
        QueryWrapper<ExcelSheet> sheetQw = new QueryWrapper<>();
        sheetQw.eq("document_id", documentId);
        sheetMapper.update(update, sheetQw);

        QueryWrapper<ExcelSheetChunk> chunkQw = new QueryWrapper<>();
        chunkQw.eq("document_id", documentId);
        chunkMapper.delete(chunkQw);
        QueryWrapper<ExcelSheetChunkDelta> deltaQw = new QueryWrapper<>();
        deltaQw.eq("document_id", documentId);
        deltaMapper.delete(deltaQw);
        cacheService.invalidateDocument(documentId);
    }

    @Transactional(rollbackFor = Exception.class)
    /**
     * 批量写单元格（协同编辑保存、历史回放都走这里）。
     * 先拿文档级写锁再写——锁的粒度是整个文档而不是单格，简单可靠：
     * 500 人规模同文档并发写本来就少（05 篇 Q1 的结论）。
     * 注意：锁是 JVM 内 ReentrantLock（ExcelDocumentLockService），只在单实例内互斥；
     * 多实例部署前必须先替换为跨实例锁，不能凭此处注释当作分布式能力。
     */
    public void batchUpdateCells(Long documentId, List<Map<String, Object>> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        lockService.runWithLock(documentId, () -> writeCellsGrouped(documentId, updates));
    }

    @SuppressWarnings("unchecked")
    /**
     * 真正的写入逻辑。按 (sheetId, 目标块号) 把更新分组——一次保存可能涉及
     * 多张表多个块，每块独立处理：块不存在就现场建（在线新建行超出原范围时），
     * 然后把更新存成增量、失效缓存、检查是否需要压实。
     */
    private void writeCellsGrouped(Long documentId, List<Map<String, Object>> updates) {
        Map<String, List<Map<String, Object>>> chunkGroup = new HashMap<>();
        int chunkSize = ExcelParserService.CHUNK_SIZE;

        for (Map<String, Object> update : updates) {
            Long sheetId = Long.parseLong(update.get("sheetId").toString());
            int r = Integer.parseInt(update.get("r").toString());
            int targetChunkIndex = r / chunkSize;
            String key = sheetId + "_" + targetChunkIndex;
            chunkGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(update);
        }

        for (Map.Entry<String, List<Map<String, Object>>> entry : chunkGroup.entrySet()) {
            String[] parts = entry.getKey().split("_");
            Long sheetId = Long.parseLong(parts[0]);
            int targetChunkIndex = Integer.parseInt(parts[1]);

            QueryWrapper<ExcelSheetChunk> qw = new QueryWrapper<>();
            qw.eq("sheet_id", sheetId).eq("chunk_index", targetChunkIndex);
            ExcelSheetChunk chunk = chunkMapper.selectOne(qw);

            if (chunk == null) {
                chunk = new ExcelSheetChunk();
                chunk.setDocumentId(documentId);
                chunk.setSheetId(sheetId);
                chunk.setChunkIndex(targetChunkIndex);
                chunk.setRowStart(targetChunkIndex * chunkSize);
                chunk.setRowEnd((targetChunkIndex + 1) * chunkSize - 1);
                chunk.setCelldataJson("[]");
                chunkMapper.insert(chunk);
                ExcelSheet sheet = sheetMapper.selectById(sheetId);
                if (sheet != null && sheet.getChunkCount() <= targetChunkIndex) {
                    ExcelSheet sheetUpdate = new ExcelSheet();
                    sheetUpdate.setId(sheetId);
                    sheetUpdate.setChunkCount(targetChunkIndex + 1);
                    sheetMapper.updateById(sheetUpdate);
                }
            }
            saveDeltasForChunk(documentId, sheetId, targetChunkIndex, entry.getValue());
            cacheService.invalidateSheet(documentId, sheetId);
            maybeCompactSheetDeltas(sheetId);
        }
    }

    /**
     * 把更新写为增量。同一格重复编辑不追加新行，而是覆盖旧增量的值
     * （existingByCell 按 "行_列" 定位）——增量表存的是"每格最新值"，
     * 中间历史由版本快照（ExcelVersionService）负责，职责分离。
     */
    private void saveDeltasForChunk(Long documentId, Long sheetId, Integer chunkIndex,
                                    List<Map<String, Object>> updates) {
        List<ExcelSheetChunkDelta> existing = loadDeltasByChunk(sheetId, chunkIndex);
        Map<String, ExcelSheetChunkDelta> existingByCell = new HashMap<>();
        for (ExcelSheetChunkDelta delta : existing) {
            existingByCell.put(delta.getCellRow() + "_" + delta.getCellCol(), delta);
        }

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        for (Map<String, Object> update : updates) {
            int r = Integer.parseInt(update.get("r").toString());
            int c = Integer.parseInt(update.get("c").toString());
            Object vObj = update.get("v");
            String valueJson;
            if (vObj == null) {
                valueJson = "{}";
            } else {
                String serialized = JsonCompat.toJSONString(vObj);
                valueJson = (serialized == null || serialized.isEmpty()) ? "{}" : serialized;
            }
            String key = r + "_" + c;
            ExcelSheetChunkDelta delta = existingByCell.get(key);
            if (delta == null) {
                delta = new ExcelSheetChunkDelta();
                delta.setDocumentId(documentId);
                delta.setSheetId(sheetId);
                delta.setChunkIndex(chunkIndex);
                delta.setCellRow(r);
                delta.setCellCol(c);
                delta.setCellValue(valueJson);
                delta.setUpdateTime(now);
                deltaMapper.insert(delta);
            } else {
                delta.setCellValue(valueJson);
                delta.setUpdateTime(now);
                deltaMapper.updateById(delta);
            }
        }
    }

    /** 每次写入后检查：增量满 500 条触发压实（检查是 O(1) 计数，压实才真正干活）。 */
    private void maybeCompactSheetDeltas(Long sheetId) {
        Long count = deltaMapper.selectCount(new QueryWrapper<ExcelSheetChunkDelta>()
                .eq("sheet_id", sheetId));
        if (count != null && count >= 500) {
            compactSheetDeltas(sheetId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    /**
     * 压实（compaction）：把全部增量合并进基数快照，然后删光增量。
     * 这是 LSM 思想的"压实"一步——读越来越慢（要叠的增量越来越多）时，
     * 用一次重写换来之后干净的读。事务保证"改基数 + 删增量"要么都成要么都不成。
     */
    public void compactSheetDeltas(Long sheetId) {
        QueryWrapper<ExcelSheetChunkDelta> qw = new QueryWrapper<>();
        qw.eq("sheet_id", sheetId);
        List<ExcelSheetChunkDelta> deltas = deltaMapper.selectList(qw);
        if (deltas.isEmpty()) {
            return;
        }
        Map<Integer, List<ExcelSheetChunkDelta>> byChunk = new LinkedHashMap<>();
        for (ExcelSheetChunkDelta delta : deltas) {
            byChunk.computeIfAbsent(delta.getChunkIndex(), k -> new ArrayList<>()).add(delta);
        }
        for (ExcelSheetChunk chunk : listRawChunksBySheetId(sheetId)) {
            List<ExcelSheetChunkDelta> chunkDeltas = byChunk.get(chunk.getChunkIndex());
            if (chunkDeltas == null || chunkDeltas.isEmpty()) {
                continue;
            }
            chunk.setCelldataJson(JsonCompat.toJSONString(
                    SheetChunkMerger.merge(chunk.getCelldataJson(), chunkDeltas)));
            chunkMapper.updateById(chunk);
        }
        deltaMapper.delete(qw);
        for (ExcelSheetChunkDelta delta : deltas) {
            cacheService.invalidateSheet(delta.getDocumentId(), delta.getSheetId());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    /**
     * 整簿替换：前端把 Univer/LuckySheet 格式的完整工作簿 JSON 发上来时使用
     * （比如从外部导入）。旧 Sheet 全部软删、分块增量清空，然后逐张新建：
     * 先存 Sheet 元信息（合并/列宽/行宽/超链接/图片/条件格式/图表各自一列 JSON），
     * 再把单元格数据切块存储。返回"前端 sheet 索引 → 数据库 sheetId"的映射，
     * 供调用方继续关联操作。整个过程在一个事务里，失败整体回滚。
     */
    public Map<Integer, Long> replaceWorkbook(Long documentId, List<Map<String, Object>> workbookSheets) {
        if (workbookSheets == null || workbookSheets.isEmpty()) {
            throw new BusinessException(ErrorCodes.EXCEL_PARSE_ERROR, "workbook sheets must not be empty");
        }

        QueryWrapper<ExcelSheetChunk> chunkQw = new QueryWrapper<>();
        chunkQw.eq("document_id", documentId);
        chunkMapper.delete(chunkQw);
        QueryWrapper<ExcelSheetChunkDelta> deltaQw = new QueryWrapper<>();
        deltaQw.eq("document_id", documentId);
        deltaMapper.delete(deltaQw);
        cacheService.invalidateDocument(documentId);

        ExcelSheet deleted = new ExcelSheet();
        deleted.setStatus(3);
        QueryWrapper<ExcelSheet> sheetQw = new QueryWrapper<>();
        sheetQw.eq("document_id", documentId);
        sheetMapper.update(deleted, sheetQw);

        List<String> sheetNames = new ArrayList<>();
        int visibleIndex = 0;
        Map<Integer, Long> sheetIdMapping = new LinkedHashMap<>();

        for (Map<String, Object> rawSheet : workbookSheets) {
            if (rawSheet == null) continue;

            String name = stringValue(rawSheet.get("name"), "Sheet" + (visibleIndex + 1));
            sheetNames.add(name);

            Map<String, Object> config = toJsonObject(rawSheet.get("config"));
            Map<String, Object> hyperlink = toJsonObject(rawSheet.get("hyperlink"));
            Map<String, Object> images = toJsonObject(rawSheet.get("images"));
            List<Object> conditionFormat = toJsonArray(rawSheet.get("luckysheet_conditionformat_save"));
            List<Object> chart = toJsonArray(rawSheet.get("chart"));

            Map<String, Object> merge = getJsonSubObject(config, "merge");
            Map<String, Object> columnLen = getJsonSubObject(config, "columnlen");
            Map<String, Object> rowLen = getJsonSubObject(config, "rowlen");

            config.put("merge", merge);
            config.put("columnlen", columnLen);
            config.put("rowlen", rowLen);

            List<Object> celldata = resolveCelldata(rawSheet);
            int totalRows = intValue(rawSheet.get("row"), calcTotalRows(rawSheet.get("data"), celldata));
            int totalCols = intValue(rawSheet.get("column"), calcTotalCols(rawSheet.get("data"), celldata));

            ExcelSheet sheet = new ExcelSheet();
            sheet.setDocumentId(documentId);
            sheet.setSheetIndex(visibleIndex);
            sheet.setSheetName(name);
            sheet.setTotalRows(Math.max(totalRows, 1));
            sheet.setTotalCols(Math.max(totalCols, 1));
            sheet.setChunkCount(0);
            sheet.setMergeConfigJson(JsonCompat.toJSONString(merge));
            sheet.setColumnLenJson(JsonCompat.toJSONString(columnLen));
            sheet.setRowLenJson(JsonCompat.toJSONString(rowLen));
            sheet.setConfigJson(JsonCompat.toJSONString(config));
            sheet.setHyperlinkConfigJson(JsonCompat.toJSONString(hyperlink));
            sheet.setImagesConfigJson(JsonCompat.toJSONString(images));
            sheet.setConditionFormatJson(JsonCompat.toJSONString(conditionFormat));
            sheet.setChartJson(JsonCompat.toJSONString(chart));
            sheet.setActive(intValue(rawSheet.get("status"), visibleIndex == 0 ? 1 : 0));
            sheet.setStatus(1);
            sheetMapper.insert(sheet);

            sheetIdMapping.put(visibleIndex, sheet.getId());

            int chunkCount = saveCelldataChunks(documentId, sheet.getId(), celldata, sheet.getTotalRows());
            ExcelSheet update = new ExcelSheet();
            update.setId(sheet.getId());
            update.setChunkCount(chunkCount);
            sheetMapper.updateById(update);

            visibleIndex++;
        }

        if (sheetNames.isEmpty()) {
            throw new BusinessException(ErrorCodes.EXCEL_PARSE_ERROR, "workbook contains no valid sheets");
        }

        documentService.updateSheetMeta(documentId, sheetNames.size(), JsonCompat.toJSONString(sheetNames));
        return sheetIdMapping;
    }

    /**
     * 把单元格集合按行切块入库。返回块数。
     * 注意空表的兜底：没有任何单元格时也要按总行数返回"至少 1 块"的账面数字，
     * 让 Sheet 的 chunkCount 语义始终成立。
     */
    private int saveCelldataChunks(Long documentId, Long sheetId, List<Object> celldata, int totalRows) {
        Map<Integer, List<Object>> chunks = new LinkedHashMap<>();
        int chunkSize = ExcelParserService.CHUNK_SIZE;

        for (Object obj : celldata) {
            if (!(obj instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> cell = (Map<String, Object>) obj;
            int row = intValue(cell.get("r"), 0);
            int chunkIndex = row / chunkSize;
            chunks.computeIfAbsent(chunkIndex, key -> new ArrayList<>()).add(cell);
        }

        if (chunks.isEmpty()) {
            return Math.max(1, (int) Math.ceil(Math.max(totalRows, 1) / (double) chunkSize));
        }

        for (Map.Entry<Integer, List<Object>> entry : chunks.entrySet()) {
            int chunkIndex = entry.getKey();
            ExcelSheetChunk chunk = new ExcelSheetChunk();
            chunk.setDocumentId(documentId);
            chunk.setSheetId(sheetId);
            chunk.setChunkIndex(chunkIndex);
            chunk.setRowStart(chunkIndex * chunkSize);
            chunk.setRowEnd((chunkIndex + 1) * chunkSize - 1);
            chunk.setCelldataJson(JsonCompat.toJSONString(entry.getValue()));
            chunkMapper.insert(chunk);
        }

        return chunks.keySet().stream().max(Integer::compareTo).orElse(0) + 1;
    }

    private List<Object> resolveCelldata(Map<String, Object> rawSheet) {
        Object celldataObj = rawSheet.get("celldata");
        List<Object> celldata = toJsonArray(celldataObj);
        if (!celldata.isEmpty()) return normalizeCelldata(celldata);
        return dataMatrixToCelldata(rawSheet.get("data"));
    }

    @SuppressWarnings("unchecked")
    /**
     * 以下一组 static 小工具：处理前端发来的两种数据形态。
     * LuckySheet/Univer 的表格 JSON 有两种风格：celldata 稀疏列表
     * （[{r,c,v}...]，只存有值的格）或 data 二维矩阵（[[]...]，空格子也是 null）。
     * 统一转成稀疏列表再入库——稀疏存储对大表格（十几万格里只有几万格有值）
     * 省一个数量级的空间。
     */
    static List<Object> normalizeCelldata(List<Object> celldata) {
        List<Object> normalized = new ArrayList<>();
        for (Object obj : celldata) {
            if (!(obj instanceof Map)) continue;
            Map<String, Object> cell = (Map<String, Object>) obj;
            if (!cell.containsKey("r") || !cell.containsKey("c")) continue;
            Object value = cell.get("v");
            if (isEmptyCellValue(value)) continue;
            normalized.add(cell);
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    static List<Object> dataMatrixToCelldata(Object dataObj) {
        List<Object> rows = toJsonArray(dataObj);
        List<Object> celldata = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            List<Object> row = toJsonArray(rows.get(r));
            for (int c = 0; c < row.size(); c++) {
                Object value = row.get(c);
                if (isEmptyCellValue(value)) continue;
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("r", r);
                cell.put("c", c);
                cell.put("v", toJsonObject(value));
                celldata.add(cell);
            }
        }
        return celldata;
    }

    static boolean isEmptyCellValue(Object value) {
        if (value == null) return true;
        if (value instanceof Map) return ((Map<?, ?>) value).isEmpty();
        return false;
    }

    @SuppressWarnings("unchecked")
    static int calcTotalRows(Object dataObj, List<Object> celldata) {
        List<Object> rows = toJsonArray(dataObj);
        int max = rows.size();
        for (Object obj : celldata) {
            if (obj instanceof Map) {
                max = Math.max(max, intValue(((Map<String, Object>) obj).get("r"), 0) + 1);
            }
        }
        return max;
    }

    @SuppressWarnings("unchecked")
    static int calcTotalCols(Object dataObj, List<Object> celldata) {
        List<Object> rows = toJsonArray(dataObj);
        int max = 0;
        for (Object rowObj : rows) {
            max = Math.max(max, toJsonArray(rowObj).size());
        }
        for (Object obj : celldata) {
            if (obj instanceof Map) {
                max = Math.max(max, intValue(((Map<String, Object>) obj).get("c"), 0) + 1);
            }
        }
        return max;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toJsonObject(Object value) {
        if (value == null) return new LinkedHashMap<>();
        if (value instanceof Map) return (Map<String, Object>) value;
        return JsonCompat.parseObject(JsonCompat.toJSONString(value));
    }

    static List<Object> toJsonArray(Object value) {
        if (value == null) return new ArrayList<>();
        if (value instanceof List) return (List<Object>) value;
        return JsonCompat.parseArray(JsonCompat.toJSONString(value));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> getJsonSubObject(Map<String, Object> map, String key) {
        if (map == null) return new LinkedHashMap<>();
        Object v = map.get(key);
        if (v instanceof Map) return (Map<String, Object>) v;
        return new LinkedHashMap<>();
    }

    static int intValue(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(value.toString()); }
        catch (Exception ignored) { return defaultValue; }
    }

    static String stringValue(Object value, String defaultValue) {
        if (value == null) return defaultValue;
        String text = value.toString();
        return text.trim().isEmpty() ? defaultValue : text;
    }
}
