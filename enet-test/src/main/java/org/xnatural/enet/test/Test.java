package org.xnatural.enet.test;

import org.xnatural.enet.common.Log;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.xnatural.enet.common.Utils.Http;
import static org.xnatural.enet.common.Utils.http;
import static org.xnatural.enet.common.Utils.tryRun;

public class Test {

    static {
        Log.init(null);
    }

    public static void main(String[] args) {
        Http http = http();
        long start = System.currentTimeMillis();
        System.out.println(http.get("http://39.104.28.131:8080/tpl/dao").execute());
        Map<String, Object> cookies = http.cookies();

        int threadCunt = 100;
        ExecutorService exec = Executors.newFixedThreadPool(threadCunt);
        AtomicInteger count = new AtomicInteger(threadCunt);
        Runnable fn = () -> {
            if (count.get() == 0) {
                exec.shutdown();
                System.out.println("共执行: " + (System.currentTimeMillis() - start) / 1000);
            }
        };
        for (int i = 0; i < threadCunt; i++) {
            exec.execute(() -> {
                for (int j = 0; j < 100; j++) {
                    try {
                        Http h = http().timeout(10000).header("Connection", "close").cookies(cookies).get("http" + "://39.104.28.131:8080/tpl/dao");
                        h.execute();
                        // System.out.println(h.execute());
                        if (h.getResponseCode() == 503) {
                            System.out.println("server is busy");
                        }
                    } catch (Exception ex) {
                        if (ex instanceof SocketTimeoutException) System.out.println(ex.getMessage());
                        else {
                            ex.printStackTrace();
                        }
                    }

                }
                count.decrementAndGet(); fn.run();
            });
        }
    }
}
