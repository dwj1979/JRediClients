package redis.clients.redisson.spring.session;

import redis.clients.redisson.Redisson;
import redis.clients.redisson.api.RedissonClient;
import redis.clients.redisson.spring.session.config.EnableRedissonHttpSession;
import org.springframework.context.annotation.Bean;

@EnableRedissonHttpSession
public class Config {

    @Bean
    public RedissonClient redisson() {
        return Redisson.create();
    }
    
    @Bean
    public SessionEventsListener listener() {
        return new SessionEventsListener();
    }
    
}
