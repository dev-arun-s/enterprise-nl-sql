package com.nl2sql.controller;

import com.nl2sql.dto.SqlExecutionResult;
import com.nl2sql.model.QueryHistory;
import com.nl2sql.service.ExcelExportService;
import com.nl2sql.service.QueryHistoryService;
import com.nl2sql.service.SqlExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class QueryHistoryController {

    private final QueryHistoryService queryHistoryService;
    private final SqlExecutionService sqlExecutionService;
    private final ExcelExportService excelExportService;

    /** GET /api/history?schema=HR&page=0&size=20 */
    @GetMapping
    public ResponseEntity<Page<QueryHistory>> getHistory(
            @RequestParam(required = false) String schema,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(queryHistoryService.getHistory(schema, page, size));
    }

    /** GET /api/history/{id}/csv — re-execute and stream as CSV */
    @GetMapping("/{id}/csv")
    public ResponseEntity<byte[]> downloadCsv(@PathVariable Long id) {
        QueryHistory entry = queryHistoryService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("History entry not found: " + id));
        SqlExecutionResult result = sqlExecutionService.execute(entry.getGeneratedSql());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"query_" + id + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(toCsv(result));
    }

    /** GET /api/history/{id}/xlsx — re-execute and stream as Excel */
    @GetMapping("/{id}/xlsx")
    public ResponseEntity<byte[]> downloadXlsx(@PathVariable Long id) {
        QueryHistory entry = queryHistoryService.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("History entry not found: " + id));
        SqlExecutionResult result = sqlExecutionService.execute(entry.getGeneratedSql());
        byte[] xlsx = excelExportService.toXlsx(result, "Query_" + id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"query_" + id + ".xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsx);
    }

    /** DELETE /api/history/{id} */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        queryHistoryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── CSV helper ────────────────────────────────────────────────────────────

    private byte[] toCsv(SqlExecutionResult result) {
        StringBuilder sb = new StringBuilder();
        List<String> cols = result.getColumns();
        if (cols != null) {
            sb.append(String.join(",", cols.stream().map(this::csvEscape).toList())).append("\n");
        }
        if (result.getRows() != null) {
            for (List<Object> row : result.getRows()) {
                sb.append(String.join(",",
                        row.stream().map(v -> v == null ? "" : csvEscape(v.toString())).toList()))
                  .append("\n");
            }
        }
        return sb.toString().getBytes();
    }

    private String csvEscape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
