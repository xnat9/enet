package org.xnatural.enet.server.redis;

import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Protocol;

import java.util.Map;
import java.util.function.Function;

/**
 * redis
 */
public class RedisServer extends ServerTpl {
    protected JedisPool pool;

    public RedisServer() { setName("redis"); }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (exec == null) initExecutor();
        if (ep == null) ep = new EP(exec);
        ep.fire(getName() + ".starting");
        attrs.putAll((Map) ep.fire("env.ns", "cache", getName()));

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
        log.info("Started {} Server", getName());
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.info("Shutdown '{}' Server", getName());
        pool.close();
    }


    @EL(name = {"${name}.hset"})
    protected void hset(String cName, String key, Object value, Integer seconds) {
        log.trace("{}.hset. cName: {}, key: {}, value: {}, seconds: {}", getName(), cName, key, value, seconds);
        execute(c -> {
            c.hset(cName, key, value.toString());
            c.expire(cName, seconds == null ? getInteger("expire." + cName, 60 * 30) : seconds);
            return null;
        });
    }


    @EL(name = {"${name}.hget"}, async = false)
    protected Object hget(String cName, String key) {
        return execute(c -> c.hget(cName, key));
    }


    @EL(name = {"${name}.hdel"}, async = false)
    protected void hdel(String cName, String key) {
        log.debug("{}.hdel. cName: {}, key: {}", getName(), cName, key);
        execute(c -> c.hdel(cName, key));
    }


    @EL(name = {"${name}.del"})
    protected void del(String cName) {
        log.info("{}.del. cName: {}", getName(), cName);
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
