package com.sicost.us1;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.CurrentUser;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.us1.WithdrawalModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class WithdrawalExportService {
    private final WithdrawalService service;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final Clock clock;
    public WithdrawalExportService(WithdrawalService service, ExcelExportService excel, CurrentUser user, Clock clock) {
        this.service = service;
        this.excel = excel;
        this.user = user;
        this.clock = clock;
    }
    public ExportFile export(Map<String, String> values) {
        var query = WithdrawalInput.parse(values, true);
        var snapshot = service.snapshot(query);
        var columns = List.<ExportColumn<Row>>of(
            new ExportColumn<>("dev_nm", "이름", false, false, r -> r.source().dev_nm()),
            new ExportColumn<>("mng_no", "관리번호", false, false, r -> r.source().mng_no()),
            new ExportColumn<>("asgn_no", "투입ID", false, false, r -> r.source().asgn_no()),
            new ExportColumn<>("prj_nm", "프로젝트", false, false, r -> r.source().prj_nm()),
            new ExportColumn<>("job_nm", "직무", false, false, r -> r.source().job_nm()),
            new ExportColumn<>("end_dt", "철수일", false, false, r -> r.source().end_dt()),
            new ExportColumn<>("plan_end_dt", "처음 계획", false, false, r -> r.source().plan_end_dt()),
            new ExportColumn<>("remaining_days", "남은 일수", false, false,
                r -> r.remaining_days() < 0 ? "철수 완료" : "D-" + r.remaining_days()),
            new ExportColumn<>("history", "연장·조기 철수", false, false, WithdrawalExportService::history),
            new ExportColumn<>("dispatch_expiry_dt", "파견 만료일", false, false, Row::dispatch_expiry_dt),
            new ExportColumn<>("ext_psblty_nm", "연장 가능성", false, false, r -> r.source().ext_psblty_nm()),
            new ExportColumn<>("next_display", "다음 배정", false, false, Row::next_display));
        byte[] bytes = excel.create(values.toString(), user.name(), LocalDateTime.now(clock), columns, snapshot.rows());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes)); var output = new ByteArrayOutputStream()) {
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            for (int i = 0; i < snapshot.rows().size(); i++) {
                var source = snapshot.rows().get(i);
                for (int column : List.of(5, 6, 9)) {
                    LocalDate date = column == 5 ? source.source().end_dt()
                        : column == 6 ? source.source().plan_end_dt() : source.dispatch_expiry_dt();
                    if (date == null) continue;
                    var cell = workbook.getSheetAt(0).getRow(i + 4).getCell(column);
                    cell.setCellValue(date.atStartOfDay());
                    cell.setCellStyle(dateStyle);
                }
            }
            workbook.write(output);
            return new ExportFile("철수리스트_" + query.year() + "_"
                + snapshot.today().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx", output.toByteArray());
        } catch (IOException e) { throw new IllegalStateException("엑셀 파일 생성에 실패했습니다.", e); }
    }
    public static String history(Row row) {
        var s = row.source();
        var parts = new ArrayList<String>();
        if (s.extend_count() > 0) parts.add("연장 " + s.extend_count() + "회");
        if (s.early_end()) parts.add("조기 철수 이력");
        if ("REPLACED".equals(s.asgn_stts_cd())) parts.add("교체 → " + Objects.toString(s.replacement_name(), "–"));
        return parts.isEmpty() ? "–" : String.join(" · ", parts);
    }
}
