package org.xnatural.enet.test.rest;

import org.xnatural.enet.common.Log;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import java.util.UUID;

/**
 * 接口统一异常处理
 */
@Provider
public class ExHandler implements ExceptionMapper<Throwable> {
    Log log = Log.of(getClass());

    @Override
    public Response toResponse(Throwable ex) {
        ApiResp r = new ApiResp();
        String errorId = UUID.randomUUID().toString();
        log.error(ex, "errorId: " + errorId);
        r.setErrorId(errorId);
        if (ex.getMessage() == null) r.setErrorMsg(ex.getClass().getSimpleName());
        else r.setErrorMsg(ex.getMessage());
        return Response.ok(r).build();
    }
}
