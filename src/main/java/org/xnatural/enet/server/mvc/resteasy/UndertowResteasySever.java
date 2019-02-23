package org.xnatural.enet.server.mvc.resteasy;

import io.undertow.Undertow;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.xnatural.enet.common.Utils;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import javax.ws.rs.Path;
import javax.ws.rs.core.Application;
import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 *
 */
public class UndertowResteasySever extends ServerTpl {
    /**
     * http 服务监听端口
     */
    private int                 port;
    /**
     * http 服务绑定地址
     */
    private String              hostname;
    /**
     * see: {@link #collect()}
     */
    private List<Class>         scan = new LinkedList<>();
    private UndertowJaxrsServer server;
    private ResteasyDeployment  deployment;


    public UndertowResteasySever() {
        setName("undertowRestEasy");
        setPort(8080);
        setHostname("localhost");
    }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("服务({})正在运行", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getName() + ".starting");
        // 先从核心取配置, 然后再启动
        Map<String, String> r = (Map) coreEp.fire("env.ns", getNs());
        port = Utils.toInteger(r.get("port"), getPort());
        hostname = r.getOrDefault("hostname", getHostname());
        if (attrs.containsKey("scan")) {
            try {
                for (String c : ((String) attrs.get("scan")).split(",")) {
                    if (c != null && !c.trim().isEmpty()) scan.add(Class.forName(c.trim()));
                }
            } catch (ClassNotFoundException e) {
                log.error(e);
            }
        }
        attrs.putAll(r);
        if (server == null) initServer();
        server.start(
                Undertow.builder().setIoThreads(getInteger("ioThreads", 1))
                        .setWorkerThreads(getInteger("workerThreads", 1))
                        .addHttpListener(getPort(), getHostname())
        );
        log.info("创建({})服务. hostname: {}, port: {}", getName(), getHostname(), getPort());
        collect();
        coreEp.fire(getName() + ".started");
    }


    @EL(name = "sys.stopping")
    public void stop() {
        if (server != null) {
            log.info("停止({})服务. name: {}, hostname: {}, port: {}", getName(), getHostname(), getPort());
            server.stop(); server = null; deployment.stop(); deployment = null;
        }
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }



    /**
     * 服务启动后自动扫描此类所在包下的 Handler({@link Path} 注解的类)
     * @param clz
     */
    public UndertowResteasySever scan(Class clz) {
        if (running.get()) throw new IllegalArgumentException("服务正在运行不允许更改");
        scan.add(clz);
        return this;
    }


    /**
     * 添加 接口 资源
     * @param o
     * @return
     */
    @EL(name = "${ns}.addResource")
    public UndertowResteasySever addResource(Object o) {
        if (o instanceof Class) return this;
        if (o instanceof EC) {
            if (deployment.getRegistry() == null) deployment.start();
            Object s = ((EC) o).getAttr("source");
            if (((EC) o).getAttr("path") != null) deployment.getRegistry().addSingletonResource(s, ((EC) o).getAttr("path", String.class));
            else deployment.getRegistry().addSingletonResource(s);
        } else {
            if (deployment.getRegistry() == null) deployment.getResources().add(o);
            else deployment.getRegistry().addSingletonResource(o);
        }
        return this;
    }


    /**
     * 创建 netty http 服务
     * @return
     */
    protected void initServer() {
        deployment = new ResteasyDeployment();
        deployment.setApplicationClass(Application.class.getName());
        if (deployment.getRegistry() == null) deployment.start();
        server = new UndertowJaxrsServer().deploy(deployment);
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
            if (clz.getAnnotation(Path.class) != null) addResource(clz.newInstance());
        }
    }


    public String getHostname() {
        return hostname;
    }


    public UndertowResteasySever setHostname(String hostname) {
        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新主机名");
        attrs.put("hostname", hostname); this.hostname = hostname;
        return this;
    }


    public int getPort() {
        return port;
    }


    public UndertowResteasySever setPort(int port) {
        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新端口");
        attrs.put("port", port); this.port = port;
        return this;
    }
}
