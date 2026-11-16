package com.vms.reporting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** 20：全部单元格按文本写出；以公式起始字符开头的内容加前缀转义。 */
final class ExcelWorkbook {

    private static final int COLUMN_WIDTH = 20 * 256;

    private ExcelWorkbook() {
    }

    static byte[] textWorkbook(String sheetName, List<String> headers, List<List<String>> rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName);
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            Row header = sheet.createRow(0);
            for (int index = 0; index < headers.size(); index++) {
                Cell cell = header.createCell(index);
                cell.setCellValue(escape(headers.get(index)));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(index, COLUMN_WIDTH);
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                List<String> values = rows.get(rowIndex);
                for (int column = 0; column < values.size(); column++) {
                    row.createCell(column).setCellValue(escape(values.get(column)));
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("导出文件生成失败", e);
        }
    }

    /** 文本单元格本身不执行公式；前缀单引号是导出侧对 =、+、-、@ 的显式转义。 */
    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            return "'" + value;
        }
        return value;
    }
}
