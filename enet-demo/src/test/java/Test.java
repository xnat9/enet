import cn.xnatural.enet.common.Log;
import okhttp3.*;
import okhttp3.internal.Util;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
        OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(Duration.ofSeconds(17)).connectTimeout(Duration.ofSeconds(5))
            .dispatcher(new Dispatcher(new ThreadPoolExecutor(4, 10, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), Util.threadFactory("OkHttp Dispatcher", false))))
            .cookieJar(new CookieJar() {// 共享cookie
                final HashMap<String, List<Cookie>> cookieStore = new HashMap<>();
                @Override
                public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                    cookieStore.put(url.host(), cookies);
                }
                @Override
                public List<Cookie> loadForRequest(HttpUrl url) {
                    List<Cookie> cookies = cookieStore.get(url.host());
                    return cookies != null ? cookies : new ArrayList<>(2);
                }
            })
            .build();
        String urlPrefix = "http://localhost:8000/tpl";

        System.out.println(client.newCall(new Request.Builder().get().url(urlPrefix + "/dao").build()).execute().body().string());
        // if (true) return;

        int threadCnt = 10;
        ExecutorService exec = Executors.newFixedThreadPool(threadCnt);
        final AtomicBoolean stop = new AtomicBoolean(false);
        for (int i = 0; i < threadCnt; i++) {
            String url = urlPrefix;
            if (i % 2 == 0) url += "/dao";
            else if (i % 5 == 0) url += "/session";
            else if (i % 3 == 0) url += "/remote?app=app2&eName=eName1&ret=" + i;
            else url += "/cache";
            String u = url;
            exec.execute(() -> {
                while (!stop.get()) {
                    Request req = new Request.Builder().get().url(u).build();
//                    client.newCall(req).enqueue(new Callback() {
//                        @Override
//                        public void onFailure(Call call, IOException e) { e.printStackTrace(); }
//                        @Override
//                        public void onResponse(Call call, Response resp) throws IOException {
//                            System.out.println(req.url().toString() +" : " + resp.body().string());
//                        }
//                    });
                    try {
                        System.out.println(req.url().toString() +" : " + client.newCall(req).execute().body().string());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
        Thread.sleep(TimeUnit.SECONDS.toMillis(20)); // 压测时间
        // client.dispatcher().cancelAll();
        // client.dispatcher().queuedCalls().forEach(call -> call.cancel());
        // client.dispatcher().executorService().shutdown();
        stop.set(true);
        exec.shutdown();
    }
}
