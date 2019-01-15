package org.xnatural.enet.modules.http;

import java.io.OutputStream;

/**
 * @author xiangxb, 2018-12-16
 */
public class HttpResponse {

    private HttpRequest request;


    public HttpResponse(HttpRequest request) {
        this.request = request;
    }


    public OutputStream getOutputStream() {
        return null;
    }


    public void submit() {

    }
}
