package cn.xnatural.enet.server.mview;

import cn.xnatural.enet.common.Log;
import cn.xnatural.enet.event.EC;
import cn.xnatural.enet.event.EP;
import com.alibaba.fastjson.JSON;
import org.apache.commons.io.IOUtils;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;

@Path("/")
public class Controller {

    final Log log = Log.of(getClass());

    private MViewServer server;
    private EP          ep;


    Controller(MViewServer server) {
        this.server = server;
        this.ep = server.getEp();
    }


    @GET @Path("/")
    public Response index() throws Exception {
        Map<String, Object> model = new TreeMap<>(Comparator.naturalOrder());
        model.put("rootPath", server.getPath());
        ep.fire("sys.info", EC.of(this).sync().completeFn(ec -> {
            ((Map<String, Object>) ec.result).forEach((k, v) -> model.put(k, JSON.toJSONString(v)));
        }));
        return Response.ok(render(IOUtils.toString(findViewFile("index.html"), "utf-8"), model))
                .type("text/html; charset=utf-8")
                // .header("Cache-Control", "max-age=1")
                .build();
    }


    @GET @Path("server/{sName}")
    public Response server(@PathParam("sName") String sName) throws Exception {
        // 1. _this: 模块对象本身
        // 2. properties: [{name: 'attr1',set: true, desc: '说明'}]
        // 3. methods: [{name: 'start', desc: '启动', annotations: '@EL,@Path'}]
        Map<String, Object> model = new HashMap<>();
        model.put("rootPath", server.getPath());
        model.put("serverName", sName);
        ep.fire("server." + sName +".info", EC.of(this).sync().completeFn(ec -> { // 获取模块服务的信息
            Map<String, Object> m = (Map<String, Object>) ec.result;
            if (m != null) {
                model.put("_this", m.get("_this").toString());
                model.put("properties", JSON.toJSONString(m.get("properties")));
                model.put("methods", JSON.toJSONString(m.get("methods")));
            }
        }));
        return Response.ok(render(IOUtils.toString(findViewFile("serverTpl.html"), "utf-8"), model))
                .type("text/html; charset=utf-8")
                .build();
    }


    @GET @Path("js/{fName:.*}")
    public Response js(@PathParam("fName") String fName) {
        InputStream f = findViewFile("js/" + fName);
        if (f == null) return Response.status(404).build();
        return Response.ok(f)
                .type("application/javascript; charset=utf-8")
                .header("Cache-Control", "max-age=60")
                .build();
    }


    @GET @Path("css/{fName:.*}")
    public Response css(@PathParam("fName") String fName) {
        InputStream f = findViewFile("css/" + fName);
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


    private InputStream findViewFile(String fPath) {
        URL r = getClass().getClassLoader().getResource(getClass().getPackage().getName().replaceAll("\\.", "/") + "/ui/" + fPath);
        try {
            return r == null ? null : r.openStream();
        } catch (IOException e) {
            return null;
        }
    }
}
