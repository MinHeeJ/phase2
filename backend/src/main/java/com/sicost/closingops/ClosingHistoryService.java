package com.sicost.closingops;

import com.sicost.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.closingops.ClosingModels.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class ClosingHistoryService {
    private final ClosingMapper mapper;
    public ClosingHistoryService(ClosingMapper mapper) {this.mapper=mapper;}
    public HistoryPage list(int year,int page,int size) {
        if(year<1||year>9999)throw invalid("year");if(page<0)throw invalid("page");if(size<1||size>100)throw invalid("size");
        var q=new HistoryQuery(year,page,size,(long)page*size);
        return new HistoryPage(mapper.history(q).stream().map(h->new History(h.month(),h.action(),timestamp(h.processedAt()),h.actor(),h.reason())).toList(),page,size,mapper.historyCount(q));
    }
    private ApiException invalid(String field){return new ApiException(400,"VALIDATION_ERROR","조회 조건을 확인하세요.",field);}
}
