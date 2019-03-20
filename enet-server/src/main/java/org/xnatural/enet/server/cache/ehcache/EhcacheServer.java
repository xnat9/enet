package org.xnatural.enet.server.cache.ehcache;

import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ExpiryPolicyBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.ehcache.config.builders.CacheConfigurationBuilder.newCacheConfigurationBuilder;
import static org.ehcache.config.builders.ResourcePoolsBuilder.newResourcePoolsBuilder;
import static org.ehcache.config.units.EntryUnit.ENTRIES;
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
        coreEp.fire(getName() + ".starting");
        attrs.putAll((Map) coreEp.fire("env.ns", "cache", getName()));

        cm = CacheManagerBuilder.newCacheManagerBuilder().build(true);
        exposeBean(cm, "ehcacheManager");

        coreEp.fire(getName() + ".started");
        log.info("Started {} Server", getName());
    }


    @Override
    public void stop() {
        log.info("Shutdown '{}' Server", getName());
        if (cm != null) cm.close();
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }


    @EL(name = {"${name}.create"}, async = false)
    protected Cache<Object, Object> createCache(String cName, Duration expire, Integer heapOfEntries, Integer heapOfMB) {
        Cache<Object, Object> cache = cm.getCache(cName, Object.class, Object.class);
        if (cache == null) {
            synchronized (this) {
                if (cache == null) {
                    ResourcePoolsBuilder b = newResourcePoolsBuilder();
                    if (heapOfEntries != null && heapOfMB != null) throw new IllegalArgumentException("heapOfEntries 和 heapOfMB 不能同时设置");
                    else if (heapOfEntries == null && heapOfMB == null) throw new IllegalArgumentException("heapOfEntries 和 heapOfMB 必须指定一个");
                    else if (heapOfEntries != null) b = b.heap(heapOfEntries, ENTRIES);
                    else if (heapOfMB != null) b = b.heap(heapOfMB, MB);
                    cache = cm.createCache(cName, newCacheConfigurationBuilder(Object.class, Object.class, b.build())
                        .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(expire == null ? Duration.ofSeconds(getInteger("expire." + cName, 60 * 30)) : expire))
                    );
                }
            }
        }
        return cache;
    }


    @EL(name = {"${name}.set", "cache.set"})
    protected void set(String cName, Object key, Object value) {
        Cache<Object, Object> cache = cm.getCache(cName, Object.class, Object.class);
        if (cache == null) cache = createCache(cName, null, getInteger("heapOfEntries", 1000), null);
        cache.put(key, value);
    }


    @EL(name = {"${name}.get", "cache.get"}, async = false)
    protected Object get(String cName, Object key) {
        Cache<Object, Object> cache = cm.getCache(cName, Object.class, Object.class);
        return (cache == null ? null : cache.get(key));
    }


    @EL(name = {"${name}.evict", "cache.evict"}, async = false)
    protected void evict(String cName, Object key) {
        Cache<Object, Object> cache = cm.getCache(cName, Object.class, Object.class);
        if (cache != null) cache.remove(key);
    }


    @EL(name = {"${name}.clear", "cache.clear"})
    protected void clear(String cName) {
        Cache<Object, Object> cache = cm.getCache(cName, Object.class, Object.class);
        if (cache != null) cache.clear();
    }
}
