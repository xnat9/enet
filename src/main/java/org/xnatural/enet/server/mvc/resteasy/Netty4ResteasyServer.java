package org.xnatural.enet.server.mvc.resteasy;

import io.netty.channel.ChannelPipeline;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.plugins.server.netty.RequestDispatcher;
import org.jboss.resteasy.plugins.server.netty.RequestHandler;
import org.jboss.resteasy.plugins.server.netty.RestEasyHttpRequestDecoder;
import org.jboss.resteasy.plugins.server.netty.RestEasyHttpResponseEncoder;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.xnatural.enet.common.Utils;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import javax.annotation.PostConstruct;
import javax.ws.rs.Path;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * netty4 和 resteasy 结合
 */
public class Netty4ResteasyServer extends ServerTpl {
    /**
     * 根 path 前缀
     */
    protected String             rootPath;
    /**
     * see: {@link #collect()}
     */
    protected List<Class>        scan       = new LinkedList<>();
    protected ResteasyDeployment deployment = new ResteasyDeployment();
    protected RequestDispatcher  dispatcher;


    public Netty4ResteasyServer() {
        setName("resteasy");
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
        rootPath = r.getOrDefault("rootPath", "/");
        if (attrs.containsKey("scan")) {
            for (String c : ((String) attrs.get("scan")).split(",")) {
                try {
                    if (c != null && !c.trim().isEmpty()) scan.add(Class.forName(c.trim()));
                } catch (ClassNotFoundException e) {
                    log.error(e);
                }
            }
        }
        attrs.putAll(r);

        startDeployment(); initDispatcher(); collect();
        coreEp.fire(getName() + ".started");
        log.info("Started {} Server. rootPath: {}", getName(), getRootPath());
    }


    @Override
    public void stop() {
        log.info("Shutdown '{}' Server", getName());
        dispatcher = null;
        deployment.stop(); deployment = null;
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }


    @EL(name = "server.http-netty.addHandler", async = false)
    protected void addHandler(ChannelPipeline cp) {
        initDispatcher();
        // 参考 NettyJaxrsServer
        cp.addLast(new RestEasyHttpRequestDecoder(dispatcher.getDispatcher(), rootPath, RestEasyHttpRequestDecoder.Protocol.HTTP));
        cp.addLast(new RestEasyHttpResponseEncoder());
        cp.addLast(new RequestHandler(dispatcher));
    }


    /**
     * 添加 接口 资源
     * @param source
     * @return
     */
    @EL(name = {"resteasy.addResource"})
    public Netty4ResteasyServer addResource(Object source, String path) {
        if (source instanceof Class) return this;
        startDeployment();
        if (path != null) deployment.getRegistry().addSingletonResource(source, path);
        else deployment.getRegistry().addSingletonResource(source);
        return this;
    }


    /**
     * 创建 RequestDispatcher
     */
    protected void initDispatcher() {
        if (dispatcher == null) {
            dispatcher = new RequestDispatcher((SynchronousDispatcher)deployment.getDispatcher(), deployment.getProviderFactory(), null);
        }
    }


    protected void startDeployment() {
        if (deployment == null) deployment = new ResteasyDeployment();
        if (deployment.getRegistry() != null) return;
        synchronized(this) {
            if (deployment.getRegistry() != null) return;
            deployment.start();
        }
    }


    /**
     * 服务启动后自动扫描此类所在包下的 Handler({@link Path} 注解的类)
     * @param clz
     */
    public Netty4ResteasyServer scan(Class clz) {
        if (running.get()) throw new IllegalArgumentException("服务正在运行不允许更改");
        scan.add(clz);
        return this;
    }


    /**
     * 收集 {@link #scan} 类对的包下边所有的 有注解{@link Path}的类
     */
    protected void collect() {
        if (scan == null || scan.isEmpty()) return;
        try {
            for (Class clz : scan) {
                String pkg = clz.getPackage().getName();
                File pkgDir = new File(getClass().getClassLoader().getResource(pkg.replaceAll("\\.", "/")).getFile());
                File[] arr = pkgDir.listFiles(f -> f.getName().endsWith(".class"));
                if (arr != null) for (File f : arr) load(pkg, f);
            }
        } catch (Exception e) {
            log.error(e, "扫描Handler类出错!");
        }
    }


    protected void load(String pkg, File f) throws Exception {
        if (f.isDirectory()) {
            for (File ff : f.listFiles(ff -> ff.getName().endsWith(".class"))) {
                load(pkg + "." + f.getName(), ff);
            }
        } else if (f.isFile() && f.getName().endsWith(".class")) {
            Class<?> clz = getClass().getClassLoader().loadClass(pkg + "." + f.getName().replace(".class", ""));
            if (clz.getAnnotation(Path.class) != null) addResource(createAndInitSource(clz), null);
        }
    }


    protected Object createAndInitSource(Class clz) throws Exception {
        Object o = clz.newInstance();
        Class<? extends Object> c = o.getClass();
        loop: do {
            for (Field f : c.getDeclaredFields()) {
                if (EP.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true); f.set(o, coreEp);
                        break loop;
                    } catch (IllegalAccessException e) {
                        log.error(e);
                    }
                }
            }
            c = c.getSuperclass();
        } while (c != null);

        c = o.getClass();
        loop: do {
            for (Method m : c.getDeclaredMethods()) {
                PostConstruct an = m.getAnnotation(PostConstruct.class);
                if (an == null) continue;
                Utils.invoke(m, o); break loop;
            }
            c = c.getSuperclass();
        } while (c != null);
        coreEp.addListenerSource(o);
        return o;
    }


    public String getRootPath() {
        return rootPath;
    }


    public Netty4ResteasyServer setRootPath(String rootPath) {
        if (running.get()) throw new RuntimeException("服务正在运行, 不充许更改");
        this.rootPath = rootPath;
        return this;
    }
}
