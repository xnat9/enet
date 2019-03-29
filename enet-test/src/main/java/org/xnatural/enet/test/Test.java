package org.xnatural.enet.test;

import org.xnatural.enet.common.Log;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.xnatural.enet.common.Utils.Http;
import static org.xnatural.enet.common.Utils.http;

public class Test {

    static {
        Log.init(null);
    }
    public static void main(String[] args) {
        Http http = http();
        long start = System.currentTimeMillis();
        System.out.println(http.get("http://localhost:8080/tpl/dao").execute());
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
                    System.out.println(http().timeout(5000).header("Connection", "close").cookies(cookies).get("http://localhost:8080/tpl/dao").execute());
                }
                count.decrementAndGet(); fn.run();
            });
        }
    }
}
