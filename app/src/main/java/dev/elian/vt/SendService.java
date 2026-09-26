package dev.elian.vt;

import java.util.function.Supplier;
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
 * <p>v2: 구간별 시간을 {@link Phases} 에 남기고, 락 범위를 {@link SyncMode} 로 나눈다.
 * 락은 {@code synchronized} 로 건다 — JDK 21 에서 monitor 가 pinning 을 만드는지 보는 것이 H3 의 목적이라서다.
 */
@Service
public class SendService {

    private final JdbcTemplate jdbc;
    private final Channel channel;
    private final Knobs knobs;
    private final Phases phases;
    private final Object lock = new Object();

    public SendService(JdbcTemplate jdbc, Channel channel, Knobs knobs, Phases phases) {
        this.jdbc = jdbc;
        this.channel = channel;
        this.knobs = knobs;
        this.phases = phases;
    }

    public void send(long targetId) {
        if (knobs.sync() == SyncMode.GLOBAL_ALL) {
            long w0 = System.nanoTime();
            synchronized (lock) {
                phases.lockWait(System.nanoTime() - w0);
                doSend(targetId, false);
            }
        } else {
            doSend(targetId, knobs.sync() == SyncMode.GLOBAL_JDBC);
        }
    }

    private void doSend(long targetId, boolean lockJdbc) {
        // ① 대상 조회 — 커넥션을 잡는다
        String addr = db(lockJdbc, () -> jdbc.queryForObject(
                "SELECT address FROM target WHERE id = ?", String.class, targetId));

        // ② 외부 채널 — 커넥션을 놓은 상태로 기다린다 (락 밖)
        long c0 = System.nanoTime();
        channel.send(knobs.channelMs());
        phases.channel(System.nanoTime() - c0);

        // ③ 결과 기록 — 다시 커넥션을 잡는다
        db(lockJdbc, () -> jdbc.update("UPDATE target SET sent_count = sent_count + 1 WHERE id = ?", targetId));

        if (addr == null) throw new IllegalStateException("target " + targetId + " 없음");
    }

    private <T> T db(boolean lockJdbc, Supplier<T> call) {
        if (!lockJdbc) {
            long d0 = System.nanoTime();
            try { return call.get(); } finally { phases.db(System.nanoTime() - d0); }
        }
        long w0 = System.nanoTime();
        synchronized (lock) {
            long d0 = System.nanoTime();
            phases.lockWait(d0 - w0);
            try { return call.get(); } finally { phases.db(System.nanoTime() - d0); }
        }
    }
}
