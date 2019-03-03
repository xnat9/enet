package org.xnatural.enet.server.session;

import org.xnatural.enet.common.Utils;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author xiangxb, 2019-02-05
 */
public class MemSessionManager extends ServerTpl {
    /**
     * 过期时间(单位: 分钟)
     */
    protected Integer expire = 30;

    protected Map<String, SessionData> sMap;

    public MemSessionManager() {
        setName("session-mem");
    }


    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getName() + ".starting");
        // 先从核心取配置, 然后再启动
        Map<String, String> r = (Map) coreEp.fire("env.ns", "session", getName());
        if (r.containsKey("expire")) {
            setExpire(Utils.toInteger(r.get("expire"), getExpire()));
        }
        attrs.putAll(r);
        sMap = new ConcurrentHashMap<>();
        coreEp.fire(getName() + ".started");
        log.info("Started {} Server", getName());
    }


    @Override
    public void stop() {
        sMap = null;
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }


    @EL(name = {"${name}.set", "session.set"})
    protected void set(String sId, Object key, Object value) {
        createOrGet(sId).data.put(key, value);
    }


    /**
     * access session
     * @param sId
     */
    @EL(name = {"${name}.access", "session.access"}, async = false)
    protected void access(String sId) {
        createOrGet(sId).accessTime = System.currentTimeMillis();
    }


    /**
     * create session
     * @param sId
     * @return {@link SessionData}
     */
    protected SessionData createOrGet(String sId) {
        if (sMap.containsKey(sId)) {
            SessionData d = sMap.get(sId);
            // 判断是否已过期
            if ((System.currentTimeMillis() - d.accessTime) > TimeUnit.MINUTES.toMillis(expire)) sMap.remove(sId);
            else return d;
        }
        synchronized(this) {
            if (sMap.containsKey(sId)) return sMap.get(sId);
            SessionData d = new SessionData(); sMap.put(sId, d);
            d.data = new ConcurrentHashMap<>();
            d.accessTime = System.currentTimeMillis();
            log.info("create session '{}' at {}", sId, d.accessTime);
            return d;
        }
    }


    @EL(name = {"${name}.get", "session.get"}, async = false)
    protected Object get(String sId, Object key) {
        return createOrGet(sId).data.get(key);
    }


    @EL(name = "session.getExpire", async = false)
    public Integer getExpire() {
        return expire;
    }


    public MemSessionManager setExpire(Integer expire) {
        if (expire == null) throw new NullPointerException("参数为空");
        this.expire = expire;
        return this;
    }


    protected class SessionData {
        Long accessTime; Map<Object, Object> data;
    }
}
