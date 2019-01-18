package org.xnatural.enet.server.mvc.resteasy;

import org.xnatural.enet.common.Log;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import java.io.File;

/**
 * @author xiangxb, 2019-01-13
 */
@Path("")
public class RestTpl {

    final Log log = Log.of(getClass());

    @GET
    @Path("get1")
    @Produces("application/json")
    public String get() {
        log.info("get1");
        return "ssssssssss";
    }


    @GET
    @Path("js/lib/{fName}")
    public Response jsLib(@PathParam("fName") String fName) {
        File f = new File(getClass().getClassLoader().getResource("static/js/lib/" + fName).getFile());
        return Response.ok()
                .header("Content-Disposition", "attachment; filename=" + f.getName())
                .header("Cache-Control", "max-age=60")
                .build();
    }
}
