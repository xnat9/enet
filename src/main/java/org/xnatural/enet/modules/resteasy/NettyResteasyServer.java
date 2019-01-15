//package org.xnatural.enet.modules.resteasy;
//
//import org.jboss.netty.bootstrap.ServerBootstrap;
//import org.jboss.netty.channel.ChannelPipelineFactory;
//import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
//import org.jboss.resteasy.core.SynchronousDispatcher;
//import org.jboss.resteasy.plugins.server.netty.HttpServerPipelineFactory;
//import org.jboss.resteasy.plugins.server.netty.NettyJaxrsServer;
//import org.jboss.resteasy.plugins.server.netty.RequestDispatcher;
//import org.xnatural.enet.common.Utils;
//import org.xnatural.enet.core.ServerTpl;
//import org.xnatural.enet.event.EC;
//import org.xnatural.enet.event.EL;
//import org.xnatural.enet.event.EP;
//
//import javax.ws.rs.ApplicationPath;
//import javax.ws.rs.Path;
//import java.io.File;
//import java.net.InetSocketAddress;
//import java.util.Collections;
//import java.util.LinkedList;
//import java.util.List;
//import java.util.Map;
//
///**
// * @author xiangxb, 2019-01-13
// */
//public class NettyResteasyServer extends ServerTpl {
//    /**
//     * http 服务监听端口
//     */
//    private int              port;
//    /**
//     * http 服务绑定地址
//     */
//    private String           hostname;
//    /**
//     * see: {@link #collect()}
//     */
//    private List<Class>      scan = new LinkedList<>();
//    private NettyJaxrsServer server;
//
//
//    public NettyResteasyServer() {
//        setName("nettyRestEasy");
//        setPort(8080);
//        setHostname("localhost");
//    }
//
//
//    @EL(name = "sys.starting")
//    public void start() {
//        if (!running.compareAndSet(false, true)) {
//            log.warn("服务({})正在运行", getName()); return;
//        }
//        if (coreExec == null) initExecutor();
//        if (coreEp == null) coreEp = new EP(coreExec);
//        coreEp.fire(getNs() + ".starting");
//        // 先从核心取配置, 然后再启动
//        coreEp.fire("sys.env.ns", EC.of("ns", getNs()), (ec) -> {
//            if (ec.result != null) {
//                Map<String, Object> m = (Map) ec.result;
//                port = Utils.toInteger(m.get("port"), getPort());
//                hostname = (String) m.getOrDefault("hostname", getHostname());
//                if (attrs.containsKey("scan")) {
//                    try {
//                        for (String c : ((String) attrs.get("scan")).split(",")) {
//                            if (c != null && !c.trim().isEmpty()) scan.add(Class.forName(c.trim()));
//                        }
//                    } catch (ClassNotFoundException e) {
//                        log.warn("未找到类. ({})", e.getMessage());
//                    }
//                }
//                attrs.putAll(m);
//            }
//            if (server == null) server = initServer(); server.start();
//            log.info("创建 {} 服务. hostname: {}, port: {}", getName(), getHostname(), getPort());
//            collect();
//            coreEp.fire(getNs() + ".started");
//        });
//    }
//
//
//    @EL(name = "sys.stopping")
//    public void stop() {
//        if (server != null) {
//            log.info("停止({})服务. name: {}, hostname: {}, port: {}", getName(), getHostname(), getPort());
//            server.stop(); server = null;
//        }
//    }
//
//
//    public static void main(String[] args) {
//        new NettyResteasyServer().addResource(new RestTpl()).start();
//    }
//
//
//    /**
//     * 服务启动后自动扫描此类所在包下的 Handler({@link Path} 注解的类)
//     * @param clz
//     */
//    public NettyResteasyServer scan(Class clz) {
//        if (running.get()) throw new IllegalArgumentException("服务正在运行不允许更改");
//        scan.add(clz);
//        return this;
//    }
//
//
//    /**
//     * 添加 接口 资源
//     * @param o
//     * @return
//     */
//    @EL(name = "${ns}.addResource")
//    public NettyResteasyServer addResource(Object o) {
//        if (o instanceof Class) return this;
//        if (server == null) server = initServer();
//        Object s;
//        if (o instanceof EC) s = ((EC) o).getAttr("source");
//        else s = o;
//        if (server.getDeployment().getRegistry() == null) server.getDeployment().getResources().add(s);
//        else server.getDeployment().getRegistry().addSingletonResource(s);
//        return this;
//    }
//
//
//    /**
//     * 创建 netty http 服务
//     * @return
//     */
//    private NettyJaxrsServer initServer() {
//        return new NettyJaxrsServer() {
//            @Override
//            public void start() {
//                deployment.start();
//                // dynamically set the root path (the user can rewrite it by calling setRootResourcePath)
//                if (deployment.getApplication() != null) {
//                    ApplicationPath appPath = deployment.getApplication().getClass().getAnnotation(ApplicationPath.class);
//                    if (appPath != null && (root == null || "".equals(root))) {
//                        // annotation is present and original root is not set
//                        setRootResourcePath(appPath.value());
//                    }
//                }
//                RequestDispatcher dispatcher = new RequestDispatcher((SynchronousDispatcher)deployment.getDispatcher(), deployment.getProviderFactory(), domain);
//
//                // Configure the server.
//                bootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(coreExec, getInteger("threads-boos", 1), coreExec, getInteger("threads-worker", 1)));
//
//                ChannelPipelineFactory factory = new HttpServerPipelineFactory(dispatcher, root, -1, getInteger("maxRequestSize", 1024), true, Collections.emptyList());
//                // Set up the event pipeline factory.
//                bootstrap.setPipelineFactory(factory);
//
//                // Add custom bootstrap options
//                // bootstrap.setOptions(channelOptions);
//
//                // Bind and start to accept incoming connections.
//                final InetSocketAddress socketAddress = new InetSocketAddress(NettyResteasyServer.this.getHostname(), NettyResteasyServer.this.getPort());
//                channel = bootstrap.bind(socketAddress);
//                // allChannels.add(channel);
//                runtimePort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
//            }
//        };
//    }
//
//
//    /**
//     * 收集 {@link #scan} 类对的包下边所有的 有注解{@link Path}的类
//     */
//    private void collect() {
//        if (scan == null || scan.isEmpty()) return;
//        try {
//            for (Class clz : scan) {
//                String pkg = clz.getPackage().getName();
//                File pkgDir = new File(getClass().getClassLoader().getResource(pkg.replaceAll("\\.", "/")).getFile());
//                for (File f : pkgDir.listFiles(f -> f.getName().endsWith(".class"))) {
//                    load(pkg, f);
//                }
//            }
//        } catch (Exception e) {
//            log.error(e, "扫描Handler类出错!");
//        }
//    }
//
//
//    private void load(String pkg, File f) throws Exception {
//        if (f.isDirectory()) {
//            for (File ff : f.listFiles(ff -> ff.getName().endsWith(".class"))) {
//                load(pkg + "." + f.getName(), ff);
//            }
//        } else if (f.isFile() && f.getName().endsWith(".class")) {
//            Class<?> clz = getClass().getClassLoader().loadClass(pkg + "." + f.getName().replace(".class", ""));
//            if (clz.getAnnotation(Path.class) != null) addResource(clz.newInstance());
//        }
//    }
//
//
//    public String getHostname() {
//        return hostname;
//    }
//
//
//    public NettyResteasyServer setHostname(String hostname) {
//        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新主机名");
//        attrs.put("hostname", hostname); this.hostname = hostname;
//        return this;
//    }
//
//
//    public int getPort() {
//        return port;
//    }
//
//
//    public NettyResteasyServer setPort(int port) {
//        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新端口");
//        attrs.put("port", port); this.port = port;
//        return this;
//    }
//}
