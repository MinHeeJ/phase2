package com.sicost.common.ai;

import java.net.URI;
import org.springframework.core.env.Environment;

/**
 * 로컬 sLLM(Ollama) 주소와 모델 이름의 한 가지 기준. 분석 해석과 추천 조건 초안이 같은 규칙을 쓴다.
 * 허용 주소: 이 서버(127.0.0.1·localhost·::1), 사설 IP, 점이 없는 내부망 서비스 이름(도커 compose 의 `ollama` 등).
 * 공인 주소·도메인 이름은 거부한다(인증 없는 API 를 외부로 열지 않는다).
 */
public final class AiModelEndpoint {
    private AiModelEndpoint() {}

    public static URI base(Environment environment) {
        try {
            URI u = URI.create(environment.getProperty("AI_OLLAMA_BASE_URL", "http://127.0.0.1:11434"));
            String h = u.getHost();
            if (!"http".equals(u.getScheme()) || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null
                    || !(u.getPath() == null || u.getPath().isEmpty() || u.getPath().equals("/"))) return null;
            return h != null && privateHost(h) ? u : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean privateHost(String h) {
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1")
            || h.matches("10\\.[0-9.]+") || h.matches("192\\.168\\.[0-9.]+") || h.matches("172\\.(1[6-9]|2[0-9]|3[01])\\.[0-9.]+")
            || h.matches("[a-z][a-z0-9-]{0,62}");
    }

    public static String model(Environment environment) {
        String model = environment.getProperty("AI_OLLAMA_MODEL", "").trim();
        return model.matches("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,199}") ? model : null;
    }

    /** Ollama 목록은 태그를 붙여 준다(`qwen3` → `qwen3:latest`). */
    public static boolean sameModel(String configured, String listed) {
        return configured.equals(listed) || (!configured.contains(":") && listed.equals(configured + ":latest"));
    }

    public static boolean enabled(Environment environment) {
        return environment.getProperty("AI_OLLAMA_ENABLED", Boolean.class, false) && model(environment) != null && base(environment) != null;
    }
}
