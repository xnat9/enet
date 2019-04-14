package cn.xnatural.enet.test.rest;

import cn.xnatural.enet.common.Log;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.core.MediaType;
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
        if (ex instanceof ClientErrorException) {
            log.warn(ex.getMessage());
            return ((ClientErrorException) ex).getResponse();
        }

        ApiResp r = new ApiResp().setSuccess(false);
        String errorId = UUID.randomUUID().toString();
        log.error((ex.getCause() == null ? ex : ex.getCause()), "errorId: " + errorId);
        r.setErrorId(errorId);
        if (ex.getMessage() == null) r.setErrorMsg(ex.getClass().getSimpleName());
        else r.setErrorMsg(ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage());
        return Response.ok(r, MediaType.APPLICATION_JSON_TYPE).build();
    }
}
