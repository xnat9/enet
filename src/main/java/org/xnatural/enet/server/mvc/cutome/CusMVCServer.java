package org.xnatural.enet.server.mvc.cutome;

import org.xnatural.enet.server.ServerTpl;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.mvc.cutome.anno.Route;

import java.io.File;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * mvc 模块
 */
public class CusMVCServer extends ServerTpl {
    private Dispatcher dispatcher;
    /**
     * see: {@link #collect()}
     */
    private Class      scan;


    public CusMVCServer() {
        setName("mvc");
    }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("服务正在运行"); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire("env.ns", EC.of("ns", getNs()), (ec) -> {
            if (ec.result != null) {
                attrs.putAll((Map) ec.result);
                if (attrs.containsKey("scan")) {
                    try {
                        scan = Class.forName((String) attrs.get("scan"));
                    } catch (ClassNotFoundException e) {
                        log.error(e);
                    }
                }
            }
            dispatcher = new Dispatcher(coreExec);
            log.info("创建 {} 服务", getName());
            collect();
            coreEp.fire(getNs() + ".started");
        });
    }


    @EL(name = "sys.stopping")
    public void stop() {
        coreEp.fire(getNs() + ".stopping");
        log.info("MVC server stopping");
    }


    /**
     * 服务启动后自动扫描此类所在包下的 Handler({@link org.xnatural.enet.server.mvc.cutome.anno.Route} 注解的类)
     * @param clz
     */
    public CusMVCServer scan(Class clz) {
        if (running.get()) throw new IllegalArgumentException("服务正在运行不允许更改");
        scan = clz;
        return this;
    }


    /**
     * 收集 {@link #scan} 类对的包下边所有的 有注解{@link org.xnatural.enet.server.mvc.cutome.anno.Route}的类
     */
    private void collect() {
        if (scan == null) return;
        try {
            String pkg = scan.getPackage().getName();
            File pkgDir = new File(getClass().getClassLoader().getResource(pkg.replaceAll("\\.", "/")).getFile());
            for (File f : pkgDir.listFiles(f -> f.getName().endsWith(".class"))) {
                load(pkg, f);
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
            if (clz.getAnnotation(Route.class) != null) resolveHandler(clz.newInstance());
        }
    }


    public CusMVCServer resolveHandler(Object source) {
        if (source instanceof Class) return this;
        dispatcher.resolveHandler(source);
        return this;
    }


    @EL(name = "${ns}.dispatch", async = false)
    private void dispatch(EC ec) {
        dispatcher.dispatch(
                ec.getAttr("path", String.class),
                ec.getAttr("method", String.class),
                ec.getAttr("resolver", Function.class),
                ec.getAttr("responseConsumer", Consumer.class),
                ec.getAttr("request")
        );
    }


    @EL(name = "${ns}.addHandlerSource")
    private void resolveHandler(EC ec) {
        dispatcher.resolveHandler(ec.getAttr("source"));
    }


    public Class getScan() {
        return scan;
    }
}
