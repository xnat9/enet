package org.xnatural.enet.server.session;

import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import static java.util.Collections.emptyMap;

/**
 * @author xiangxb, 2019-02-05
 */
public class MemSessionManager extends ServerTpl {

    protected Map<String, Map> sMap;

    public MemSessionManager() {
        setName("memSession");
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
        Map<String, String> r = (Map) coreEp.fire("env.ns", getNs());
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


    @EL(name = {"${ns}.session.set", "session.set"})
    protected MemSessionManager put(String sId, Object key, Object value) {
        sMap.computeIfAbsent(sId, s -> new ConcurrentHashMap<>()).put(key, value);
        return this;
    }


    @EL(name = {"${ns}.session.get", "session.get"}, async = false)
    protected Object get(String sId, Object key) {
        return sMap.getOrDefault(sId, emptyMap()).get(key);
    }
}
