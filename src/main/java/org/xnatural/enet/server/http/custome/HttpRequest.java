package org.xnatural.enet.server.http.custome;

import org.apache.commons.io.IOUtils;
import org.xnatural.enet.common.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author xiangxb, 2018-12-16
 */
public class HttpRequest {
    final   Log    log        = Log.of(getClass());
    /**
     * 请求创建时间
     */
    private Date   createTime = new Date();
    /**
     * GET,POST
     */
    private String method;
    /**
     * http协议版本
     * http/1.1, http/1.2
     */
    private String protocolVersion;
    /**
     * application/json, multipart/form-data, application/x-www-form-urlencoded
     */
    private String contentType;
    /**
     * 请求路径
     */
    private String path;
    /**
     * 参数
     */
    private Map<String, String> params;
    /**
     * http headers
     */
    private Map<String, String> headers    = new LinkedHashMap<>();
    /**
     * 请求处理过程中的属性
     */
    private Map<String, Object> attrs      = new ConcurrentHashMap<>();
    /**
     * application/json
     */
    private String              jsonParam;
    /**
     * 协议解析器
     */
    private HttpParser          parser;
    /**
     * nio SelectionKey
     */
    private SelectionKey        key;
    /**
     * bio socket
     */
    private Socket              socket;


    HttpRequest(SelectionKey key) throws IOException {
        this.key = key;
        parse();
    }
    HttpRequest(Socket s) throws IOException {
        this.socket = s;
        parse();
    }


    /**
     * 解析协议. 为请求的各种属性设值
     */
    private void parse() throws IOException {
        InputStream in = null;
        if (key != null) in = new ByteArrayInputStream(((ByteBuffer) key.attachment()).array());
        else if (socket != null) in = socket.getInputStream();
        parseHeader(in);
    }


    private void parseHeader(InputStream in) throws IOException {
        List<String> lines = IOUtils.readLines(in, "utf-8");
        parseRequestLine(lines.get(0));
        lines.stream().skip(1).forEach(s -> {
            if (s == null || s.isEmpty()) return;
            String[] arr = s.split(":");
            String v = "";
            for (int i = 1; i < arr.length; i++) {
                v += arr[i];
            }
            headers.put(arr[0], v.trim());
        });
        this.contentType = headers.get("content-type");
        log.debug("headers: {}", headers);
    }



    /**
     * 解析请求行: http 协议第一行数据
     * GET /hello HTTP/1.1
     * @param firstLine
     */
    private void parseRequestLine(String firstLine) {
        String[] arr = firstLine.split(" ");
        this.method = arr[0];
        this.path = arr[1];
        this.protocolVersion = arr[2];
    }


    public void submitResponse() {

    }


    public HttpRequest setAttr(String aName, Object aValue) {
        attrs.put(aName, aValue);
        return this;
    }


    public <T> T getAttr(String aName, Class<T> type, T defaultValue) {
        return type.cast(attrs.getOrDefault(aName, defaultValue));
    }


    public String getContentType() {
        return contentType;
    }


    public String getMethod() {
        return method;
    }


    public String getJsonParam() {
        return jsonParam;
    }


    public String getPath() {
        return path;
    }
}
