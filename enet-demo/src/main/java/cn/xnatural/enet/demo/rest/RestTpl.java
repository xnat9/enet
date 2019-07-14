package cn.xnatural.enet.demo.rest;

import cn.xnatural.enet.common.Log;
import cn.xnatural.enet.core.GroovyEngine;
import cn.xnatural.enet.demo.rest.request.AddFileDto;
import cn.xnatural.enet.demo.service.FileUploader;
import cn.xnatural.enet.demo.service.TestService;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.server.ServerTpl;
import cn.xnatural.enet.server.resteasy.SessionAttr;
import cn.xnatural.enet.server.resteasy.SessionId;
import com.alibaba.fastjson.JSON;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.apache.commons.lang3.BooleanUtils;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import javax.annotation.Resource;
import javax.ws.rs.*;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Response;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static cn.xnatural.enet.common.Utils.isEmpty;
import static cn.xnatural.enet.common.Utils.isNotEmpty;
import static cn.xnatural.enet.demo.rest.ApiResp.ok;

/**
 * @author xiangxb, 2019-01-13
 */
@Path("tpl")
//@Hidden // 不加入到 swagger doc
public class RestTpl extends ServerTpl {

    final Log log = Log.of(getClass());

    @Resource
    TestService  service;
    @Resource
    FileUploader uploader;


    @EL(name = "sys.started")
    public void init() {
        attrs.putAll((Map<? extends String, ?>) ep.fire("env.ns", "mvc"));
    }


    @Resource
    GroovyEngine ge;

    @GET
    @Path("groovy")
    @Produces("application/json")
    public ApiResp groovy() {
        // 执行某个文件脚本
        ep.fire("groovy.script", "test.groovy");
        // ge.script("test.groovy", script -> script.run());
        // ge.script("text.groovy", script -> script.invokeMethod("aa", "xx")); // 执行文件脚本中的某个方法

        // 执行一段脚本代码
        // ep.fire("groovy.eval", "log.info('groovy eval')");
        // ge.eval("ep.fire('eName2', 'xx')", script -> script.run());
        // ge.eval("def aa() {println 'aa'}", script -> script.invokeMethod("aa", null)); // 执行脚本代码中的aa方法
        return ok();
    }


    @GET
    @Path("remote")
    @Produces("application/json")
    public void remote(@QueryParam("app") String app, @QueryParam("eName") String eName, @QueryParam("ret") String ret, @Suspended final AsyncResponse resp) {
        resp.setTimeout(30, TimeUnit.SECONDS);
        service.remote(app, eName, ret, o -> {
            if (o instanceof Exception) resp.resume(ApiResp.fail(((Exception) o).getMessage()));
            else resp.resume(ApiResp.ok(o));
        });
    }


    @GET @Path("dao")
    @Produces("application/json")
    public ApiResp<PageModel> dao() throws Exception {
        // return testRepo.tbName();
//        tm.trans(() -> testRepo.delete(testRepo.findById(66L)));
//        return "xxx";
        return ok(service.findTestData());
        // return testRepo.findPage(0, 10, (root, query, cb) -> {query.orderBy(cb.desc(root.get("id"))); return null;});
    }


    @GET @Path("get")
    @Produces("application/json")
    public ApiResp get(@QueryParam("a") String a) {
        return ok("a", a);
    }


    @Operation(summary = "session 测试")
    @GET @Path("session")
    @Produces("application/json")
    public Object session(
        @Parameter(hidden = true) @SessionId String sId,
        @Parameter(hidden = true) @SessionAttr("attr1") Object attr1,
        @Parameter(description = "参数a") @QueryParam("a") String a
    ) {
        ep.fire("session.set", sId, "attr1", "value1" + System.currentTimeMillis());
        return ok("sId", sId).attr("attr1", attr1).attr("param_a", a);
    }


    @GET @Path("cache")
    @Produces("application/json")
    public ApiResp cache() {
//        Object r = ep.fire("ehcache.get", "test", "key1");
//        if (r == null) ep.fire("ehcache.set", "test", "key1", "qqqqqqqqqq");

//        ep.fire("redis.hset", "test", "key1", "xxxxxxxxxxxxx");
//        return ok(ep.fire("redis.hget", "test", "key1"));

        Object r = ep.fire("cache.get", "test", "key1");
        if (r == null) ep.fire("cache.set", "test", "key1", "mem");
        return ok(r);
    }


    @GET
    @Path("mongo")
    @Produces("application/json")
    public ApiResp mongo() {
//        MongoClient c = bean(MongoClient.class);
//        // return c == null ? "" : c.getDatabase("cenarius").getCollection("config_list").find().first().toJson();
//        return c == null ? "" : c.getDatabase("gsis").getCollection("cidtb").find().first().toJson();
        return ok();
    }


    @GET @Path("async")
    @Produces("text/plain")
    public CompletionStage<String> async() {
        CompletableFuture<String> f = new CompletableFuture();
        f.complete("ssssssssssss");
        return f;
    }


    @GET @Path("async2")
    @Produces("text/plain")
    public void async2(@Suspended final AsyncResponse resp) {
        resp.setTimeout(1, TimeUnit.MINUTES);
        resp.resume("@Suspended async result");
    }


    @POST
    @Path("upload")
    @Consumes("multipart/form-data")
    @Produces("application/json")
//    @Operation(
//        summary = "文件上传测试",
//        // parameters = {@Parameter(name = "name", )},
//        requestBody = @RequestBody(content = {
//            @Content(mediaType = "application/octet-stream",
//                schema = @Schema(name = "headportrait", type="string", format="binary")
//            ),
//            @Content(
//                mediaType = "text/plan",
//                schema = @Schema(name = "name", type="string")
//            ),
//            @Content(mediaType = "text/plan",
//                schema = @Schema(name = "age", type="integer")
//            ),
//        })
//        )
    public ApiResp upload(MultipartFormDataInput formData) throws Exception {
    // public ApiResp upload(@MultipartForm AddFileDto addFile) {
    // public ApiResp upload(@FormParam("name") String name, @FormParam("age") Integer ss) {
        AddFileDto addFile = extractFormDto(formData, AddFileDto.class);
        if (addFile.getAge() == null) throw new IllegalArgumentException("年龄必填");
        uploader.save(addFile.getHeadportrait());
        try {
            service.save(addFile);
        } catch (Exception ex) {
            uploader.delete(addFile.getHeadportrait().getResultName());
            throw ex;
        }
        log.info("upload file: {}", addFile.getHeadportrait());
        return ok(uploader.toFullUrl(addFile.getHeadportrait().getResultName()));
        // return ok();
    }


    @POST @Path("form")
    @Consumes("application/x-www-form-urlencoded")
    @Produces("application/json")
    public ApiResp form(@FormParam("attr") String attr, @FormParam("ss") Integer ss) {
        log.info("form request test.");
        return ok().attr("attr", attr).attr("ss", ss);
    }


    @GET @Path("js/{fName}")
    public Response js(@PathParam("fName") String fName) throws Exception {
        URL url = getClass().getClassLoader().getResource("static/js/" + fName);
        if (url == null) return Response.status(404).build();
        return Response.ok(url.openStream())
            .type("application/javascript; charset=utf-8")
            .header("Cache-Control", "max-age=" + getInteger("jsCacheMaxAge", getInteger("maxAge", 60)))
            .build();
    }


    @GET @Path("css/{fName}")
    public Response css(@PathParam("fName") String fName) throws Exception {
        URL url = getClass().getClassLoader().getResource("static/css/" + fName);
        if (url == null) return Response.status(404).build();
        return Response.ok(url.openStream())
            .type("text/css; charset=utf-8")
            .header("Cache-Control", "max-age=" + getInteger("cssCacheMaxAge", getInteger("maxAge", 60)))
            .build();
    }


    @GET @Path("{fName}")
    public Response html(@PathParam("fName") String fName) throws Exception {
        URL url = getClass().getClassLoader().getResource("static/" + fName);
        if (url == null) return Response.status(404).build();
        return Response.ok(url.openStream())
            .type("text/html; charset=utf-8")
            .header("Cache-Control", "max-age=" + getInteger("htmlCacheMaxAge", getInteger("maxAge", 60)))
            .build();
    }


    @GET @Path("")
    public Response index() throws Exception {
        URL url = getClass().getClassLoader().getResource("static/index.html");
        if (url == null) return Response.status(404).build();
        return Response.ok(url.openStream()).type("text/html; charset=utf-8").build();
    }


    @GET @Path("file/{fName}")
    public Response file(@PathParam("fName") String fName) {
        File f = uploader.findFile(fName);
        if (f == null) return Response.status(404).build();
        return Response.ok(f)
                .header("Content-Disposition", "attachment; filename=" + f.getName())
                .header("Cache-Control", "max-age=" + getInteger("uploadFileCacheMaxAge", getInteger("maxAge", 180)))
                .build();
    }


    /**
     * 把表单中的数据填充到一个 java bean 中去
     * @param formDataInput
     * @param clz
     * @param <T>
     * @return
     * @throws Exception
     */
    private  <T> T extractFormDto(MultipartFormDataInput formDataInput, Class<T> clz) throws Exception {
        T resultDto = clz.newInstance();
        for (PropertyDescriptor pd : Introspector.getBeanInfo(clz).getPropertyDescriptors()) {
            List<InputPart> value = formDataInput.getFormDataMap().get(pd.getName());
            if (isEmpty(value)) continue;
            Class<?> pdType = pd.getPropertyType();
            if (FileData.class.isAssignableFrom(pdType)) {
                InputPart inputPart = value.get(0);
                FileData fileData = new FileData();
                InputStream inputStream = inputPart.getBody(InputStream.class, null);
                if (inputStream.available() < 1) continue;
                String fileNameHeader = inputPart.getHeaders().get("Content-Disposition").get(0).replace("\"", "");
                String fileName = (fileNameHeader.contains("filename") ? fileNameHeader.split("filename=")[1] : (fileNameHeader.contains("name") ? fileNameHeader.split("name=")[1] : ""));
                String[] fileNameArr = fileName.split("\\.");

                fileData.setInputStream(inputStream);
                fileData.setOriginName(fileName);
                fileData.setExtension(fileNameArr.length > 1 ? fileNameArr[1] : "");
                fileData.setGeneratedName(UUID.randomUUID().toString().replace("-", ""));

                pd.getWriteMethod().invoke(resultDto, fileData);
            } else if (String.class.isAssignableFrom(pdType)) {
                pd.getWriteMethod().invoke(resultDto, value.get(0).getBodyAsString());
            } else if (Boolean.class.isAssignableFrom(pdType) || boolean.class.isAssignableFrom(pdType)) {
                String strValue = value.get(0).getBodyAsString();
                if (isNotEmpty(strValue)) {
                    pd.getWriteMethod().invoke(resultDto, BooleanUtils.toBoolean(strValue));
                }
            } else if (Long.class.isAssignableFrom(pdType) || long.class.isAssignableFrom(pdType)) {
                String strValue = value.get(0).getBodyAsString();
                if (isNotEmpty(strValue)) {
                    pd.getWriteMethod().invoke(resultDto, Long.valueOf(strValue));
                }
            } else if (pdType.isEnum()) {
                String strValue = value.get(0).getBodyAsString();
                for (Object o : pdType.getEnumConstants()) {
                    if (Objects.equals(o.toString(), strValue)) {
                        pd.getWriteMethod().invoke(resultDto, o);
                    }
                }
            } else if (Integer.class.isAssignableFrom(pdType) || int.class.isAssignableFrom(pdType)) {
                String strValue = value.get(0).getBodyAsString();
                if (isNotEmpty(strValue)) {
                    pd.getWriteMethod().invoke(resultDto, Integer.valueOf(strValue));
                }
            } else if (Date.class.isAssignableFrom(pdType)) {
                String strValue = value.get(0).getBodyAsString();
                if (isNotEmpty(strValue)) {
                    Date date = org.apache.commons.lang3.time.DateUtils.parseDate(strValue, new String[]{"yyyy-MM-dd", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"});
                    pd.getWriteMethod().invoke(resultDto, date);
                }
            } else if (pdType.isArray()) {
                String strValue = value.get(0).getBodyAsString();
                if (isEmpty(strValue)) continue;
                pd.getWriteMethod().invoke(resultDto, JSON.parseObject("[" + strValue + "]", pdType));
            } else if (Collection.class.isAssignableFrom(pdType)) {
                //
            } else {
                String strValue = value.get(0).getBodyAsString();
                if (isEmpty(strValue)) continue;
                pd.getWriteMethod().invoke(resultDto, JSON.parseObject(strValue, pdType));
            }
        }
        return resultDto;
    }
}
