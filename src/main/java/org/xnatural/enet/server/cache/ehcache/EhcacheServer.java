package org.xnatural.enet.server.cache.ehcache;

import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ExpiryPolicyBuilder;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.ehcache.config.builders.CacheConfigurationBuilder.newCacheConfigurationBuilder;
import static org.ehcache.config.builders.ResourcePoolsBuilder.newResourcePoolsBuilder;
import static org.ehcache.config.units.MemoryUnit.MB;

/**
 * 提供 ehcache 服务
 */
public class EhcacheServer extends ServerTpl {

    protected CacheManager cm;


    public EhcacheServer() {
        setName("ehcache");
    }


    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getNs() + ".starting");
        // 先从核心取配置, 然后再启动
        coreEp.fire("env.ns", EC.of("ns", getNs()).sync(), (ec) -> {
            Map<String, String> m = (Map) ec.result;
            attrs.putAll(m);
        });

        cm = CacheManagerBuilder.newCacheManagerBuilder()
                .build(true);
        exposeBean(cm, "ehcacheManager");

        coreEp.fire(getNs() + ".started");
        log.info("Started {} Server", getName());
    }


    @Override
    public void stop() {
        if (cm != null) cm.close();
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }


    @EL(name = {"${ns}.add", "cache.add", "ehcache.add"})
    protected void addCache(EC ec) {
        // cache name
        String cName = ec.getAttr("name", String.class);
        Object key = ec.getAttr("key");
        Object value = ec.getAttr("value");
        Cache<Object, Object> cache = cm.getCache(cName, Object.class, Object.class);
        if (cache == null) {
            synchronized (this) {
                if (cache == null) {
                    cache = cm.createCache(cName, newCacheConfigurationBuilder(
                            Object.class, Object.class, newResourcePoolsBuilder().heap(20, MB).build()
                            )
                            .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(Duration.ofMinutes(ec.getAttr("expire", Integer.class, 20))))
                    );
                }
            }
        }
        cache.put(key, value);
    }


    @EL(name = {"${ns}.get", "cache.get", "ehcache.get"}, async = false)
    protected Object getCache(EC ec) {
        String cName = ec.getAttr("name", String.class);
        Object key = ec.getAttr("key");
        Cache<Object, Object> cache = cm.getCache(cName, Object.class, Object.class);
        if (cache == null) return null;
        return cache.get(key);
    }
}
