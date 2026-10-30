package com.sicost.people;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.io.*;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.people.PeopleModels.*;

@Service
public class PeopleExportService {
    private final PeopleCostService people;
    private final PeopleMapper mapper;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public PeopleExportService(PeopleCostService people, PeopleMapper mapper, ExcelExportService excel,
            CurrentUser user, PermissionService permissions, Clock clock) {
        this.people = people;
        this.mapper = mapper;
        this.excel = excel;
        this.user = user;
        this.permissions = permissions;
        this.clock = clock;
    }
    @Transactional
    public byte[] download(Query query) {
        permissions.can(CostActions.PEOPLE_DOWNLOAD);
        var result = people.full(query);
        var columns = List.<ExportColumn<PeopleRow>>of(
            new ExportColumn<>("", "", false, false, r -> null),
            new ExportColumn<>("dev_nm", "이름", false, false, PeopleRow::dev_nm),
            new ExportColumn<>("vendor_name", "소속", false, false, r -> Objects.toString(r.vendor_name(), "자사")),
            new ExportColumn<>("pstn_cd", "직위", false, false, PeopleRow::pstn_cd),
            new ExportColumn<>("emp_se_cd", "구분", false, false, PeopleRow::emp_se_cd),
            new ExportColumn<>("project_names", "프로젝트", false, false, r -> String.join(" · ", r.project_names())),
            new ExportColumn<>("period", "기간", false, false, PeopleRow::period),
            new ExportColumn<>("contract_types", "유형", false, false, r -> String.join(" · ", r.contract_types())),
            new ExportColumn<>("mm_qty", "MM", false, true, PeopleRow::mm_qty),
            new ExportColumn<>("bill_untprc_amt", "단가", false, true, PeopleRow::bill_untprc_amt),
            new ExportColumn<>("slry_amt", "급여", true, true, PeopleRow::slry_amt),
            new ExportColumn<>("alwnc_amt", "수당", true, true, PeopleRow::alwnc_amt),
            new ExportColumn<>("sins_cmpny_amt", "보험 회사분", true, true, PeopleRow::sins_cmpny_amt),
            new ExportColumn<>("rtrpay_acml_amt", "퇴직금", true, true, PeopleRow::rtrpay_acml_amt),
            new ExportColumn<>("indv_lbr_cst_amt", "개인원가", true, true, PeopleRow::indv_lbr_cst_amt),
            new ExportColumn<>("team_expns_amt", "팀비", false, true, PeopleRow::team_expns_amt),
            new ExportColumn<>("project_expns_amt", "프로젝트경비", false, true, PeopleRow::project_expns_amt),
            new ExportColumn<>("tot_cst_amt", "총원가", true, true, PeopleRow::tot_cst_amt),
            new ExportColumn<>("prft_amt", "이익", true, true, PeopleRow::prft_amt),
            new ExportColumn<>("mrgn_rt", "이익률", false, true, PeopleRow::mrgn_rt),
            new ExportColumn<>("sins_emp_amt", "보험 본인분", true, true, PeopleRow::sins_emp_amt),
            new ExportColumn<>("whtax_amt", "원천세", true, true, PeopleRow::whtax_amt),
            new ExportColumn<>("real_pay_amt", "실지급액", true, true, PeopleRow::real_pay_amt));
        byte[] bytes = excel.create(query.toString(), user.name(),
            LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))), columns, result.items());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes)); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            var mmStyle = workbook.createCellStyle();
            mmStyle.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            var rateStyle = workbook.createCellStyle();
            rateStyle.setDataFormat(workbook.createDataFormat().getFormat("0.0\"%\""));
            for (int i = 4; i <= sheet.getLastRowNum(); i++) {
                sheet.getRow(i).getCell(8).setCellStyle(mmStyle);
                sheet.getRow(i).getCell(19).setCellStyle(rateStyle);
            }
            sheet.setColumnWidth(0, 3 * 256);
            workbook.write(out);
            mapper.download(new Download("개인원가수익.xlsx", "{\"rows\":" + result.total()
                + ",\"columns\":\"B-W\"}", user.userId()));
            return out.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("엑셀 파일 생성에 실패했습니다.", e); }
    }
}
