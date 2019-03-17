package org.xnatural.enet.server.swagger;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import org.xnatural.enet.common.Log;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EP;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;

@Path("/")
public class Controller {

    final Log log = Log.of(getClass());
    private EP ep;
    private SwaggerServer server;


    public Controller(SwaggerServer server) {
        this.server = server;
        this.ep = server.getCoreEp();
    }


    @GET
    @Path("data")
    @Produces("application/json; charset=utf-8")
    public void data(@Suspended final AsyncResponse resp) {
        OpenAPI openApi = new OpenAPI();
        openApi.setPaths(new Paths()); openApi.setTags(new LinkedList<>());
        ep.fire("swagger.openApi", EC.of(this).result(new LinkedList<OpenAPI>()),
                ec -> {
                    server.apis.forEach(o -> {
                        openApi.getPaths().putAll(o.getPaths());
                        openApi.getTags().addAll(o.getTags());
                    });
                    ((List<OpenAPI>) ec.result).forEach(o -> {
                        openApi.getPaths().putAll(o.getPaths());
                        openApi.getTags().addAll(o.getTags());
                    });
                    resp.resume(openApi);
                });
    }


    @GET
    @Path("")
    public Response index() {
        return Response.ok(findViewFile("index.html"))
                .type("text/html; charset=utf-8")
                .header("Cache-Control", "max-age=5")
                .build();
    }


    @GET
    @Path("{fName:.*}")
    public Response file(@PathParam("fName") String fName) {
        InputStream f = findViewFile("" + fName);
        if (f == null) return Response.status(404).build();
        String type = null;
        if (fName.endsWith(".js")) type = "application/javascript; charset=utf-8";
        else if (fName.endsWith(".css")) type = "text/css; charset=utf-8";
        else if (fName.endsWith(".html")) type = "text/html; charset=utf-8";
        return Response.ok(f)
                .type(type)
                .header("Cache-Control", "max-age=60")
                .build();
    }


    protected InputStream findViewFile(String fPath) {
        URL r = getClass().getClassLoader().getResource(getClass().getPackage().getName().replaceAll("\\.", "/") + "/ui/" + fPath);
        try {
            return r == null ? null : r.openStream();
        } catch (IOException e) {
            return null;
        }
    }
}
