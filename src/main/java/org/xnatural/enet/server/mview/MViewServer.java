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
    private String path = "mview";
    private Controller ctl;

    public MViewServer() {
        setName("mview");
    }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getNs() + ".starting");
        // 先从核心取配置, 然后再启动
        coreEp.fire("env.ns", EC.of("ns", getNs()).sync(), (ec) -> {
            if (ec.result != null) {
                Map<String, Object> m = (Map) ec.result;
                if (m.containsKey("path")) path = m.get("path").toString();
                attrs.putAll(m);
            }
        });
        ctl = new Controller(this);
        log.info("Started {} Server. pathPrefix: {}", getName(), ("/" + getPath() + "/").replace("//", "/"));
        coreEp.fire("resteasy.addResource", EC.of("source", ctl).attr("path", getPath()));
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.info("Shutdown '{}' Server", getName());
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
        // TODO
    }



    @EL(name = "server.swagger.openApi")
    private void openApi(EC ec) throws Exception {
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
