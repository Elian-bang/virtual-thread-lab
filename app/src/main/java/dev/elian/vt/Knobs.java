package dev.elian.vt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 실험 변수. 전부 밖에서 주입한다 — 코드를 고치지 않고 조건만 바꾸기 위해서다.
 *
 * @param model      실행 모델 (M)
 * @param poolSize   플랫폼 스레드 풀 크기. VIRTUAL 에서는 안 쓴다
 * @param channelMs  외부 채널 응답 지연 (ms). I/O 바운드를 만드는 요소
 * @param useSync    작업을 synchronized 블록으로 감쌀지 여부 (S) — H3 검증용
 */
@ConfigurationProperties(prefix = "lab")
public record Knobs(Model model, int poolSize, int channelMs, boolean useSync) {
    public Knobs {
        if (model == null) model = Model.PLATFORM;
        if (poolSize <= 0) poolSize = 200;
        if (channelMs < 0) channelMs = 50;
    }
}
