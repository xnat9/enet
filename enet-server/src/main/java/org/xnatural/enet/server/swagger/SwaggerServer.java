package org.xnatural.enet.server.swagger;

import io.swagger.v3.jaxrs2.integration.JaxrsApplicationAndAnnotationScanner;
import io.swagger.v3.jaxrs2.integration.XmlWebOpenApiContext;
import io.swagger.v3.oas.integration.OpenApiConfigurationException;
import io.swagger.v3.oas.integration.OpenApiContextLocator;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.tags.Tag;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.lang.reflect.Field;
import java.util.*;

import static java.util.Collections.singletonList;
import static org.xnatural.enet.common.Utils.isEmpty;

public class SwaggerServer extends ServerTpl {

    protected String root;
    protected Controller ctl;
    protected List<OpenAPI> apis = new LinkedList<>();


    public SwaggerServer() {
        setName("swagger");
    }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("服务正在运行"); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getName() + ".starting");
        attrs.putAll((Map) coreEp.fire("env.ns", getName()));
        root = getStr("root", "api-doc");

        ctl = new Controller(this);
        coreEp.fire("resteasy.addResource", ctl, getRoot()); // addJaxrsDoc(ctl, getRoot(), getName(), null);
        log.info("Started {} Server. pathPrefix: {}", getName(), ("/" + getRoot() + "/").replace("//", "/"));
        coreEp.fire(getName() + ".started");
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.debug("Shutdown '{}' Server", getName());
        try {
            Field f = OpenApiContextLocator.class.getDeclaredField("map");
            f.setAccessible(true);
            ((Map) f.get(OpenApiContextLocator.getInstance())).clear();
        } catch (Exception e) {
            log.warn(e, "");
        }
    }


    @EL(name = "${name}.addJaxrsDoc")
    public void addJaxrsDoc(Object source, String path, String tag, String desc) {
        // 参照: SwaggerLoader
        HashSet<String> rs = new HashSet<>(1); rs.add(source.getClass().getName());
        OpenAPI openApi = null;
        try {
            openApi = new XmlWebOpenApiContext().id(getName()).cacheTTL(0L).resourceClasses(rs).openApiConfiguration(
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

        this.apis.add(openApi);
    }


    public SwaggerServer setRoot(String root) {
        if (running.get()) throw new RuntimeException("服务正在运行不能更改");
        this.root = root;
        return this;
    }


    public String getRoot() {
        return root;
    }
}
