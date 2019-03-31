package org.xnatural.enet.server.resteasy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import org.jboss.resteasy.core.InjectorFactoryImpl;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.core.ValueInjector;
import org.jboss.resteasy.plugins.server.netty.*;
import org.jboss.resteasy.spi.*;
import org.jboss.resteasy.spi.metadata.Parameter;
import org.xnatural.enet.common.Utils;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import javax.annotation.PostConstruct;
import javax.ws.rs.Path;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.NewCookie;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.jboss.resteasy.plugins.server.netty.RestEasyHttpRequestDecoder.Protocol.HTTP;
import static org.jboss.resteasy.util.FindAnnotation.findAnnotation;
import static org.xnatural.enet.common.Utils.invoke;

/**
 * netty4 和 resteasy 结合
 */
public class NettyResteasy extends ServerTpl {
    /**
     * 根 path 前缀
     */
    protected String             rootPath;
    /**
     * 表示 session 的 cookie 名字
     */
    protected String sessionCookieName;
    /**
     * session 是否可用
     */
    protected boolean enableSession;
    /**
     * see: {@link #collect()}
     */
    protected List<Class>        scan       = new LinkedList<>();
    protected ResteasyDeployment deployment = new ResteasyDeployment();
    protected RequestDispatcher  dispatcher;


    public NettyResteasy() { setName("resteasy-netty"); }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getName() + ".starting");
        attrs.putAll((Map) coreEp.fire("env.ns", "mvc", getName()));
        enableSession = Utils.toBoolean(coreEp.fire("env.getAttr", "session.enabled"), true);
        rootPath = getStr("rootPath", "/");
        sessionCookieName = getStr("sessionCookieName", "sId");
        for (String c : getStr("scan", "").split(",")) {
            try {
                if (c != null && !c.trim().isEmpty()) scan.add(Class.forName(c.trim()));
            } catch (ClassNotFoundException e) {
                log.error(e);
            }
        }

        startDeployment(); initDispatcher(); collect();
        coreEp.fire(getName() + ".started");
        log.info("Started {} Server. rootPath: {}", getName(), getRootPath());
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.debug("Shutdown '{}' Server", getName());
        dispatcher = null; deployment.stop(); deployment = null;
    }


    @EL(name = "http-netty.addHandler", async = false)
    protected void addHandler(ChannelPipeline cp) {
        initDispatcher();
        // 参考 NettyJaxrsServer
        cp.addLast(new RestEasyHttpRequestDecoder(dispatcher.getDispatcher(), rootPath, HTTP));
        cp.addLast(new RestEasyHttpResponseEncoder());
        cp.addLast(new RequestHandler(dispatcher) {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                coreExec.execute(() -> process(ctx, msg));
            }
        });
    }


    /**
     * 处理请求
     * @param ctx
     * @param msg
     */
    protected void process(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof NettyHttpRequest) {
            NettyHttpRequest req = (NettyHttpRequest) msg;
            NettyHttpResponse resp = req.getResponse();
            try {
                if (isEnableSession()) { // 添加session控制
                    Cookie c = req.getHttpHeaders().getCookies().get(getSessionCookieName());
                    String sId;
                    if (c == null || Utils.isEmpty(c.getValue())) {
                        sId = UUID.randomUUID().toString().replace("-", "");
                        ((NettyHttpRequest) msg).getResponse().addNewCookie(
                            new NewCookie(
                                getSessionCookieName(), sId, "/", (String) null, 1, (String) null,
                                (int) TimeUnit.MINUTES.toSeconds((Integer) coreEp.fire("session.getExpire") + 5)
                                , null, false, false
                            )
                        );
                    } else sId = c.getValue();
                    coreEp.fire("session.access", sId);
                    ((NettyHttpRequest) msg).setAttribute(getSessionCookieName(), sId);
                }

                dispatcher.service(ctx, req, resp, true);
            } catch (Failure e1) {
                resp.reset(); resp.setStatus(e1.getErrorCode());
            } catch (UnhandledException e) {
                resp.reset(); resp.setStatus(500);
                if (e.getCause() != null) log.error(e.getCause());
                else log.error(e);
            } catch (Exception ex) {
                resp.reset(); resp.setStatus(500);
                log.error(ex);
            } finally {
                if (!req.getAsyncContext().isSuspended()) {
                    try { resp.finish(); }
                    catch (IOException e) { log.error(e); }
                }
                req.releaseContentBuffer();
            }
        }
    }


    /**
     * 添加 接口 资源
     * @param source
     * @return
     */
    @EL(name = {"resteasy.addResource"})
    public NettyResteasy addResource(Object source, String path) {
        log.debug("resteasy.addResource. source: {}, path: {}", source, path);
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
            deployment.setInjectorFactory(new InjectorFactoryImpl() {
                @Override
                public ValueInjector createParameterExtractor(Parameter param, ResteasyProviderFactory pf) {
                    SessionAttr attrAnno = findAnnotation(param.getAnnotations(), SessionAttr.class);
                    if (findAnnotation(param.getAnnotations(), SessionId.class) != null) {
                        return new ValueInjector() {
                            @Override
                            public Object inject() { return null; }
                            @Override
                            public Object inject(HttpRequest request, HttpResponse response) {
                                return request.getAttribute(getSessionCookieName());
                            }
                        };
                    } else if (attrAnno != null) {
                        return new ValueInjector() {
                            @Override
                            public Object inject() { return null; }
                            @Override
                            public Object inject(HttpRequest request, HttpResponse response) {
                                return coreEp.fire("session.get", request.getAttribute(getSessionCookieName()), attrAnno.value());
                            }
                        };
                    }
                    return super.createParameterExtractor(param, pf);
                }
            });
            deployment.start();
        }
    }


    /**
     * 服务启动后自动扫描此类所在包下的 Handler({@link Path} 注解的类)
     * @param clz
     */
    public NettyResteasy scan(Class clz) {
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
            log.debug("collect resteasy resource. scan: {}", scan);
            for (Class clz : scan) {
                String pkg = clz.getPackage().getName();
                File pkgDir = new File(getClass().getClassLoader().getResource(pkg.replaceAll("\\.", "/")).getFile());
                File[] arr = pkgDir.listFiles(f -> f.getName().endsWith(".class"));
                if (arr != null) for (File f : arr) load(pkg, f);
            }
        } catch (Exception e) {
            log.error(e, "scan resteasy resource error!");
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
        log.trace("createAndInitSource. class: {}", clz);
        Object o = clz.newInstance();
        Class<? extends Object> c = clz;
        try {
            loop: do {
                for (Field f : c.getDeclaredFields()) {
                    if (EP.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true); f.set(o, coreEp);
                            break loop;
                    }
                }
                c = c.getSuperclass();
            } while (c != null);
        } catch (IllegalAccessException e) {
            log.error(e);
        }

        c = clz;
        loop: do {
            for (Method m : c.getDeclaredMethods()) {
                PostConstruct an = m.getAnnotation(PostConstruct.class);
                if (an == null) continue;
                invoke(m, o); break loop;
            }
            c = c.getSuperclass();
        } while (c != null);
        coreEp.addListenerSource(o);
        return o;
    }


    public String getRootPath() {
        return rootPath;
    }


    public NettyResteasy setRootPath(String rootPath) {
        if (running.get()) throw new RuntimeException("服务正在运行, 不充许更改");
        this.rootPath = rootPath;
        return this;
    }


    public String getSessionCookieName() {
        return sessionCookieName;
    }


    public NettyResteasy setSessionCookieName(String sessionCookieName) {
        if (running.get()) throw new RuntimeException("不允许运行时更改");
        if (sessionCookieName == null || sessionCookieName.isEmpty()) throw new NullPointerException("参数为空");
        this.sessionCookieName = sessionCookieName;
        return this;
    }


    public boolean isEnableSession() {
        return enableSession;
    }
}
