package com.sicost.billingsops;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.billingsops.BillingModels.*;

@Mapper
@org.springframework.stereotype.Component("billingOperationsMapper")
public interface BillingMapper {
    Project project(long id);
    Long lockProject(long id);
    Bill bill(long id);
    Bill lockBill(long id);
    List<Bill> bills();
    List<Project> gapProjects(String month);
    List<Source> sources(SourceKey key);
    Stage stage(StageKey key);
    BigDecimal vatRate(String month);
    String monthStatus(String month);
    String lastClosed();
    List<DetailLine> details(long id);
    List<Receipt> receipts(long id);
    List<Bill> amendments(long id);
    List<History> history(long id);
    List<Sent> sent(long id);
    Part part(SourceKey key);
    List<CashDate> cash(int year);
    void numberLock(String month);
    String nextNumber(String month);
    Long insertAmendment(AmendmentWrite write);
    void amendmentDetail(long id);
    int state(StateWrite write);
    void audit(Audit audit);
}
