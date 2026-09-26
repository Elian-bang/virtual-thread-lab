package dev.elian.vt;

/**
 * H3 의 락 범위. v1 은 {@link #GLOBAL_ALL} 하나뿐이라 pinning 과 상호배제를 구분할 수 없었다.
 *
 * <ul>
 *   <li>{@link #NONE} — 락 없음</li>
 *   <li>{@link #GLOBAL_ALL} — v1 과 같다. 락 하나가 DB 와 50ms 외부 대기까지 감싼다.
 *       스레드 모델과 무관하게 요청이 한 줄로 서므로 PLATFORM 과 VIRTUAL 이 비슷해야 한다</li>
 *   <li>{@link #GLOBAL_JDBC} — 락이 JDBC 호출만 감싼다. 외부 대기는 락 밖이다.
 *       JDK 21 에서 락 안의 소켓 대기가 캐리어를 pin 하면 VIRTUAL 만 추가로 느려져야 한다</li>
 * </ul>
 */
public enum SyncMode { NONE, GLOBAL_ALL, GLOBAL_JDBC }
