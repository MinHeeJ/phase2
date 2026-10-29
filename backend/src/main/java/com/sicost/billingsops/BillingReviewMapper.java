package com.sicost.billingsops;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import com.sicost.common.billing.BillingSupportModels.ProjectMonth;
import static com.sicost.billingsops.BillingReviewModels.*;

@Mapper
public interface BillingReviewMapper {
    Bill bill(long id);
    Header header(long id);
    List<Line> details(long id);
    List<Receipt> receipts(long id);
    List<Bill> amendments(long id);
    List<History> history(long id);
    long confirmedAmendments(long id);
    long confirmedAdjustmentSupply(long id);
    long insertAmendment(AmendmentWrite write);
    void amendmentDetail(AmendmentDetail write);
    int transition(Transition write);
    Project project(long id);
    Stage stage(long id);
    List<Source> sources(PreviewKey key);
    List<Project> gapProjects(String month);
    List<Long> confirmedIds(ProjectMonth key);
    String lastClosed();
    List<Bill> originals(YearRange range);
    Part historicalPart(ProjectMonth key);
    long plannedSupply(YearRange range);
    long draftSupply(YearRange range);
    void access(ReviewDownload write);
    record ReviewDownload(String filename, String description, Long actor) {}
    record AmendmentDetail(long id, String month, long amount, String reason) {}
}
