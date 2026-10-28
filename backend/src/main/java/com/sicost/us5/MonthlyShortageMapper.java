package com.sicost.us5;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.us5.MonthlyShortageModels.*;

@Mapper
public interface MonthlyShortageMapper {
    List<Row> months(Query query);
    BigDecimal tolerance(String month);
}
