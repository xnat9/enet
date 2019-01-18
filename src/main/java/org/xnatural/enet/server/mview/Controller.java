package org.xnatural.enet.server.mview;

import com.alibaba.fastjson.JSON;
import org.apache.commons.io.IOUtils;
import org.xnatural.enet.common.Log;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EP;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Path("")
public class Controller {

    final Log log = Log.of(getClass());

    private MViewServer server;
    private EP          ep;


    Controller(MViewServer server) {
        this.server = server;
        this.ep = server.getCoreEp();
    }


    @GET
    @Path("")
    public Response index() throws Exception {
        Map<String, Object> model = new HashMap<>();
        model.put("rootPath", server.getPath());
        ep.fire("sys.info", EC.of(this).sync(), ec -> {
            ((Map<String, Object>) ec.result).forEach((k, v) -> model.put(k, JSON.toJSONString(v)));
        });
        return Response.ok(render(IOUtils.toString(new FileInputStream(findViewFile("index.html")), "utf-8"), model))
                .type("text/html; charset=utf-8")
                // .header("Cache-Control", "max-age=1")
                .build();
    }


    @GET
    @Path("server/{sName}")
    public Response server(@PathParam("sName") String sName) throws Exception {
        // 1. _this: 模块对象本身
        // 2. properties: [{name: 'attr1',set: true, desc: '说明'}]
        // 3. methods: [{name: 'start', desc: '启动', annotations: '@EL,@Path'}]
        Map<String, Object> model = new HashMap<>();
        model.put("rootPath", server.getPath());
        model.put("serverName", sName);
        ep.fire("server." + sName +".info", EC.of(this).sync(), ec -> { // 获取模块服务的信息
            Map<String, Object> m = (Map<String, Object>) ec.result;
            if (m != null) {
                model.put("_this", m.get("_this").toString());
                model.put("properties", JSON.toJSONString(m.get("properties")));
                model.put("methods", JSON.toJSONString(m.get("methods")));
            }
        });
        return Response.ok(render(IOUtils.toString(new FileInputStream(findViewFile("serverTpl.html")), "utf-8"), model))
                .type("text/html; charset=utf-8")
                .build();
    }


    @GET
    @Path("js/{fName:.*}")
    public Response js(@PathParam("fName") String fName) {
        File f = findViewFile("js/" + fName);
        if (f == null) return Response.status(404).build();
        return Response.ok(f)
                .type("application/javascript; charset=utf-8")
                .header("Cache-Control", "max-age=60")
                .build();
    }


    @GET
    @Path("css/{fName:.*}")
    public Response css(@PathParam("fName") String fName) {
        File f = findViewFile("css/" + fName);
        if (f == null) return Response.status(404).build();
        return Response.ok(f)
                .type("text/css; charset=utf-8")
                .header("Cache-Control", "max-age=60")
                .build();
    }


    private String render(String pHtmlTpl, final Map pModel) {
        // 自定义一个属性替代类是因为, 如果有个属性的值是个String 值为: "${", 那么其 originValue="${",
        // org.springframework.util.PropertyPlaceholderHelper 这个属性替代器会对这个originValue 做解析(它会一直匹配到后面的 "}"), 这里不应该对属性值做解析.
        return new PropertyPlaceholderHelper("${", "}").replacePlaceholders(
                pHtmlTpl, placeholderName -> Objects.toString(pModel.get(placeholderName), "")
        );
    }


    private File findViewFile(String fPath) {
        URL r = getClass().getClassLoader().getResource(getClass().getPackage().getName().replaceAll("\\.", "/") + "/view/" + fPath);
        return r == null ? null : new File(r.getFile());
    }
}
