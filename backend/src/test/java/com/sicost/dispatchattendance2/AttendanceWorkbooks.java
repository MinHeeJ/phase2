package com.sicost.dispatchattendance2;

import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.mock.web.MockMultipartFile;

final class AttendanceWorkbooks {
    static Map<String,String> values() {
        return new LinkedHashMap<>(Map.of("연월","2026-10","프로젝트ID","1","관리번호","D-100",
            "이름","홍길동","근무일수","10","정규 근무시간","80","초과 근무시간","","해당 월 근무일수","20"));
    }
    static MockMultipartFile file(Map<String,String> values) throws Exception { return file(values,1,false,false); }
    static MockMultipartFile file(Map<String,String> values,int count,boolean missingHeader,boolean formula) throws Exception {
        try (var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("근태");
            var title=sheet.createRow(3);
            var headers=DispatchAttendanceUploadForm.HEADERS;
            for (int i=0;i<headers.size();i++) if (!missingHeader||i!=7) title.createCell(i).setCellValue(headers.get(i));
            for (int r=0;r<count;r++) {
                var row=sheet.createRow(r+5);
                for (int i=0;i<headers.size();i++) row.createCell(i).setCellValue(values.getOrDefault(headers.get(i),""));
                if (formula) row.getCell(4).setCellFormula("1+1");
            }
            book.write(out);
            return new MockMultipartFile("file","근태.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray());
        }
    }
}
