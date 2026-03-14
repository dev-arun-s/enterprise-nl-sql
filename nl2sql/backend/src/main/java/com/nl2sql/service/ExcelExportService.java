package com.nl2sql.service;

import com.nl2sql.dto.SqlExecutionResult;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Converts a SqlExecutionResult to an Excel (.xlsx) byte array using Apache POI.
 */
@Service
public class ExcelExportService {

    public byte[] toXlsx(SqlExecutionResult result, String sheetName) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(sheetName.length() > 31 ? sheetName.substring(0, 31) : sheetName);

            // ── Header row styles ────────────────────────────────────────────
            CellStyle headerStyle = wb.createCellStyle();
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            // ── Header row ───────────────────────────────────────────────────
            List<String> columns = result.getColumns();
            if (columns != null && !columns.isEmpty()) {
                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(columns.get(i));
                    cell.setCellStyle(headerStyle);
                }
            }

            // ── Data rows ────────────────────────────────────────────────────
            List<List<Object>> rows = result.getRows();
            if (rows != null) {
                for (int r = 0; r < rows.size(); r++) {
                    Row row = sheet.createRow(r + 1);
                    List<Object> rowData = rows.get(r);
                    for (int c = 0; c < rowData.size(); c++) {
                        Cell cell = row.createCell(c);
                        Object val = rowData.get(c);
                        if (val == null) {
                            cell.setBlank();
                        } else {
                            // Try numeric
                            try {
                                cell.setCellValue(Double.parseDouble(val.toString()));
                            } catch (NumberFormatException e) {
                                cell.setCellValue(val.toString());
                            }
                        }
                    }
                }
            }

            // ── Auto-size columns ────────────────────────────────────────────
            if (columns != null) {
                for (int i = 0; i < columns.size(); i++) {
                    sheet.autoSizeColumn(i);
                    // Cap at 50 chars wide
                    if (sheet.getColumnWidth(i) > 50 * 256) {
                        sheet.setColumnWidth(i, 50 * 256);
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Failed to generate Excel file", e);
        }
    }
}
