package dev.elian.vt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * 요청 한 건 안의 시간을 구간별로 나눈다 (E5).
 *
 * <p>"DB 가 느려서" 와 "실행될 차례를 기다려서" 를 구분하려는 것이다.
 * 앱 안 총시간 − DB − 외부 대기 − 락 대기 = 그 밖의 대기 (캐리어·스케줄링 등).
 * 부하 생성기 지연 − 앱 안 총시간 = 앱에 들어오기 전 대기 (Tomcat 큐·네트워크).
 *
 * <p>모두 {@code /stat/reset} 이후 구간값이다. 워밍업이 섞이지 않는다.
 */
@Component
public class Phases {
    private final LongAdder requests = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final LongAdder dbNanos = new LongAdder();
    private final LongAdder channelNanos = new LongAdder();
    private final LongAdder lockWaitNanos = new LongAdder();

    public void total(long n)    { requests.increment(); totalNanos.add(n); }
    public void db(long n)       { dbNanos.add(n); }
    public void channel(long n)  { channelNanos.add(n); }
    public void lockWait(long n) { lockWaitNanos.add(n); }

    public void reset() {
        requests.reset(); totalNanos.reset(); dbNanos.reset(); channelNanos.reset(); lockWaitNanos.reset();
    }

    public Map<String, Object> snapshot() {
        long r = Math.max(1, requests.sum());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appRequests", requests.sum());
        m.put("appTotalMeanMs", totalNanos.sum() / r / 1e6);
        m.put("appDbMeanMs", dbNanos.sum() / r / 1e6);
        m.put("appChannelMeanMs", channelNanos.sum() / r / 1e6);
        m.put("appLockWaitMeanMs", lockWaitNanos.sum() / r / 1e6);
        m.put("appOtherMeanMs", (totalNanos.sum() - dbNanos.sum() - channelNanos.sum() - lockWaitNanos.sum()) / r / 1e6);
        return m;
    }
}
