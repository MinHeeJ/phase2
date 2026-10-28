package com.sicost.billingsops;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Component;
import static com.sicost.billingsops.BillingModels.*;

@Mapper
@Component("billingOperationsMapper")
public interface BillingMapper {
    Bill bill(long id);
    Bill lockBill(long id);
    Long lockProject(long id);
    void numberLock(String prefix);
    long nextNumber(String prefix);
    int status(StatusWrite write);
    Long amendment(AmendmentWrite write);
    void amendmentDetail(AmendmentDetail write);
    void audit(Audit audit);
    List<String> closedMonths();
    Balance balance(long id);
    List<Bill> bills();
    List<DetailLine> details(long id);
    List<Receipt> receipts(long id);
    List<ChangeHistory> history(long id);
    Project project(long id);
    Stage stage(long id);
    List<Stage> virtualStages(int year);
    List<Source> sources(SourceKey key);
    BigDecimal vat(String month);
    List<Project> gapProjects(String month);
    Part part(PartKey key);
    List<Revenue> revenue(int year);
    List<Cash> cash(int year);
    long virtualPlanned(int year);
    void downloadAccess(String description);
}
