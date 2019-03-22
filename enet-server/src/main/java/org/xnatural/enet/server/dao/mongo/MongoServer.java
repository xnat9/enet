package org.xnatural.enet.server.dao.mongo;

import com.mongodb.*;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.util.Map;

public class MongoServer extends ServerTpl {
    protected MongoClient client;


    public MongoServer() {
        setName("mongo");
    }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName());
            return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getName() + ".starting");
        attrs.putAll((Map) coreEp.fire("env.ns", getName()));

        String uri = getStr("uri", "");
        if (!uri.isEmpty()) {
            client = new MongoClient(new MongoClientURI(uri, optBuilder()));
        } else {
            MongoCredential credential = null;
            if (attrs.containsKey("username")) {
                credential = MongoCredential.createCredential(getStr("username", ""), getStr("database", ""), getStr("password", "").toCharArray());
            }
            if (credential == null) {
                client = new MongoClient(
                    new ServerAddress(getStr("host", "localhost"), getInteger("port", 27017)), optBuilder().build()
                );
            } else {
                client = new MongoClient(
                    new ServerAddress(getStr("host", "localhost"), getInteger("port", 27017)), credential, optBuilder().build()
                );
            }
        }
        exposeBean(client);

        coreEp.fire(getName() + ".started");
        log.info("Started {} Server", getName());
    }



    protected MongoClientOptions.Builder optBuilder() {
        return MongoClientOptions.builder()
            .connectTimeout(getInteger("connectTimeout", 3000))
            .maxWaitTime(getInteger("maxWaitTime", 5000))
            .heartbeatFrequency(getInteger("heartbeatFrequency", 5000));
    }
}
