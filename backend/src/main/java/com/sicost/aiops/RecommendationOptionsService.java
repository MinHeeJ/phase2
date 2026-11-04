package com.sicost.aiops;

import com.sicost.common.ai.*;
import com.sicost.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.sicost.aiops.RecommendationOptionsModels.*;

@Service
public class RecommendationOptionsService {
    private static final String ATTRIBUTE = RecommendationOptionsService.class.getName()+".references";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final RecommendationOptionsMapper mapper;
    private final AiAccessGuard access;
    private final AiOwnerSession owners;
    private final AiSourceReads reads;
    private final Clock clock;
    public RecommendationOptionsService(RecommendationOptionsMapper mapper, AiAccessGuard access,
            AiOwnerSession owners, AiSourceReads reads, Clock clock) {
        this.mapper=mapper; this.access=access; this.owners=owners; this.reads=reads; this.clock=clock;
    }
    private static final class References implements Serializable {
        private static final long serialVersionUID=1L;
        private final String owner;
        private final Map<Long,SourceReference> byHandle=new HashMap<>();
        private final Map<SourceReference,Long> bySource=new HashMap<>();
        private final Map<Long,String> stamps=new HashMap<>();
        private References(String owner) { this.owner=owner; }
        @Override public String toString() { return "RecommendationReferences[redacted]"; }
    }
    public Options get(HttpServletRequest request, Map<String,String> values) {
        access.requireAction(AiActions.VIEW_RECOMMENDATIONS);
        Query query=RecommendationOptionsInput.parse(values);
        String owner=owners.ownerHash(request);
        return reads.required(() -> read(request,owner,query)).value();
    }
    private Options read(HttpServletRequest request, String owner, Query query) {
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        String month=YearMonth.from(today).toString();
        if(query.devId()!=null && !mapper.developerExists(query.devId()))
            throw ApiException.notFound("개발자가 없습니다.");
        List<SourceRow> sources=mapper.sources(month,query.sourceId()==null ? query.search() : null);
        var options=new ArrayList<SourceOption>();
        HttpSession session=request.getSession();
        synchronized(session) {
            References references=references(session,owner);
            SourceReference selected=query.sourceId()==null ? null : references.byHandle.get(query.sourceId());
            if(query.sourceId()!=null && selected==null) throw ApiException.notFound("원천 참조가 없거나 만료되었습니다.");
            if(selected!=null && sources.stream().noneMatch(s -> reference(s).equals(selected))) throw stale();
            for(var source:sources) {
                SourceReference ref=reference(source);
                if(selected!=null && !ref.equals(selected)) continue;
                List<String> skills=mapper.sourceSkills(ref);
                String stamp=stamp(source,skills);
                Long handle=references.bySource.get(ref);
                if(selected!=null && !stamp.equals(references.stamps.get(handle))) throw stale();
                if(handle==null) {
                    do { handle=RANDOM.nextLong() & ((1L<<53)-1); } while(handle==0 || references.byHandle.containsKey(handle));
                    references.bySource.put(ref,handle); references.byHandle.put(handle,ref);
                }
                references.stamps.put(handle,stamp);
                var criteria=new Criteria(source.jobCode(),source.startYm(),source.gradeCode(),skills,
                    source.customerId(),source.industryCode(),source.durationMonths(),source.remainingHeadcount(),source.requestedMm());
                options.add(new SourceOption(handle,source.sourceType(),source.projectId(),source.needId(),criteria,source.remainingHeadcount()));
            }
        }
        List<ExitOption> developers=mapper.developers(new ExitQuery(today,YearMonth.from(today).plusMonths(2).atEndOfMonth(),query.devId(),query.search()));
        if(query.devId()!=null && developers.isEmpty()) throw ApiException.notFound("현재 추천 대상 개발자가 없습니다.");
        owners.assertCurrent(request,owner);
        return new Options("PERSON_TO_POSITION".equals(query.direction()) ? Collections.emptyList() : options,
            "PROJECT_TO_PERSON".equals(query.direction()) ? Collections.emptyList() : developers,
            mapper.codes("JOB"),mapper.codes("GRADE"),mapper.codes("SKILL"),YearMonth.from(today).plusMonths(1).toString());
    }
    /** Search callers resolve the opaque options handle here, then use the returned physical slot identity. */
    public SourceRow resolveCurrentSource(HttpServletRequest request, long sourceId) {
        access.requireAction(AiActions.VIEW_RECOMMENDATIONS);
        String owner=owners.ownerHash(request);
        return reads.required(() -> {
            HttpSession session=request.getSession();
            synchronized(session) {
                References state=references(session,owner);
                SourceReference ref=state.byHandle.get(sourceId);
                if(ref==null) throw ApiException.notFound("원천 참조가 없거나 만료되었습니다.");
                String month=YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString();
                SourceRow row=mapper.sources(month,null).stream().filter(s -> reference(s).equals(ref)).findFirst().orElseThrow(RecommendationOptionsService::stale);
                if(!stamp(row,mapper.sourceSkills(ref)).equals(state.stamps.get(sourceId))) throw stale();
                owners.assertCurrent(request,owner);
                return row;
            }
        }).value();
    }
    private static SourceReference reference(SourceRow row) { return new SourceReference(row.sourceType(),row.rowId()); }
    private static References references(HttpSession session,String owner) {
        Object value=session.getAttribute(ATTRIBUTE);
        if(value instanceof References refs && refs.owner.equals(owner)) return refs;
        var refs=new References(owner); session.setAttribute(ATTRIBUTE,refs); return refs;
    }
    private static String stamp(SourceRow row,List<String> skills) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((row.toString()+skills).getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static ApiException stale() { return ApiException.conflict("CONFLICT","추천 원천이 변경되었습니다. 조건을 다시 조회하세요.","sourceId"); }
}
