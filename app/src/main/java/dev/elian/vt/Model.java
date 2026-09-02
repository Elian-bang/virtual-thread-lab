package dev.elian.vt;

/**
 * 실행 모델. 이것 하나만 바꿔가며 잰다.
 *
 * <p>세 모델 모두 <b>같은 작업</b>을 한다 — 조회 → 외부 호출 → 기록.
 * 다른 것은 그 작업을 무엇이 실어 나르느냐 뿐이다.
 */
public enum Model {
    /** 고정 크기 플랫폼 스레드 풀. 스레드를 늘려서 동시성을 얻는 쪽. */
    PLATFORM,
    /** CompletableFuture + 플랫폼 풀. 비동기지만 결국 같은 풀 위에서 돈다. */
    CF,
    /** Virtual Thread. 요청당 하나씩 만든다. 스레드를 늘리지 않는 쪽. */
    VIRTUAL
}
