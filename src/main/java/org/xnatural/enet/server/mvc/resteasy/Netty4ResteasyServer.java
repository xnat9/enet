package org.xnatural.enet.server.mvc.resteasy;

import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.plugins.server.netty.RequestDispatcher;
import org.jboss.resteasy.plugins.server.netty.RequestHandler;
import org.jboss.resteasy.plugins.server.netty.RestEasyHttpRequestDecoder;
import org.jboss.resteasy.plugins.server.netty.RestEasyHttpResponseEncoder;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.xnatural.enet.common.Log;
import org.xnatural.enet.core.ServerTpl;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;

import javax.ws.rs.Path;
import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * netty4 和 resteasy 结合
 */
public class Netty4ResteasyServer extends ServerTpl {
    /**
     * 根 path 前缀
     */
    private String             root;
    /**
     * see: {@link #collect()}
     */
    private List<Class>        scan       = new LinkedList<>();
    private ResteasyDeployment deployment = new ResteasyDeployment();
    private RequestDispatcher  dispatcher;

    public Netty4ResteasyServer() {
        setName("netty4Resteasy");
    }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("服务({})正在运行", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getNs() + ".starting");
        // 先从核心取配置, 然后再启动
        coreEp.fire("sys.env.ns", EC.of("ns", getNs()).sync(), (ec) -> {
            if (ec.result == null) return;
            Map<String, Object> m = (Map) ec.result;
            root = (String) m.getOrDefault("root", "");
            if (attrs.containsKey("scan")) {
                try {
                    for (String c : ((String) attrs.get("scan")).split(",")) {
                        if (c != null && !c.trim().isEmpty()) scan.add(Class.forName(c.trim()));
                    }
                } catch (ClassNotFoundException e) {
                    log.error(e);
                }
            }
            attrs.putAll(m);
        });
        startDeployment();
        initDispatcher();
        log.info("创建({})服务. root: {}", getName(), getRoot());
        collect();
    }


    @EL(name = "server.http-netty4.addHandler")
    private void addHandler(EC ec) {
        initDispatcher();
        // 参考 NettyJaxrsServer
        ChannelPipeline pipeline = ec.getAttr("pipeline", ChannelPipeline.class);
        pipeline.addLast(new RestEasyHttpRequestDecoder(dispatcher.getDispatcher(), root, RestEasyHttpRequestDecoder.Protocol.HTTP));
        pipeline.addLast(new RestEasyHttpResponseEncoder());
        pipeline.addLast(new RequestHandler(dispatcher));
    }


    /**
     * 添加 接口 资源
     * @param o
     * @return
     */
    @EL(name = "${ns}.addResource")
    public Netty4ResteasyServer addResource(Object o) {
        if (o instanceof Class) return this;
        if (o instanceof EC) {
            startDeployment();
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
     * 创建 RequestDispatcher
     */
    private void initDispatcher() {
        if (dispatcher == null) {
            dispatcher = new RequestDispatcher((SynchronousDispatcher)deployment.getDispatcher(), deployment.getProviderFactory(), null);
        }
    }


    private synchronized void startDeployment() {
        if (deployment.getRegistry() != null) return;
        // deployment.setAsyncJobServiceEnabled(true);
        deployment.start();
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
    private void collect() {
        if (scan == null || scan.isEmpty()) return;
        try {
            for (Class clz : scan) {
                String pkg = clz.getPackage().getName();
                File pkgDir = new File(getClass().getClassLoader().getResource(pkg.replaceAll("\\.", "/")).getFile());
                for (File f : pkgDir.listFiles(f -> f.getName().endsWith(".class"))) {
                    load(pkg, f);
                }
            }
        } catch (Exception e) {
            log.error(e, "扫描Handler类出错!");
        }
    }


    private void load(String pkg, File f) throws Exception {
        if (f.isDirectory()) {
            for (File ff : f.listFiles(ff -> ff.getName().endsWith(".class"))) {
                load(pkg + "." + f.getName(), ff);
            }
        } else if (f.isFile() && f.getName().endsWith(".class")) {
            Class<?> clz = getClass().getClassLoader().loadClass(pkg + "." + f.getName().replace(".class", ""));
            if (clz.getAnnotation(Path.class) != null) addResource(clz.newInstance());
        }
    }


    public String getRoot() {
        return root;
    }


    public Netty4ResteasyServer setRoot(String root) {
        if (running.get()) throw new RuntimeException("服务正在运行, 不充许更改");
        this.root = root;
        return this;
    }
}
