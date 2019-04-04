package org.xnatural.enet.test.rest;

import com.alibaba.fastjson.JSON;
import com.mongodb.MongoClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;
import org.xnatural.enet.common.Log;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;
import org.xnatural.enet.server.dao.hibernate.TransWrapper;
import org.xnatural.enet.server.resteasy.SessionAttr;
import org.xnatural.enet.server.resteasy.SessionId;
import org.xnatural.enet.test.dao.repo.TestRepo;
import org.xnatural.enet.test.rest.request.AddFileDto;
import org.xnatural.enet.test.service.FileUploader;
import org.xnatural.enet.test.service.TestService;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.File;
import java.io.InputStream;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.xnatural.enet.common.Utils.isEmpty;
import static org.xnatural.enet.test.rest.ApiResp.ok;

/**
 * @author xiangxb, 2019-01-13
 */
@Path("tpl")
//@Hidden // 不加入到 swagger doc
public class RestTpl extends ServerTpl {

    private EP ep;
    final Log log = Log.of(getClass());

    private TestRepo     testRepo;
    private TransWrapper tm;
    private TestService  service;
    private FileUploader uploader;


    @EL(name = "sys.started")
    public void init() {
        // ep.fire("swagger.addJaxrsDoc", this, null, "tpl", "tpl rest doc");
        testRepo = (TestRepo) ep.fire("dao.bean.get", TestRepo.class);
        tm = (TransWrapper) ep.fire("bean.get", TransWrapper.class);
        service = bean(TestService.class);
        uploader = bean(FileUploader.class);
    }


    @GET @Path("dao")
    @Produces("application/json")
    public Object dao() throws Exception {
        // return testRepo.tbName();
//        tm.trans(() -> testRepo.delete(testRepo.findById(66L)));
//        return "xxx";
        return service.findTestData();
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
        // if (true) throw new IllegalArgumentException("xxxxxxxxxxxxx");
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
//        try {
//            IOUtils.readLines(new FileInputStream("E:\\tmp\\idNo.txt")).forEach(l -> {
//                ep.fire("redis.del", "overmind:PBOC:P01:"+l);
//            });
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
        return ok(r);
    }


    @GET
    @Path("mongo")
    @Produces("application/json")
    public Object mongo() {
        MongoClient c = (MongoClient) ep.fire("bean.get", MongoClient.class);
        // return c == null ? "" : c.getDatabase("cenarius").getCollection("config_list").find().first().toJson();
        return c == null ? "" : c.getDatabase("gsis").getCollection("cidtb").find().first().toJson();
    }


    @GET @Path("async")
    @Produces("text/plain")
    public CompletionStage<String> async() {
        CompletableFuture<String> f = new CompletableFuture();
        f.complete("ssssssssssss");
        return f;
    }


    @POST
    @Path("upload")
    @Consumes("multipart/form-data")
    @Produces("application/json")
    public ApiResp upload(MultipartFormDataInput formData) {
    // public ApiResp upload(@MultipartForm AddFileDto addFile) {
        AddFileDto addFile = extractFormDto(formData, AddFileDto.class);
        uploader.save(addFile.getHeadportrait());
        service.save(addFile);
        return ok(uploader.toFullUrl(addFile.getHeadportrait().getResultName()));
    }


    @POST @Path("form")
    @Consumes("application/x-www-form-urlencoded")
    @Produces("application/json")
    public ApiResp form(@FormParam("attr") String attr, @FormParam("ss") Integer ss) {
        return ok().attr("attr", attr).attr("ss", ss);
    }


    @GET @Path("js/lib/{fName}")
    public Response jsLib(@PathParam("fName") String fName) {
        File f = new File(getClass().getClassLoader().getResource("static/js/lib/" + fName).getFile());
        return Response.ok(f)
            .header("Content-Disposition", "attachment; filename=" + f.getName())
            .header("Cache-Control", "max-age=60")
            .build();
    }


    @GET @Path("file/{fName}")
    public Response file(@PathParam("fName") String fName) {
        File f = bean(FileUploader.class).findFile(fName);
        return Response.ok(f)
                .header("Content-Disposition", "attachment; filename=" + f.getName())
                .header("Cache-Control", "max-age=60")
                .build();
    }


    public <T> T extractFormDto(MultipartFormDataInput formDataInput, Class<T> clz) {
        T resultDto = null;
        try {
            resultDto = clz.newInstance();
            for (PropertyDescriptor pd : Introspector.getBeanInfo(clz).getPropertyDescriptors()) {
                List<InputPart> value = formDataInput.getFormDataMap().get(pd.getName());
                if (isEmpty(value)) continue;
                Class<?> pdType = pd.getPropertyType();
                if (FileData.class.isAssignableFrom(pdType)) {
                    InputPart inputPart = value.get(0);
                    // if (!"image".equals(inputPart.getMediaType().getType())) continue;

                    FileData fileDto = new FileData();
                    InputStream inputStream = inputPart.getBody(InputStream.class, null);
                    if (inputStream.available() < 1) continue;
                    String fileNameHeader = inputPart.getHeaders().get("Content-Disposition").get(0).replace("\"", "");
                    String fileName = (fileNameHeader.contains("filename") ? fileNameHeader.split("filename=")[1] : (fileNameHeader.contains("name") ? fileNameHeader.split("name=")[1] : ""));
                    String[] fileNameArr = fileName.split("\\.");

                    // fileDto.setFileData(IOUtils.toByteArray(inputStream));
                    fileDto.setInputStream(inputStream);
                    fileDto.setOriginName(fileName);
                    fileDto.setExtension(fileNameArr.length > 1 ? fileNameArr[1] : "");

                    pd.getWriteMethod().invoke(resultDto, fileDto);
                } else if (String.class.isAssignableFrom(pdType)) {
                    pd.getWriteMethod().invoke(resultDto, value.get(0).getBodyAsString());
                } else if (Boolean.class.isAssignableFrom(pdType) || boolean.class.isAssignableFrom(pdType)) {
                    String strValue = value.get(0).getBodyAsString();
                    if (StringUtils.isNotEmpty(strValue)) {
                        pd.getWriteMethod().invoke(resultDto, BooleanUtils.toBoolean(strValue));
                    }
                } else if (Long.class.isAssignableFrom(pdType) || long.class.isAssignableFrom(pdType)) {
                    String strValue = value.get(0).getBodyAsString();
                    if (StringUtils.isNotEmpty(strValue)) {
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
                    if (StringUtils.isNotEmpty(strValue)) {
                        pd.getWriteMethod().invoke(resultDto, Integer.valueOf(strValue));
                    }
                } else if (Date.class.isAssignableFrom(pdType)) {
                    String strValue = value.get(0).getBodyAsString();
                    if (StringUtils.isNotEmpty(strValue)) {
                        Date date = org.apache.commons.lang3.time.DateUtils.parseDate(strValue, new String[]{"yyyy-MM-dd", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"});
                        pd.getWriteMethod().invoke(resultDto, date);
                    }
                } else if (pdType.isArray()) {
                    String strValue = value.get(0).getBodyAsString();
                    if (StringUtils.isEmpty(strValue)) continue;
                    pd.getWriteMethod().invoke(resultDto, JSON.parseObject("[" + strValue + "]", pdType));
                } else if (Collection.class.isAssignableFrom(pdType)) {
                    //
                } else {
                    String strValue = value.get(0).getBodyAsString();
                    if (StringUtils.isEmpty(strValue)) continue;
                    pd.getWriteMethod().invoke(resultDto, JSON.parseObject(strValue, pdType));
                }
            }
        } catch (Exception e) {
            log.error(e);
        }
        return resultDto;
    }
}
