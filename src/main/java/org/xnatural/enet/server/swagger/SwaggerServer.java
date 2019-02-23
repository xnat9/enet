package org.xnatural.enet.server.swagger;

import io.swagger.v3.jaxrs2.integration.JaxrsApplicationAndAnnotationScanner;
import io.swagger.v3.jaxrs2.integration.XmlWebOpenApiContext;
import io.swagger.v3.oas.integration.OpenApiContextLocator;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.tags.Tag;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ExecutorService;

public class SwaggerServer extends ServerTpl {

    protected String root;
    protected Controller ctl;


    public SwaggerServer() {
        setName("swagger");
        setRoot("api-doc");
    }


    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("服务正在运行"); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getName() + ".starting");
        // 先从核心取配置, 然后再启动
        Map<String, String> r = (Map) coreEp.fire("env.ns", getNs());
        root = r.getOrDefault("root", getRoot());
        attrs.putAll(r);

        ctl = new Controller(this);
        coreEp.fire("resteasy.addResource", ctl, getRoot());
        log.info("Started {} Server. pathPrefix: {}", getName(), ("/" + getRoot() + "/").replace("//", "/"));
        coreEp.fire(getName() + ".started");
    }


    @Override
    public void stop() {
        log.debug("Shutdown '{}' Server", getName());
        try {
            Field f = OpenApiContextLocator.class.getDeclaredField("map");
            f.setAccessible(true);
            ((Map) f.get(OpenApiContextLocator.getInstance())).clear();
        } catch (Exception e) {
            log.warn(e, "");
        }
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }


    @EL(name = "swagger.openApi")
    protected void openApi(EC ec) throws Exception {
        // 参照: SwaggerLoader
        HashSet<String> rs = new HashSet<>(1); rs.add(ctl.getClass().getName());
        OpenAPI openApi = new XmlWebOpenApiContext().id(getName()).cacheTTL(0L).resourceClasses(rs).openApiConfiguration(
                new SwaggerConfiguration()
                        .scannerClass(JaxrsApplicationAndAnnotationScanner.class.getName())
                        .resourceClasses(rs).cacheTTL(0L)
        ).init().read();
        if (openApi == null) return;
        Tag t = new Tag(); t.setName(getName()); t.setDescription("swagger rest api");
        openApi.addTagsItem(t);
        Map<String, PathItem> rPaths = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, PathItem>> it = openApi.getPaths().entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, PathItem> e = it.next();
            PathItem pi = e.getValue();
            if (pi.getGet() != null && pi.getGet().getTags() == null) {
                pi.getGet().setTags(Collections.singletonList(t.getName()));
            }
            if (pi.getPost() != null && pi.getPost().getTags() == null) {
                pi.getPost().setTags(Collections.singletonList(t.getName()));
            }
            rPaths.put(("/" + getRoot() + "/" + e.getKey()).replace("//", "/"), pi);
            it.remove();
        }
        openApi.getPaths().putAll(rPaths);
        ((List) ec.result).add(openApi);
    }


    public SwaggerServer setRoot(String root) {
        if (running.get()) throw new RuntimeException("服务正在运行不能更改");
        this.root = root;
        attrs.put("root", root);
        return this;
    }


    public String getRoot() {
        return root;
    }
}
