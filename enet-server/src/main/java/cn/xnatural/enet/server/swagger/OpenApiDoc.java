package cn.xnatural.enet.server.swagger;

import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;
import io.swagger.v3.jaxrs2.integration.JaxrsApplicationAndAnnotationScanner;
import io.swagger.v3.jaxrs2.integration.JaxrsOpenApiContext;
import io.swagger.v3.oas.integration.OpenApiConfigurationException;
import io.swagger.v3.oas.integration.OpenApiContextLocator;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.tags.Tag;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.xnatural.enet.common.Utils.isEmpty;
import static java.util.Collections.singletonList;

public class OpenApiDoc extends ServerTpl {
    protected final AtomicBoolean running = new AtomicBoolean(false);
    protected   String        root;
    protected   Controller    ctl;
    protected   List<OpenAPI> apis    = new LinkedList<>();


    public OpenApiDoc() { super("openApi"); }
    public OpenApiDoc(String name) { super(name); }


    @EL(name = "sys.starting", async = true)
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("服务正在运行"); return;
        }
        if (ep == null) {ep = new EP(); ep.addListenerSource(this);}
        ep.fire(getName() + ".starting");
        attrs.putAll((Map) ep.fire("env.ns", getName()));
        root = getStr("root", "api-doc");

        ctl = new Controller(this);
        ep.fire("resteasy.addResource", ctl, getRoot()); // addJaxrsDoc(ctl, getRoot(), getName(), null);
        log.info("Started {} Server. url: {}", getName(), ("http://" + ep.fire("http.getHp") + ("/" + getRoot() + "/").replace("//", "/")));
        ep.fire(getName() + ".started");
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.debug("Shutdown '{}' Server", getName());
        try {
            Field f = OpenApiContextLocator.class.getDeclaredField("map");
            f.setAccessible(true);
            ((Map) f.get(OpenApiContextLocator.getInstance())).clear();
        } catch (Exception e) {
            log.error(e);
        }
    }


    @EL(name = "${name}.addJaxrsDoc")
    public void addJaxrsDoc(Object source, String path, String tag, String desc) {
        log.debug("Add jaxr rest api doc. source: {}, path: {}, tag: {}, desc: {}", source, path, tag, desc);
        // 参照: SwaggerLoader
        HashSet<String> rs = new HashSet<>(1); rs.add(source.getClass().getName());
        OpenAPI openApi = null;
        try {
            openApi = new JaxrsOpenApiContext<>().id(getName()).cacheTTL(0L)
                .resourceClasses(rs)
                .openApiConfiguration(
                    new SwaggerConfiguration()
                            .scannerClass(JaxrsApplicationAndAnnotationScanner.class.getName())
                            .resourceClasses(rs).cacheTTL(0L)
                ).init().read();
        } catch (OpenApiConfigurationException e) {
            log.error(e);
        }
        if (openApi == null) return;
        Tag t = new Tag(); t.setName(tag); t.setDescription(desc);
        openApi.addTagsItem(t);
        Map<String, PathItem> rPaths = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, PathItem>> it = openApi.getPaths().entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, PathItem> e = it.next();
            PathItem pi = e.getValue();
            if (pi.getGet() != null && pi.getGet().getTags() == null) {
                pi.getGet().setTags(singletonList(t.getName()));
            }
            if (pi.getPost() != null && pi.getPost().getTags() == null) {
                pi.getPost().setTags(singletonList(t.getName()));
            }
            rPaths.put(("/" + (isEmpty(path) ? "" : path) + "/" + e.getKey()).replace("///", "/").replace("//", "/"), pi);
            it.remove();
        }
        openApi.getPaths().putAll(rPaths);
        log.trace("Add jaxr rest api doc. openApi: {}", openApi);
        this.apis.add(openApi);
    }


    public OpenApiDoc setRoot(String root) {
        if (running.get()) throw new RuntimeException("服务正在运行不能更改");
        this.root = root;
        return this;
    }


    public String getRoot() {
        return root;
    }
}
