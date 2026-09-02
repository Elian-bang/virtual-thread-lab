package dev.elian.vt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 발송 1건. 실제 작업과 같은 모양이다.
 *
 * <pre>
 *   ① DB 조회      ← 커넥션 점유
 *   ② 외부 호출    ← I/O 대기 (대부분의 시간)
 *   ③ DB 기록      ← 커넥션 점유
 * </pre>
 *
 * <p><b>①③ 이 커넥션을 잡고 ② 가 오래 기다린다.</b>
 * 그래서 스레드만 늘리고 커넥션을 그대로 두면 ①③ 에서 줄을 선다. H2 는 이걸 잰다.
 */
@Service
public class SendService {

    private final JdbcTemplate jdbc;
    private final Channel channel;
    private final Knobs knobs;
    private final Object lock = new Object();

    public SendService(JdbcTemplate jdbc, Channel channel, Knobs knobs) {
        this.jdbc = jdbc;
        this.channel = channel;
        this.knobs = knobs;
    }

    public void send(long targetId) {
        if (knobs.useSync()) {
            // H3 전용. Java 21 에서 synchronized 안의 블로킹은 캐리어 스레드를 pin 한다.
            synchronized (lock) {
                doSend(targetId);
            }
        } else {
            doSend(targetId);
        }
    }

    private void doSend(long targetId) {
        // ① 대상 조회 — 커넥션을 잡는다
        String addr = jdbc.queryForObject(
                "SELECT address FROM target WHERE id = ?", String.class, targetId);

        // ② 외부 채널 — 커넥션을 놓은 상태로 기다린다
        channel.send(knobs.channelMs());

        // ③ 결과 기록 — 다시 커넥션을 잡는다
        jdbc.update("UPDATE target SET sent_count = sent_count + 1 WHERE id = ?", targetId);

        if (addr == null) throw new IllegalStateException("target " + targetId + " 없음");
    }
}
