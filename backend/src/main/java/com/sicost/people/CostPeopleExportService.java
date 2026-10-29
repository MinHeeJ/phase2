package com.sicost.people;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.people.CostPeopleModels.*;

@Service
public class CostPeopleExportService {
    private final CostPeopleService service;
    private final CostPeopleMapper mapper;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final Clock clock;
    private final ObjectMapper json;
    public CostPeopleExportService(CostPeopleService service, CostPeopleMapper mapper,
            ExcelExportService excel, CurrentUser user, Clock clock, ObjectMapper json) {
        this.service = service;
        this.mapper = mapper;
        this.excel = excel;
        this.user = user;
        this.clock = clock;
        this.json = json;
    }
    public static final List<String> TITLES = List.of("이름", "소속", "직위", "직원구분", "프로젝트",
        "프로젝트 기간", "계약유형", "MM", "단가", "월 급여", "그룹장/팀장 수당", "4대보험 회사분",
        "4대보험 본인", "원천세", "퇴직금 적립", "실지급액", "개인별 원가", "팀비", "프로젝트경비",
        "원가합계", "영업이익", "이익률");
    static List<ExportColumn<Row>> columns() {
        var result = new ArrayList<ExportColumn<Row>>();
        result.add(new ExportColumn<>("", "", false, false, r -> ""));
        for (int i = 0; i < TITLES.size(); i++) {
            final int index = i;
            result.add(new ExportColumn<>("B:W-" + i, TITLES.get(i), i >= 9, i >= 7, r -> cell(r, index)));
        }
        return result;
    }
    private static Object cell(Row r, int column) {
        return switch (column) {
            case 0 -> r.dev_nm(); case 1 -> r.affiliation_name(); case 2 -> r.pstn_cd();
            case 3 -> r.emp_se_cd();
            case 4 -> String.join(" / ", r.projects().stream().map(Project::prj_nm).distinct().toList());
            case 5 -> String.join(" / ", r.projects().stream().map(p -> p.bgn_dt() + " ~ " + p.end_dt()).toList());
            case 6 -> r.cntrct_ty_cd(); case 7 -> r.mm_qty(); case 8 -> r.bill_untprc_amt();
            case 9 -> r.slry_amt(); case 10 -> r.alwnc_amt(); case 11 -> r.sins_cmpny_amt();
            case 12 -> r.sins_emp_amt(); case 13 -> r.whtax_amt(); case 14 -> r.rtrpay_acml_amt();
            case 15 -> r.real_pay_amt(); case 16 -> r.indv_lbr_cst_amt(); case 17 -> r.team_expns_amt();
            case 18 -> r.project_expns_amt(); case 19 -> r.total_cost_amt(); case 20 -> r.profit_amt();
            case 21 -> r.margin_rt(); default -> throw new IllegalArgumentException("열이 없습니다.");
        };
    }
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public File export(CostPeopleInput query) {
        var page = service.full(query);
        var now = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        String name = "개인원가수익표_" + query.year() + "_" + now.format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
        String conditions;
        try { conditions = json.writeValueAsString(query); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
        byte[] bytes = excel.create(conditions, user.name(), now, columns(), page.items());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes)); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            var mm = workbook.createCellStyle();
            mm.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            var ratio = workbook.createCellStyle();
            ratio.setDataFormat(workbook.createDataFormat().getFormat("0.0"));
            for (int i = 4; i <= sheet.getLastRowNum(); i++) {
                sheet.getRow(i).getCell(8).setCellStyle(mm);
                sheet.getRow(i).getCell(22).setCellStyle(ratio);
            }
            workbook.write(output);
            bytes = output.toByteArray();
        } catch (IOException ex) { throw new IllegalStateException("엑셀 파일 생성에 실패했습니다.", ex); }
        try {
            mapper.download(new Access(name, json.writeValueAsString(Map.of("filename", name,
                "conditions", query, "columns", TITLES)), user.userId()));
        } catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
        return new File(name, bytes);
    }
}
