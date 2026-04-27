package com.matching.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.clients.jedis.JedisPoolConfig;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;

@Configuration
@Slf4j
public class RedisConfig {

    @Value("${spring.data.redis.cluster.nodes:}")
    private List<String> clusterNodes;

    @Value("${spring.data.redis.sentinel.master:mymaster}")
    private String sentinelMaster;

    @Value("${spring.data.redis.sentinel.nodes:}")
    private List<String> sentinelNodes;

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.cluster.max-redirects:3}")
    private int maxRedirects;

    @Value("${spring.data.redis.jedis.pool.max-active:100}")
    private int maxActive;

    @Value("${spring.data.redis.jedis.pool.max-idle:50}")
    private int maxIdle;

    @Value("${spring.data.redis.jedis.pool.min-idle:10}")
    private int minIdle;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    private boolean isClusterMode = false;
    private boolean isSentinelMode = false;

    @PostConstruct
    public void init() {
        // 判断是否为集群模式
        isClusterMode = clusterNodes != null && !clusterNodes.isEmpty() &&
                !clusterNodes.get(0).trim().isEmpty();

        // 判断是否为Sentinel模式
        isSentinelMode = !isClusterMode && sentinelNodes != null && !sentinelNodes.isEmpty() &&
                !sentinelNodes.get(0).trim().isEmpty();

        if (isClusterMode) {
            log.info("[Redis] Cluster mode detected - nodes: {}, profile: {}", clusterNodes, activeProfile);
        } else if (isSentinelMode) {
            log.info("[Redis] Sentinel mode detected - master: {}, nodes: {}, profile: {}", 
                    sentinelMaster, sentinelNodes, activeProfile);
        } else {
            log.info("[Redis] Standalone mode detected - {}:{}, profile: {}", host, port, activeProfile);
        }

        if (password != null && !password.isEmpty()) {
            log.info("[Redis] Password authentication enabled");
        } else {
            log.warn("[Redis] No password configured - ensure this is intended for your environment");
        }
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        JedisPoolConfig poolConfig = createJedisPoolConfig();
        JedisClientConfiguration clientConfig = createJedisClientConfig(poolConfig);

        if (isClusterMode) {
            return createClusterConnectionFactory(clientConfig);
        } else if (isSentinelMode) {
            return createSentinelConnectionFactory(clientConfig);
        } else {
            return createStandaloneConnectionFactory(clientConfig);
        }
    }

    private JedisPoolConfig createJedisPoolConfig() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(maxActive);
        poolConfig.setMaxIdle(maxIdle);
        poolConfig.setMinIdle(minIdle);
        poolConfig.setMaxWait(Duration.ofMillis(2000));

        // 生产环境重要参数
        poolConfig.setTestOnBorrow(true);  // 借用时测试连接
        poolConfig.setTestOnReturn(false); // 返回时不测试（性能考虑）
        poolConfig.setTestWhileIdle(true); // 空闲时测试连接
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30)); // 空闲连接检查间隔
        poolConfig.setMinEvictableIdleTime(Duration.ofMinutes(5)); // 最小空闲时间
        poolConfig.setNumTestsPerEvictionRun(3); // 每次检查的连接数

        // 集群模式需要更多连接
        if (isClusterMode) {
            poolConfig.setMaxTotal(maxActive * 2); // 集群模式配置更多连接
            log.info("[Redis] Cluster pool config - maxTotal: {}, maxIdle: {}, minIdle: {}",
                    poolConfig.getMaxTotal(), maxIdle, minIdle);
        } else {
            log.info("[Redis] Standalone pool config - maxTotal: {}, maxIdle: {}, minIdle: {}",
                    maxActive, maxIdle, minIdle);
        }

        return poolConfig;
    }

    private JedisClientConfiguration createJedisClientConfig(JedisPoolConfig poolConfig) {
        return JedisClientConfiguration.builder()
                .usePooling().poolConfig(poolConfig).and()
                .connectTimeout(Duration.ofMillis(2000))
                .readTimeout(Duration.ofMillis(2000))
                .build();
    }

    private RedisConnectionFactory createClusterConnectionFactory(JedisClientConfiguration clientConfig) {
        log.info("[Redis] Creating cluster connection factory with nodes: {}", clusterNodes);

        RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration(clusterNodes);
        clusterConfig.setMaxRedirects(maxRedirects);

        if (password != null && !password.isEmpty()) {
            clusterConfig.setPassword(password);
            log.info("[Redis] Cluster authentication configured");
        }

        // 集群模式的额外配置
        // clusterConfig.setClusterTimeout(Duration.ofMillis(5000)); // 这个方法在当前版本中不存在

        log.info("[Redis] Cluster config - maxRedirects: {}", maxRedirects);
        return new JedisConnectionFactory(clusterConfig, clientConfig);
    }

    private RedisConnectionFactory createSentinelConnectionFactory(JedisClientConfiguration clientConfig) {
        log.info("[Redis] Creating Sentinel connection factory with master: {}, nodes: {}", 
                sentinelMaster, sentinelNodes);

        RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration();
        sentinelConfig.master(sentinelMaster);
        sentinelNodes.forEach(node -> {
            String[] parts = node.split(":");
            if (parts.length == 2) {
                sentinelConfig.sentinel(parts[0], Integer.parseInt(parts[1]));
            } else {
                log.warn("[Redis] Invalid sentinel node format: {}", node);
            }
        });

        if (password != null && !password.isEmpty()) {
            sentinelConfig.setPassword(password);
            log.info("[Redis] Sentinel authentication configured");
        }

        // Sentinel模式下的超时配置
        // 注意：JedisConnectionFactory不支持直接设置Sentinel超时，使用客户端配置中的超时

        log.info("[Redis] Sentinel config - master: {}, nodes: {}", sentinelMaster, sentinelNodes);
        return new JedisConnectionFactory(sentinelConfig, clientConfig);
    }

    private RedisConnectionFactory createStandaloneConnectionFactory(JedisClientConfiguration clientConfig) {
        log.info("[Redis] Creating standalone connection factory for {}:{}", host, port);

        RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration(host, port);

        if (password != null && !password.isEmpty()) {
            standaloneConfig.setPassword(password);
            log.info("[Redis] Standalone authentication configured");
        }

        return new JedisConnectionFactory(standaloneConfig, clientConfig);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate(redisConnectionFactory);

        // 设置序列化器（可选，默认已经是String）
        template.setEnableTransactionSupport(false); // 禁用事务支持以提高性能

        String mode = isClusterMode ? "CLUSTER" : (isSentinelMode ? "SENTINEL" : "STANDALONE");
        log.info("[Redis] StringRedisTemplate configured with {} mode", mode);
        return template;
    }

    /**
     * 获取Redis模式信息，用于监控和调试
     */
    public String getRedisMode() {
        if (isClusterMode) {
            return "CLUSTER";
        } else if (isSentinelMode) {
            return "SENTINEL";
        } else {
            return "STANDALONE";
        }
    }

    /**
     * 获取连接信息，用于监控
     */
    public String getConnectionInfo() {
        if (isClusterMode) {
            return "Cluster: " + String.join(", ", clusterNodes);
        } else if (isSentinelMode) {
            return "Sentinel - master: " + sentinelMaster + ", nodes: " + String.join(", ", sentinelNodes);
        } else {
            return "Standalone: " + host + ":" + port;
        }
    }
}