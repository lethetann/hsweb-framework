package org.hswebframework.web.oauth2.server.impl;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.codec.digest.DigestUtils;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.oauth2.ErrorType;
import org.hswebframework.web.oauth2.OAuth2Exception;
import org.hswebframework.web.oauth2.server.AccessToken;
import org.hswebframework.web.oauth2.server.AccessTokenManager;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

public class RedisAccessTokenManager implements AccessTokenManager {

    private final ReactiveRedisOperations<String, RedisAccessToken> tokenRedis;

    @Getter
    @Setter
    private int tokenExpireIn = 7200;//2小时

    @Getter
    @Setter
    private int refreshExpireIn = 2592000; //30天

    public RedisAccessTokenManager(ReactiveRedisOperations<String, RedisAccessToken> tokenRedis) {
        this.tokenRedis = tokenRedis;
    }

    @SuppressWarnings("all")
    public RedisAccessTokenManager(ReactiveRedisConnectionFactory connectionFactory) {
        this(new ReactiveRedisTemplate<>(connectionFactory, RedisSerializationContext
                .newSerializationContext()
                .key((RedisSerializer) RedisSerializer.string())
                .value(RedisSerializer.java())
                .hashKey(RedisSerializer.string())
                .hashValue(RedisSerializer.java())
                .build()
        ));
    }

    @Override
    public Mono<Authentication> getAuthenticationByToken(String accessToken) {

        return tokenRedis
                .opsForValue()
                .get(createTokenRedisKey(accessToken))
                .map(RedisAccessToken::getAuthentication);
    }

    private String createTokenRedisKey(String token) {
        return "oauth2-token:" + token;
    }

    private String createRefreshTokenRedisKey(String token) {
        return "oauth2-refresh-token:" + token;
    }

    private String createSingletonTokenRedisKey(String clientId) {
        return "oauth2-" + clientId + "-token";
    }

    private Mono<RedisAccessToken> doCreateAccessToken(String clientId, Authentication authentication, boolean singleton) {
        String token = DigestUtils.md5Hex(UUID.randomUUID().toString());
        String refresh = DigestUtils.md5Hex(UUID.randomUUID().toString());
        RedisAccessToken accessToken = new RedisAccessToken(clientId, token, refresh, System.currentTimeMillis(), authentication, singleton);

        return storeToken(accessToken).thenReturn(accessToken);
    }

    private Mono<Void> storeToken(RedisAccessToken token) {
        return Mono
                .zip(
                        tokenRedis.opsForValue().set(createTokenRedisKey(token.getAccessToken()), token, Duration.ofSeconds(tokenExpireIn)),
                        tokenRedis.opsForValue().set(createRefreshTokenRedisKey(token.getRefreshToken()), token, Duration.ofSeconds(refreshExpireIn))
                ).then();
    }

    private Mono<AccessToken> doCreateSingletonAccessToken(String clientId, Authentication authentication) {
        String redisKey = createSingletonTokenRedisKey(clientId);

        return tokenRedis
                .opsForValue()
                .get(redisKey)
                .flatMap(token -> tokenRedis
                        .getExpire(redisKey)
                        .map(duration -> token.toAccessToken((int) (duration.toMillis() / 1000))))
                .switchIfEmpty(Mono.defer(() -> doCreateAccessToken(clientId, authentication, true)
                        .flatMap(redisAccessToken -> tokenRedis
                                .opsForValue()
                                .set(redisKey, redisAccessToken, Duration.ofSeconds(tokenExpireIn))
                                .thenReturn(redisAccessToken.toAccessToken(tokenExpireIn))))
                );
    }

    @Override
    public Mono<AccessToken> createAccessToken(String clientId,
                                               Authentication authentication,
                                               boolean singleton) {
        return singleton
                ? doCreateSingletonAccessToken(clientId, authentication)
                : doCreateAccessToken(clientId, authentication, false).map(token -> token.toAccessToken(tokenExpireIn));
    }

    @Override
    public Mono<AccessToken> refreshAccessToken(String clientId, String refreshToken) {
        String redisKey = createRefreshTokenRedisKey(refreshToken);

        return tokenRedis
                .opsForValue()
                .get(redisKey)
                .switchIfEmpty(Mono.error(() -> new OAuth2Exception(ErrorType.EXPIRED_REFRESH_TOKEN)))
                .flatMap(token -> {
                    if (!token.getClientId().equals(clientId)) {
                        return Mono.error(new OAuth2Exception(ErrorType.ILLEGAL_CLIENT_ID));
                    }
                    //生成新token
                    String accessToken = DigestUtils.md5Hex(UUID.randomUUID().toString());
                    token.setAccessToken(accessToken);
                    token.setCreateTime(System.currentTimeMillis());
                    return storeToken(token)
                            .as(result -> {
                                // 单例token
                                if (token.isSingleton()) {
                                    return tokenRedis
                                            .opsForValue()
                                            .set(createSingletonTokenRedisKey(clientId), token, Duration.ofSeconds(tokenExpireIn))
                                            .then(result);
                                }
                                return result;
                            })
                            .thenReturn(token.toAccessToken(tokenExpireIn));
                });

    }
}
