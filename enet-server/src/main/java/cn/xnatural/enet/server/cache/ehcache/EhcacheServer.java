package cn.xnatural.enet.server.cache.ehcache;

import cn.xnatural.enet.event.EC;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ExpiryPolicyBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.slf4j.event.Level;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import static org.ehcache.config.builders.CacheConfigurationBuilder.newCacheConfigurationBuilder;
import static org.ehcache.config.builders.ResourcePoolsBuilder.newResourcePoolsBuilder;
import static org.ehcache.config.units.EntryUnit.ENTRIES;
import static org.ehcache.config.units.MemoryUnit.MB;

/**
 * 提供 ehcache 服务
 */
public class EhcacheServer extends ServerTpl {
    protected       CacheManager  cm;


    public EhcacheServer() { this("ehcache"); }
    public EhcacheServer(String name) { super(name); }


    @EL(name = {"sys.starting", "${name}.start"})
    public void start() {
        if (ep == null) {ep = new EP(); ep.addListenerSource(this);}
        ep.fire(getName() + ".starting");
        Map m = (Map) ep.fire("env.ns", "cache", getName());
        if (m != null) attrs.putAll(m);

        cm = CacheManagerBuilder.newCacheManagerBuilder().build(true);
        exposeBean(cm);

        ep.fire(getName() + ".started");
        log.info("Started {} Server", getName());
    }


    @EL(name = {"sys.stopping", "${name}.stop"})
    public void stop(EC ec) {
        log.doLog(null, (ec != null && Objects.equals(ec.eName(), getName()+ ".stop") ? Level.INFO : Level.DEBUG), "Shutdown '{}' Server", getName());
        if (cm != null) cm.close();
    }


    @EL(name = {"${name}.create"}, async = false)
    public Cache<Object, Object> createCache(String cName, Duration expire, Integer heapOfEntries, Integer heapOfMB) {
        Cache<Object, Object> cache = cm.getCache(cName, Object.class, Object.class);
        if (cache == null) {
            synchronized (this) {
                cache = cm.getCache(cName, Object.class, Object.class); // 不同线程同时进来, cache为null
                if (cache == null) {
                    log.info("{}.create. cName: {}, expire: {}, heapOfEntries: {}, heapOfMB: {}", getName(), cName, expire, heapOfEntries, heapOfMB);
                    ResourcePoolsBuilder b = newResourcePoolsBuilder();
                    if (heapOfEntries != null && heapOfMB != null) throw new IllegalArgumentException("heapOfEntries 和 heapOfMB 不能同时设置");
                    else if (heapOfEntries == null && heapOfMB == null) throw new IllegalArgumentException("heapOfEntries 和 heapOfMB 必须指定一个");
                    else if (heapOfEntries != null) b = b.heap(heapOfEntries, ENTRIES);
                    else if (heapOfMB != null) b = b.heap(heapOfMB, MB);
                    cache = cm.createCache(cName, newCacheConfigurationBuilder(Object.class, Object.class, b.build())
                        .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(expire == null ? Duration.ofSeconds(getInteger("expire." + cName, getInteger("defaultExpire", 60 * 30))) : expire))
                    );
                }
            }
        }
        return cache;
    }


    @EL(name = {"${name}.set", "cache.set"})
    public void set(String cName, Object key, Object value) {
        log.trace("{}.set. cName: {}, key: {}, value: {}", getName(), cName, key, value);
        Cache<Object, Object> cache = cm.getCache(cName, Object.class, Object.class);
        if (cache == null) cache = createCache(cName, null, getInteger("heapOfEntries", 1000), null);
        cache.put(key, value);
    }


    @EL(name = {"${name}.get", "cache.get"}, async = false)
    public Object get(String cName, Object key) {
        Cache<Object, Object> cache = cm.getCache(cName, Object.class, Object.class);
        return (cache == null ? null : cache.get(key));
    }


    @EL(name = {"${name}.evict", "cache.evict"}, async = false)
    public void evict(String cName, Object key) {
        log.debug("{}.evict. cName: {}, key: {}", getName(), cName, key);
        Cache<Object, Object> cache = cm.getCache(cName, Object.class, Object.class);
        if (cache != null) cache.remove(key);
    }


    @EL(name = {"${name}.clear", "cache.clear"})
    public void clear(String cName) {
        log.info("{}.clear. cName: {}", getName(), cName);
        Cache<Object, Object> cache = cm.getCache(cName, Object.class, Object.class);
        if (cache != null) cache.clear();
    }
}
