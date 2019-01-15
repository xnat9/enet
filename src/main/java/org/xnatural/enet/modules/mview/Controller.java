package org.xnatural.enet.modules.mview;

import org.xnatural.enet.common.Log;

import javax.ws.rs.CookieParam;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;
import java.io.File;
import java.util.Date;

@Path("mview")
public class Controller {

    final Log log = Log.of(getClass());


    @GET
    @Path("")
    public Response index() {
        return Response.ok(findViewFile("index.html"))
                .type("text/html; charset=utf-8")
                .header("Cache-Control", "max-age=1")
                .build();
    }


    @GET
    @Path("js/{fName}")
    public Response js(@PathParam("fName") String fName) {
        return Response.ok(findViewFile("js/" + fName))
                .type("application/javascript; charset=utf-8")
                .header("Cache-Control", "max-age=60")
                .build();
    }


    @GET
    @Path("css/{fName}")
    public Response css(@PathParam("fName") String fName) {
        return Response.ok(findViewFile("css/" + fName))
                .type("text/css; charset=utf-8")
                .header("Cache-Control", "max-age=60")
                .build();
    }


    private File findViewFile(String fPath) {
        return new File(getClass().getClassLoader().getResource(getClass().getPackage().getName().replaceAll("\\.", "/") + "/view/" + fPath).getFile());
    }
}
