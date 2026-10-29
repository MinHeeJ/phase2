package com.sicost.parts;

import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.time.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.parts.PartSettlementModels.*;

@Service
public class PartSettlementExportService {
    private final PartSettlementService settlements;
    private final PartSettlementMapper mapper;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final Clock clock;
    public PartSettlementExportService(PartSettlementService settlements, PartSettlementMapper mapper,
            ExcelExportService excel, CurrentUser user, Clock clock) {
        this.settlements = settlements;
        this.mapper = mapper;
        this.excel = excel;
        this.user = user;
        this.clock = clock;
    }
    @Sensitive
    @Transactional
    public byte[] download(String month) {
        SettlementList list = settlements.list(month);
        List<ExportColumn<PartRow>> columns = List.of(
            new ExportColumn<>("bsn_part_nm", "파트", false, false, PartRow::bsn_part_nm),
            new ExportColumn<>("stlm_ym", "정산월", false, false, r -> r.settlement().stlm_ym()),
            new ExportColumn<>("status", "상태", false, false, r -> r.provisional() ? "잠정" : "확정"),
            new ExportColumn<>("ldr_nm", "파트장", false, false, r -> r.settlement().ldr_nm()),
            new ExportColumn<>("prft_dstrb_rt", "배분율(%)", false, true, r -> r.settlement().prft_dstrb_rt()),
            new ExportColumn<>("psn_cnt", "인원", false, true, r -> r.settlement().psn_cnt()),
            new ExportColumn<>("tot_sls_amt", "월 단가 합계", true, true, r -> r.settlement().tot_sls_amt()),
            new ExportColumn<>("tot_exp_amt", "비용 합계", true, true, r -> r.settlement().tot_exp_amt()),
            new ExportColumn<>("tot_diff_amt", "차액", true, true, r -> r.settlement().tot_diff_amt()),
            new ExportColumn<>("tot_psn_prft_amt", "인력별 이익금 합계", true, true, r -> r.settlement().tot_psn_prft_amt()),
            new ExportColumn<>("corp_card_amt", "법인카드", true, true, r -> r.settlement().corp_card_amt()),
            new ExportColumn<>("part_prft_amt", "파트 이익금", true, true, r -> r.settlement().part_prft_amt()),
            new ExportColumn<>("rmrk_desc", "비고", false, false, r -> r.settlement().rmrk_desc()));
        byte[] bytes = excel.create("month=" + list.calculation_month() + ";view=MONTH;전체 파트;단위=원",
            user.name(), LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))), columns, list.items());
        mapper.access(new Access("DOWNLOAD", "파트정산.xlsx", "{\"columns\":\"monthly-all-parts\",\"rows\":"
            + list.items().size() + "}", user.userId()));
        return bytes;
    }
}
