package cn.xnatural.enet.test;

import cn.xnatural.enet.common.Log;

import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.xnatural.enet.common.Utils.Http;
import static cn.xnatural.enet.common.Utils.http;

public class Test {

    static {
        Log.init(null);
    }

    public static void main(String[] args) {
        Http http = http();
        long start = System.currentTimeMillis();
        String url = "http://localhost:8080/dao";
        http.get(url);
        System.out.println(http.execute());
        System.out.println(http.getResponseCode());
        if (true) return;
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
                try {
                    Http h = http().cookies(cookies).get(url);
                    String r = h.execute();
                    if (h.getResponseCode() == 503) System.out.println("server is busy");
                    else System.out.println(r);
                } catch (Exception ex) {
                    if (ex instanceof SocketTimeoutException) System.out.println(ex.getMessage());
                    else {
                        ex.printStackTrace();
                    }
                }
            });
        }
    }
}
