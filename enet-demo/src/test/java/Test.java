import cn.xnatural.enet.common.Log;

import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.xnatural.enet.common.Utils.Http;
import static cn.xnatural.enet.common.Utils.http;

public class Test {

    static {
        Log.init(null);
    }

    public static void main(String[] args) throws Throwable {
//        System.out.println(http().get("http://localhost:8080/dao").header("Connection", "keep-alive").execute());
//        Thread.sleep(TimeUnit.SECONDS.toMillis(15));
        压测();
    }


    static void 压测() throws Throwable {
        String urlPrefix = "http://localhost:8080";

        Http http = http();
        System.out.println(http.get(urlPrefix + "/dao").execute());
        // if (true) return;
        Map<String, Object> cookies = http.cookies();

        int threadCnt = 20;
        ExecutorService exec = Executors.newFixedThreadPool(threadCnt);
        final AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger c = new AtomicInteger(0);
        for (int i = 0; i < threadCnt; i++) {
            String url = urlPrefix;
            if (i % 2 == 0) url += "/dao";
            else if (i % 3 == 0) url += "/session";
            else url += "/cache";
            String u = url;
            exec.execute(() -> {
                while (!stop.get()) {
                    try {
                        c.incrementAndGet();
                        Http h = http().cookies(cookies).get(u);
                        String r = h.execute();
                        if (h.getResponseCode() == 503) System.out.println("server is busy");
                        else System.out.println(r);
                    } catch (Exception ex) {
                        if (ex instanceof SocketTimeoutException) System.out.println(ex.getMessage());
                        else {
                            ex.printStackTrace();
                        }
                    }
                }
            });
        }
        Thread.sleep(TimeUnit.MINUTES.toMillis(5)); // 压测时间
        stop.set(true);
        exec.shutdown();
        System.out.println("共执行请求: " + c.get());
    }
}
