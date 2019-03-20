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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.jboss.resteasy.util.FindAnnotation.findAnnotation;

/**
 * netty4 和 resteasy 结合
 */
public class Netty4Resteasy extends ServerTpl {
    /**
     * 根 path 前缀
     */
    protected String             rootPath;
    /**
     * 表示 session 的 cookie 名字
     */
    protected String sessionCookieName;
    /**
     * 是否启用session
     */
    protected boolean enableSession = true;
    /**
     * see: {@link #collect()}
     */
    protected List<Class>        scan       = new LinkedList<>();
    protected ResteasyDeployment deployment = new ResteasyDeployment();
    protected RequestDispatcher  dispatcher;


    public Netty4Resteasy() { setName("resteasy-netty"); }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getName() + ".starting");
        attrs.putAll((Map) coreEp.fire("env.ns", "mvc", getName()));
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


    @Override
    public void stop() {
        log.debug("Shutdown '{}' Server", getName());
        dispatcher = null; deployment.stop(); deployment = null;
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }


    @EL(name = "http-netty.addHandler", async = false)
    protected void addHandler(ChannelPipeline cp) {
        initDispatcher();
        // 参考 NettyJaxrsServer
        cp.addLast(new RestEasyHttpRequestDecoder(dispatcher.getDispatcher(), rootPath, RestEasyHttpRequestDecoder.Protocol.HTTP));
        cp.addLast(new RestEasyHttpResponseEncoder());
        cp.addLast(new RequestHandler(dispatcher) {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
                coreExec.execute(() -> {
                    if (msg instanceof NettyHttpRequest) {
                        NettyHttpRequest request = (NettyHttpRequest) msg;
                        NettyHttpResponse response = request.getResponse();
                        try {
                            if (isEnableSession()) { // 添加session控制
                                Cookie c = request.getHttpHeaders().getCookies().get(getSessionCookieName());
                                String sId;
                                if (c == null || Utils.isEmpty(c.getValue())) {
                                    sId = UUID.randomUUID().toString().replace("-", "");
                                    ((NettyHttpRequest) msg).getResponse().addNewCookie(
                                        new NewCookie(
                                            getSessionCookieName(), sId, "/", (String) null, 1, (String) null,
                                            (int) TimeUnit.MINUTES.toSeconds((Integer) coreEp.fire("session.getExpire") + 10)
                                            , null, false, false
                                        )
                                    );
                                } else sId = c.getValue();
                                coreEp.fire("session.access", sId);
                                ((NettyHttpRequest) msg).setAttribute(getSessionCookieName(), sId);
                            }

                            dispatcher.service(ctx, request, response, true);
                        } catch (Failure e1) {
                            response.reset();
                            response.setStatus(e1.getErrorCode());
                        } catch (UnhandledException e) {
                            response.reset();
                            response.setStatus(500);
                            if (e.getCause() != null) log.error(e.getCause());
                            else log.error(e);
                        } catch (Exception ex) {
                            response.reset();
                            response.setStatus(500);
                            log.error(ex);
                        } finally {
                            if (!request.getAsyncContext().isSuspended()) {
                                try {
                                    response.finish();
                                } catch (IOException e) {
                                    log.error(e);
                                }
                            }
                            request.releaseContentBuffer();
                        }
                    }
                });
            }
        });
    }


    /**
     * 添加 接口 资源
     * @param source
     * @return
     */
    @EL(name = {"resteasy.addResource"})
    public Netty4Resteasy addResource(Object source, String path) {
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
    public Netty4Resteasy scan(Class clz) {
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


    public Netty4Resteasy setRootPath(String rootPath) {
        if (running.get()) throw new RuntimeException("服务正在运行, 不充许更改");
        this.rootPath = rootPath;
        return this;
    }


    public String getSessionCookieName() {
        return sessionCookieName;
    }


    public Netty4Resteasy setSessionCookieName(String sessionCookieName) {
        if (running.get()) throw new RuntimeException("不允许运行时更改");
        if (sessionCookieName == null || sessionCookieName.isEmpty()) throw new NullPointerException("参数为空");
        this.sessionCookieName = sessionCookieName;
        return this;
    }


    public Netty4Resteasy setEnableSession(boolean enableSession) {
        if (running.get()) throw new RuntimeException("运行时不允许更改");
        this.enableSession = enableSession;
        return this;
    }


    public boolean isEnableSession() {
        return enableSession;
    }
}
