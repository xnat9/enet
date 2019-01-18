package org.xnatural.enet.server.mvc.cutome;

import org.xnatural.enet.server.mvc.cutome.anno.*;

import java.io.File;
import java.util.List;
import java.util.Map;

@Route(path = "tpl", desc = "tpl rest")
public class RestTpl {

    @Route(path = "/get1/", method = "get")
    String get1() {
        return "xxxxxxxxxx";
    }


    @Route(path = "param", method = "get")
    String param(@Param(name = "param1") String p1, Integer p2) {
        return "param: " + p1;
    }


    @Route(path = "postFile1", consumes = "multipart/form-data")
    String postFile1(String p1, Map<String, Object> file) {
        return "";
    }


    @Route(path = "postFil2", consumes = "multipart/form-data")
    String postFil2(String p1, List<Map<String, Object>> files) {
        return "";
    }


    @Route(path = "postJson1", consumes = "application/json")
    String postJson1(String json) {
        return "";
    }


    @Route(path = "sessionAttrs", method = "post")
    String sessionAttrs(@SessionAttrs Map<String, Object> session) {
        return "";
    }


    @Route(path = "sessionAttr", method = "post")
    String sessionAttr(@SessionAttr String id) {
        return "";
    }


    @Route(path = "requestAttrs", method = "post")
    String requestAttrs(@RequestAttrs Map<String, Object> request) {
        return "";
    }


    @Route(path = "requestAttr", method = "post")
    String requestAttr(@RequestAttr String attr) {
        return "";
    }


    @Route(path = "cookie", method = "post")
    String cookie(@Cookie String aaa) {
        return "";
    }


    @Route(path = "requestHeader", method = "post")
    String requestHeader(@RequestHeader String host) {
        return "";
    }


    @Route(path = "requestHeaders", method = "post")
    String requestHeaders(Map<String, Object> header) {
        return "";
    }


    @Route(path = "responseHeaders", method = "post")
    String responseHeaders(@ResponseHeaders Map<String, Object> header) {
        return "";
    }


    @Route(path = "css/{fName}", method = "get", produces = "text/css")
    void getCssFile(@PathVariable String fName, @ResponseHeaders Map<String, Object> header) {
        header.put("Cache-Control", "max-age=60");
    }


    @Route(path = "js/lib/{fName}", method = "get", produces = "application/javascript")
    File getLibJsFile(@PathVariable(name = "fName") String fName, @ResponseHeaders Map<String, Object> header) {
        header.put("Cache-Control", "max-age=60");
        return new File(getClass().getClassLoader().getResource("static/js/lib/" + fName).getFile());
    }


    @Route(path = "js/{fName}", method = "get", produces = "application/javascript")
    void getJsFile(@PathVariable String fName, @ResponseHeaders Map<String, Object> header) {
    }
}
