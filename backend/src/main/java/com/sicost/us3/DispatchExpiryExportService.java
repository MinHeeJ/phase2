package com.sicost.us3;

import java.io.*;
import java.util.List;
import java.util.function.ToLongFunction;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class DispatchExpiryExportService {
    public <T> byte[] formatDates(byte[] workbook, List<T> exportedRows, ToLongFunction<T> assignmentId,
            DispatchExpiryService.Snapshot snapshot, int column) {
        if (column < 0) throw new IllegalArgumentException("파견 만료일 열이 없습니다.");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(workbook));
                var output = new ByteArrayOutputStream()) {
            var sheet = book.getSheetAt(0);
            var style = book.createCellStyle();
            style.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd"));
            for (int i = 0; i < exportedRows.size(); i++) {
                var row = sheet.getRow(i + 4);
                var cell = row.getCell(column);
                if (cell == null) cell = row.createCell(column);
                var display = snapshot.assignments().get(assignmentId.applyAsLong(exportedRows.get(i)));
                if (display == null) throw new IllegalArgumentException("파견 만료 조회 결과가 없습니다.");
                cell.setBlank();
                if (display.dispatch_expiry_dt() != null) {
                    cell.setCellType(CellType.NUMERIC);
                    cell.setCellValue(display.dispatch_expiry_dt().atStartOfDay());
                    cell.setCellStyle(style);
                }
            }
            book.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("파견 만료일 엑셀 생성에 실패했습니다.", ex);
        }
    }
}
