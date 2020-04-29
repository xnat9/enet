package cn.xnatural.enet.server.redis;

import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * redis
 */
public class RedisClient extends ServerTpl {
    protected final AtomicBoolean running = new AtomicBoolean(false);
    protected       JedisPool     pool;

    public RedisClient() { super("redis"); }
    public RedisClient(String name) { super(name); }


    @EL(name = "sys.starting", async = true)
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Client is running", getName()); return;
        }
        if (ep == null) {ep = new EP(); ep.addListenerSource(this);}
        ep.fire(getName() + ".starting");
        Map m = (Map) ep.fire("env.ns", "cache", getName());
        if (m != null) attrs.putAll(m);

        JedisPoolConfig poolCfg = new JedisPoolConfig();
        poolCfg.setMinIdle(getInteger("minIdle", 1));
        poolCfg.setMaxIdle(getInteger("maxIdle", 4));
        poolCfg.setMaxTotal(getInteger("maxTotal", 7));
        poolCfg.setMaxWaitMillis(getInteger("maxWaitMillis", 2000));
        pool = new JedisPool(
            poolCfg, getStr("host", "localhost"), getInteger("port", 6379),
            getInteger("connectionTimeout", getInteger("timeout", Protocol.DEFAULT_TIMEOUT)),
            getInteger("soTimeout", getInteger("timeout", Protocol.DEFAULT_TIMEOUT)),
            getStr("password", null),
            getInteger("database", Protocol.DEFAULT_DATABASE),
            getStr("clientName", null)
        );

        exposeBean(pool);
        ep.fire(getName() + ".started");
        log.info("Started {} Client", getName());
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.info("Shutdown '{}' Client", getName());
        if (pool != null) pool.close();
    }


    @EL(name = {"${name}.hset"})
    public void hset(String cName, String key, Object value, Integer seconds) {
        log.trace("{}.hset. cName: {}, key: {}, value: {}, seconds: {}", getName(), cName, key, value, seconds);
        exec(c -> {
            c.hset(cName, key, value.toString());
            c.expire(cName, seconds == null ? getInteger("expire." + cName, getInteger("defaultExpire", 60 * 30)) : seconds);
            return null;
        });
    }


    @EL(name = {"${name}.hget"}, async = false)
    public Object hget(String cName, String key) {
        return exec(c -> c.hget(cName, key));
    }


    @EL(name = {"${name}.hdel"}, async = false)
    public void hdel(String cName, String key) {
        log.debug("{}.hdel. cName: {}, key: {}", getName(), cName, key);
        exec(c -> c.hdel(cName, key));
    }


    @EL(name = {"${name}.del"})
    public void del(String cName) {
        log.info("{}.del. cName: {}", getName(), cName);
        exec(c -> c.del(cName));
    }


    @EL(name = "${name}.exec", async = false)
    public Object exec(Function<Jedis, Object> fn) {
        try (Jedis c = pool.getResource()) {
            return fn.apply(c);
        } catch (Throwable t) { throw t; }
    }
}
