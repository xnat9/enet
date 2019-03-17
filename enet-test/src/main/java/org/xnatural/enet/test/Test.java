package org.xnatural.enet.test;

import javassist.bytecode.analysis.Executor;
import org.xnatural.enet.common.Log;
import org.xnatural.enet.common.Utils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Test {

    static {
        Log.init(null);
    }
    public static void main(String[] args) {
        System.out.println(650/30*12 - 550/30*12);
        if (true) return;
        Utils.Http h = Utils.http();
        long start = System.currentTimeMillis();
        System.out.println(h.get("http://localhost:8080/tpl/dao").execute());
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
                    System.out.println(h.get("http://localhost:8080/tpl/dao").execute());
                }
                count.decrementAndGet(); fn.run();
            });
        }
    }
}
