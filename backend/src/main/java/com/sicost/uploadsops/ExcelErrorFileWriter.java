package com.sicost.uploadsops;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import static com.sicost.uploadsops.UploadModels.*;

public final class ExcelErrorFileWriter {
    private ExcelErrorFileWriter() {}
    public static byte[] write(Path path,List<Issue> issues) {
        try(var in=Files.newInputStream(path);var book=new XSSFWorkbook(in);var out=new ByteArrayOutputStream()) {
            while(book.getNumberOfSheets()>1)book.removeSheetAt(1);
            Sheet sheet=book.getSheetAt(0);int h=UploadForm.header(sheet);Map<Integer,Integer> columns=UploadForm.columns(sheet,h);
            int reason=0;for(Row r:sheet)reason=Math.max(reason,r.getLastCellNum());
            sheet.getRow(h).createCell(reason).setCellValue("오류 사유");
            Map<Integer,List<Issue>> byRow=new LinkedHashMap<>();for(Issue i:issues)byRow.computeIfAbsent(i.rowNo(),k->new ArrayList<>()).add(i);
            Map<Short,CellStyle> styles=new HashMap<>();
            for(var entry:byRow.entrySet()) {
                Row row=sheet.getRow(entry.getKey()-1);if(row==null)continue;
                row.createCell(reason).setCellValue(String.join(" / ",entry.getValue().stream().map(Issue::message).toList()));
                for(Issue issue:entry.getValue())if(issue.severity().equals("ERROR")) {
                    Integer c=columns.get(UploadForm.column(issue.column()));if(c==null)continue;
                    Cell cell=row.getCell(c,Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    CellStyle original=cell.getCellStyle();CellStyle style=styles.computeIfAbsent(original.getIndex(),k->{CellStyle s=book.createCellStyle();s.cloneStyleFrom(original);s.setFillForegroundColor(IndexedColors.ROSE.getIndex());s.setFillPattern(FillPatternType.SOLID_FOREGROUND);return s;});cell.setCellStyle(style);
                }
            }
            Sheet list=book.createSheet("오류 목록");String[] titles={"구분","행","열","입력값","이유"};Row header=list.createRow(0);for(int c=0;c<5;c++)header.createCell(c).setCellValue(titles[c]);
            int n=1;for(Issue i:issues){Row row=list.createRow(n++);row.createCell(0).setCellValue(i.severity());row.createCell(1).setCellValue(i.rowNo());row.createCell(2).setCellValue(i.column());row.createCell(3).setCellValue(i.rawValue()==null?"":i.rawValue());row.createCell(4).setCellValue(i.message());}
            book.write(out);return out.toByteArray();
        } catch(IOException ex){throw new UncheckedIOException(ex);}
    }
}
