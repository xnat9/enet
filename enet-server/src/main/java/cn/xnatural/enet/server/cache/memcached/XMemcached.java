package cn.xnatural.enet.server.cache.memcached;

import com.google.code.yanf4j.config.Configuration;
import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.XMemcachedClientBuilder;
import net.rubyeye.xmemcached.command.BinaryCommandFactory;
import net.rubyeye.xmemcached.utils.AddrUtil;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Memcached
 */
public class XMemcached extends ServerTpl {
    protected final AtomicBoolean   running = new AtomicBoolean(false);
    protected       MemcachedClient client;

    public XMemcached() {
        super("memcached");
    }
    public XMemcached(String name) {
        super(name);
    }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Client is running", getName()); return;
        }
        if (ep == null) {ep = new EP(); ep.addListenerSource(this);}
        ep.fire(getName() + ".starting");
        Map m = (Map) ep.fire("env.ns", "cache", getName());
        if (m != null) attrs.putAll(m);

        try {
            List<InetSocketAddress> as = AddrUtil.getAddresses(getStr("hosts", "localhost:11211"));
            System.setProperty(Configuration.XMEMCACHED_SELECTOR_POOL_SIZE, getInteger("selectorPoolSize", as.size()) + "");
            XMemcachedClientBuilder builder = new XMemcachedClientBuilder(as);
            builder.setConnectionPoolSize(getInteger("poolSize", as.size()));
            builder.setConnectTimeout(getLong("connectTimeout", 5000L));
            builder.setOpTimeout(getLong("opTimeout", 5000L));
            builder.setName(getName());
            if (getBoolean("binaryCommand", false)) builder.setCommandFactory(new BinaryCommandFactory());
            client = builder.build();
            exposeBean(client);
        } catch (Exception ex) {
            log.error(ex);
        }

        ep.fire(getName() + ".started");
        log.info("Started {} Client", getName());
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.info("Shutdown '{}' Client", getName());
        try { client.shutdown(); } catch (IOException e) { log.error(e); }
    }



    @EL(name = {"${name}.set", "cache.set"})
    protected void set(String cName, String key, Object value) {
        log.trace("{}.set. cName: {}, key: {}, value: {}", getName(), cName, key, value);
        try {
            client.withNamespace(cName, c -> {
                c.setWithNoReply(key, getInteger("expire." + cName, getInteger("defaultExpire", 60 * 30)), value); return null;
            });
        } catch (Exception e) {
            log.error(e);
        }
    }


    @EL(name = {"${name}.get", "cache.get"}, async = false)
    protected Object get(String cName, String key) {
        try {
            return client.withNamespace(cName, c -> c.get(key));
        } catch (Exception e) {
            log.error(e);
        }
        return null;
    }


    @EL(name = {"${name}.evict", "cache.evict"})
    protected void evict(String cName, String key) {
        log.debug("{}.evict. cName: {}, key: {}", getName(), cName, key);
        try {
            client.withNamespace(cName, c -> c.delete(key));
        } catch (Exception e) {
            log.error(e);
        }
    }


    @EL(name = {"${name}.clear", "cache.clear"})
    protected void clear(String cName) {
        log.info("{}.clear. cName: {}", getName(), cName);
        try {
            client.invalidateNamespace(cName, 10 * 1000L);
        } catch (Exception e) {
            log.error(e);
        }
    }
}
