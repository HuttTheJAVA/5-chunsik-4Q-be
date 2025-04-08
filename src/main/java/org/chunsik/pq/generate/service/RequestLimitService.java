package org.chunsik.pq.generate.service;

import jakarta.annotation.Nullable;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.chunsik.pq.login.manager.UserManager;
import org.chunsik.pq.login.security.CustomUserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RequestLimitService {

    @Value("${chunsik.cookie.max-age}")
    private int cookieMaxAge;

    private final UserManager userManager;

    private final RedisTemplate<String, String> redisTemplate;

    private final String CLIENT_REQUEST_LIMIT = "3";
    private final String GLOBAL_REQUEST_LIMIT = "9";
    private final String CLIENT_WINDOW_SIZE = "60";
    private final String GLOBAL_WINDOW_SIZE = "600";
    private final String GLOBAL_KEY = "GLOBAL_KEY";

    private final String script = "local KEY = KEYS[1] " + // 클라이언트 식별 값
            "local GLOBAL_KEY = KEYS[2] " +
            "local CLIENT_LIMIT = tonumber(ARGV[1]) " + // 클라이언트 생성 가능 횟수
            "local GLOBAL_LIMIT = tonumber(ARGV[2]) " + // 서비스 생성 가능 횟수
            "local CLIENT_WINDOW_SIZE = tonumber(ARGV[3]) " + // 클라이언트 윈도우 사이즈 = ttl (sec)
            "local GLOBAL_WINDOW_SIZE = tonumber(ARGV[4]) " + // 서비스 윈도우 사이즈 = ttl (sec)

            "if redis.call('SETNX', KEY, 0) == 1 " + // KEY 초기 설정 값이 true면 (캐시에 처음 등록됐으면)
            "then " +
            "redis.call('EXPIRE', KEY, CLIENT_WINDOW_SIZE) " + // KEY가 캐시에 없으면 등록 후 0으로 설정
            "end " +

            "if redis.call('SETNX', GLOBAL_KEY, 0) == 1 " +
            "then " +
            "redis.call('EXPIRE', GLOBAL_KEY, GLOBAL_WINDOW_SIZE) " + // GLOBAL_KEY가 캐시에 없으면 등록 후 0으로 설정
            "end " +

            "local KEY_INCR = redis.call('INCR', KEY) " + // KEY + 1
            "local GLOBAL_INCR = redis.call('INCR', GLOBAL_KEY) " + // GLOBAL_KEY + 1

            "if KEY_INCR > CLIENT_LIMIT " + // KEY_INCR가 클라이언트 제한 초과 시
            "then " +
            "redis.call('DECR', KEY) " +
            "redis.call('DECR', GLOBAL_KEY) " + // 각각 더한 값을 롤백
            "return 0 " + // 0 리턴 (클라이언트 요청 실패)
            "end " +

            "if GLOBAL_INCR > GLOBAL_LIMIT " + // GLOBAL_INCR가 클라이언트 제한 초과 시
            "then " +
            "redis.call('DECR', KEY) " +
            "redis.call('DECR', GLOBAL_KEY) " + // 각각 더한 값을 롤백
            "return 1 " + // 1 리턴 (서비스 요청 실패)
            "end " +

            "return 2";

    public Long canUseService(String uuid, HttpServletResponse response) {
        Long userId = findLoginUserIdOrNull();
        String clientKey;
        if (userId == null) {
            if (uuid == null) {
                // UUID 생성
                uuid = UUID.randomUUID().toString();

                // 쿠키 생성 및 설정
                Cookie Cookie = new Cookie("uuid", uuid);
                Cookie.setHttpOnly(true);
                Cookie.setPath("/");
                Cookie.setMaxAge(cookieMaxAge); // 604800 sec = 1주일

                // Response에 쿠키 추가
                response.addCookie(Cookie);
            }

            // 특정 로직을 수행하고 false를 반환할 수 있음 (서비스 사용 불가)
            clientKey = uuid;
        } else {
            clientKey = "userID:" + String.valueOf(userId);
        }

        return incrementKeys(clientKey);
    }

    @Nullable
    private Long findLoginUserIdOrNull() {
        Optional<CustomUserDetails> currentUser = userManager.currentUser();
        return currentUser.map(CustomUserDetails::getId).orElse(null);
    }

    public Long incrementKeys(String clientKey) {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);

        List<String> keys = Arrays.asList(clientKey, GLOBAL_KEY);
        List<String> args = Arrays.asList(CLIENT_REQUEST_LIMIT, GLOBAL_REQUEST_LIMIT, CLIENT_WINDOW_SIZE, GLOBAL_WINDOW_SIZE);

        return redisTemplate.execute(redisScript, keys, args.toArray());
    }
}