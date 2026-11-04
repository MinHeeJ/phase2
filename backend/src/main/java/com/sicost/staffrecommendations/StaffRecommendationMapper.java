package com.sicost.staffrecommendations;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import static com.sicost.staffrecommendations.StaffRecommendationModels.*;

@Mapper
public interface StaffRecommendationMapper {
    List<Person> people();
    String career(long devId);
    List<String> grades();
    List<String> codes(String group);
    boolean customerExists(long id);
    boolean hasCostEvidence(long devId);
    String syntheticCost(@Param("devId") long devId, @Param("month") String month);
    String fingerprint();
    long nextRequestId();
    long nextResultId(String direction);
    void insertRequest(RequestWrite row);
    void insertA(ResultWrite row);
    void insertB(ResultWrite row);
    Stored request(long id);
    List<Stored> history(HistoryQuery query);
    List<ResultState> states(@Param("requestId") long requestId,@Param("direction") String direction);
    ResultState result(@Param("id") long id,@Param("direction") String direction);
    int transition(Transition change);
    int observed(@Param("id") long assignmentId,@Param("today") LocalDate today);
}
