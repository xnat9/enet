package org.xnatural.enet;

import org.xnatural.enet.common.Log;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.ws.rs.*;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.Response;
import java.io.File;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * @author xiangxb, 2019-01-13
 */
@Path("tpl")
public class RestTpl {

    private EP ep;
    final Log log = Log.of(getClass());

    private EntityManagerFactory emf;


    @EL(name = "sys.started")
    public void init() {
        emf = (EntityManagerFactory) ep.fire("bean.get", EntityManagerFactory.class);
    }


    @GET
    @Path("insert")
    public void insert() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        TestEntity e = new TestEntity();
        e.setName("aaaa");
        e.setAge(111);
        em.persist(e);

        em.getTransaction().commit();
        em.close();
    }


    @GET
    @Path("find")
    @Produces("application/json")
    public Object find() {
        return emf.createEntityManager().createQuery("from TestEntity").getResultList();
    }


//    public Object session(@CookieParam("sessionid") Cookie cookie) {
//        cookie.getValue();
//    }


    @GET
    @Path("get1")
    @Produces("application/json")
    public String get() {
        log.info("get1");
        return "ssssssssss";
    }


    @GET
    @Path("cache")
    @Produces("application/json")
    public Object cache() {
        Object r = ep.fire("cache.get", "test", "key1");
        if (r == null) ep.fire("cache.add", "test", "key1", "qqqqqqqqqq");
        return r;
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