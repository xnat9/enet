package org.xnatural.enet;

import org.xnatural.enet.common.Log;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;

import javax.annotation.PostConstruct;
import javax.persistence.EntityManager;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * @author xiangxb, 2019-01-13
 */
@Path("tpl")
public class RestTpl {

    private EP ep;
    final Log log = Log.of(getClass());

    private EntityManager em;


    @EL(name = "sys.started")
    public void init() {
        ep.fire("bean.get", EC.of("type", EntityManager.class), ec -> em = (EntityManager) ec.result);
    }


    @GET
    @Path("insert")
    public void insert() {
        TestEntity e = new TestEntity();
        e.setName("aaaa");
        e.setAge(111);
        em.persist(e);
    }


    @GET
    @Path("find")
    @Produces("application/json")
    public Object find() {
        return em.createQuery("from TestEntity").getResultList();
    }


    @GET
    @Path("get1")
    @Produces("application/json")
    public String get() {
        log.info("get1");
        return "ssssssssss";
    }


    @GET
    @Path("async")
    @Produces("text/plain")
    public CompletionStage<String> async() {
        CompletableFuture<String> f = new CompletableFuture();
        f.complete("ssssssssssss");
        return f;
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
