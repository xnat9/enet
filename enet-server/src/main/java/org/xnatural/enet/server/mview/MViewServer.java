package org.xnatural.enet.server.mview;

import io.swagger.v3.jaxrs2.integration.JaxrsApplicationAndAnnotationScanner;
import io.swagger.v3.jaxrs2.integration.XmlWebOpenApiContext;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.tags.Tag;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.util.*;
import java.util.concurrent.ExecutorService;

/**
 * mview web界面管理模块
 * 对系统中的所有模块管理
 */
public class MViewServer extends ServerTpl {

    /**
     * mview http path前缀. 默认为: mview
     */
    protected String path = "mview";
    protected Controller ctl;

    public MViewServer() {
        setName("mview");
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
        if (r.containsKey("path")) path = r.get("path");
        attrs.putAll(r);

        ctl = new Controller(this);
        log.info("Started {} Server. pathPrefix: {}", getName(), ("/" + getPath() + "/").replace("//", "/"));
        coreEp.fire("resteasy.addResource", ctl, getPath());
    }

    @Override
    public void stop() {
        log.debug("Shutdown '{}' Server", getName());
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
        // TODO
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
        Tag t = new Tag(); t.setName(getName()); t.setDescription("mview rest api");
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
            rPaths.put(("/" + getPath() + "/" + e.getKey()).replace("//", "/"), pi);
            it.remove();
        }
        openApi.getPaths().putAll(rPaths);
        ((List) ec.result).add(openApi);
    }


    public String getPath() {
        return path;
    }
}
