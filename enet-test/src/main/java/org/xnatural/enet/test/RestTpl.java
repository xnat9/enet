package org.xnatural.enet.test;

import org.xnatural.enet.common.Log;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.dao.hibernate.TransWrapper;
import org.xnatural.enet.server.resteasy.SessionAttr;
import org.xnatural.enet.server.resteasy.SessionId;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * @author xiangxb, 2019-01-13
 */
@Path("tpl")
public class RestTpl {

    private EP ep;
    final Log log = Log.of(getClass());

    private TestRepo     testRepo;
    private TransWrapper transManager;


    @EL(name = "sys.started")
    public void init() {
        testRepo = (TestRepo) ep.fire("dao.bean.get", TestRepo.class);
        transManager = (TransWrapper) ep.fire("dao.bean.get", TransWrapper.class);
        ep.fire("swagger.addJaxrsDoc", this, null, "tpl", "tpl rest doc");
    }


    @GET
    @Path("insert")
    public void insert() {
        TestEntity e = new TestEntity();
        e.setName("aaaa" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        e.setAge(111);
        transManager.trans(() -> testRepo.saveOrUpdate(e));
    }


    @GET
    @Path("find")
    @Produces("application/json")
    public Object find() {
        return testRepo.findPage(0, 100, null);
    }


//    public Object session(@CookieParam("sessionid") Cookie cookie) {
//        cookie.getValue();
//    }


    @GET
    @Path("get1")
    //@Produces("application/json")
    public String get(@SessionId String sId, @QueryParam("a") String a, @SessionAttr("attr1") Object attr1) {
        log.info("get1 {}" + a);
        // if (true) throw new IllegalArgumentException("xxxxxxxxxxxxx");
        ep.fire("session.set", sId, "attr1", "value1" + System.currentTimeMillis());
        log.info("session attr1: {}", attr1);
        return sId;
    }


    @GET
    @Path("cache")
    // @Produces("application/json")
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
