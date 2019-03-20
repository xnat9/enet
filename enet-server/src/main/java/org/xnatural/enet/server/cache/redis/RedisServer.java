package org.xnatural.enet.server.cache.redis;

import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * redis
 */
public class RedisServer extends ServerTpl {
    protected JedisPool pool;

    public RedisServer() {
        setName("redis");
    }


    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getName() + ".starting");
        attrs.putAll((Map) coreEp.fire("env.ns", "cache", getName()));

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
        coreEp.fire(getName() + ".started");
        log.info("Started {} Server", getName());
    }


    @Override
    public void stop() {
        log.info("Shutdown '{}' Server", getName());
        pool.close();
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }


    @EL(name = {"${name}.hset", "cache.set"})
    protected void set(String cName, String key, String value) {
        execute(c -> {
            c.hset(cName, key, value);
            c.expire(cName, getInteger("expire." + cName, 60 * 30));
            return null;
        });
    }


    @EL(name = {"${name}.hget", "cache.get"}, async = false)
    protected Object get(String cName, String key) {
        return execute(c -> c.hget(cName, key));
    }


    @EL(name = {"${name}.evict", "cache.evict"}, async = false)
    protected void evict(String cName, String key) {
        execute(c -> c.hdel(cName, key));
    }


    @EL(name = {"${name}.clear", "cache.clear"})
    protected void clear(String cName) {
        execute(c -> c.del(cName));
    }


    @EL(name = "${name}.exec")
    protected Object execute(Function<Jedis, Object> fn) {
        Jedis c = null;
        try {
            c = pool.getResource();
            return fn.apply(c);
        } catch (Throwable t) {
            log.error(t);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }
}
