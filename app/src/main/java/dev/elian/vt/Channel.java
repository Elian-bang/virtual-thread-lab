package dev.elian.vt;

import org.springframework.stereotype.Component;

/**
 * 외부 채널사 API 를 대신한다.
 *
 * <p>실제 채널사는 응답 시간이 들쭉날쭉하다. 여기서는 <b>고정 지연</b>을 준다.
 * 변동을 없애야 모델 간 차이가 노이즈에 묻히지 않는다.
 *
 * <p>{@code Thread.sleep} 은 Java 21 에서 가상 스레드를 <b>park</b> 시킨다 —
 * 캐리어 스레드를 놓아준다. 이것이 이 실험이 성립하는 전제다.
 */
@Component
public class Channel {
    public void send(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
