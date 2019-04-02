package org.xnatural.enet.test;

import org.xnatural.enet.common.Log;
import org.xnatural.enet.event.EP;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class ResteasyMonitor implements ContainerRequestFilter, ContainerResponseFilter {
    protected EP ep;
    protected Log log = Log.of(getClass());

    @Override
    public void filter(ContainerRequestContext reqCtx) throws IOException {
        reqCtx.setProperty("startTime", System.currentTimeMillis());
    }


    @Override
    public void filter(ContainerRequestContext reqCtx, ContainerResponseContext respCtx) throws IOException {
        Object startTime = reqCtx.getProperty("startTime");
        if (startTime != null) {
            long spend = System.currentTimeMillis() - (long) startTime;
            if (spend > 3000) {
                log.warn("接口 '{}' 超时. spend: {}", reqCtx.getUriInfo().getPath(), spend);
            }
        }
    }
}
