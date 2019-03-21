package org.xnatural.enet.server.session;

import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.util.Map;

/**
 * redis session 管理
 */
public class RedisSessionManager extends ServerTpl {
    /**
     * 过期时间(单位: 分钟)
     */
    protected Integer expire;
    protected String keyPrefix;

    public RedisSessionManager() {
        setName("session-redis");
    }


    @EL(name = "${redisServerName}.started")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        attrs.putAll((Map) coreEp.fire("env.ns", getName()));

        expire = getInteger("expire", 30);
        keyPrefix = getStr("keyPrefix", "session-");

        coreEp.fire(getName() + ".started");
        log.info("Started {} Server", getName());
    }


    /**
     * access session
     * @param sId
     */
    @EL(name = {"${name}.access", "session.access"}, async = false)
    protected void access(String sId) {
        coreEp.fire(getRedisServerName() + ".hset", keyPrefix + sId, "access", System.currentTimeMillis(), getExpire() * 60);
    }


    @EL(name = {"${name}.set", "session.set"})
    protected void set(String sId, String key, String value) {
        coreEp.fire(getRedisServerName() + ".hset", keyPrefix + sId, key, value, getExpire() * 60);
    }


    @EL(name = {"${name}.get", "session.get"}, async = false)
    protected Object get(String sId, String key) {
        return coreEp.fire(getRedisServerName() + ".hget", keyPrefix + sId, key);
    }


    public String getRedisServerName() {
        return getStr("redisServerName", "redis");
    }


    @EL(name = "session.getExpire", async = false)
    public Integer getExpire() {
        return expire;
    }


    public RedisSessionManager setExpire(Integer expire) {
        if (expire == null) throw new NullPointerException("参数为空");
        this.expire = expire;
        attr("expire", expire);
        return this;
    }
}
