package org.xnatural.enet.common;

import com.alibaba.fastjson.JSON;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarEntry;

/**
 * 常用工具方法集
 */
public class Utils {

    static Log log = Log.of(Utils.class);


    /**
     * 构建一个 http 请求, 支持 get, post. 文件上传.
     * @return
     */
    public static Http http() { return new Http(); }


    public static class Http {
        private String              urlStr;
        private String              contentType = "application/x-www-form-urlencoded";
        private String              method;
        private String              jsonBody;
        private Map<String, Object> params;
        private Map<String, Object> cookies;
        private Map<String, String> headers;
        private int                 timeout = 3000;
        private int                 responseCode;

        public Http get(String url) { this.urlStr = url; this.method = "GET"; return this; }
        public Http post(String url) { this.urlStr = url; this.method = "POST"; return this; }
        /**
         *  设置 content-type
         * @param contentType application/json, multipart/form-data, application/x-www-form-urlencoded
         * @return
         */
        public Http contentType(String contentType) { this.contentType = contentType; return this; }
        public Http jsonBody(String jsonStr) {this.jsonBody = jsonStr; return this; }
        public Http timeout(int timeout) { this.timeout = timeout; return this; }
        /**
         * 添加参数
         * @param name 参数名
         * @param value 支持 {@link File}
         * @return
         */
        public Http param(String name, Object value) {
            if (params == null) params = new LinkedHashMap<>();
            params.put(name, value);
            return this;
        }
        public Http header(String name, String value) {
            if (headers == null) headers = new HashMap<>(7);
            headers.put(name, value);
            return this;
        }
        public Http cookie(String name, Object value) {
            if (cookies == null) cookies = new HashMap<>(7);
            cookies.put(name, value);
            return this;
        }
        public Http cookies(Map<String, Object> cookies) {
            if (cookies == null || cookies.isEmpty()) return this;
            if (this.cookies == null) this.cookies = new HashMap<>(7);
            this.cookies.putAll(cookies);
            return this;
        }
        public Map<String, Object> cookies() {return cookies;}
        public int getResponseCode() {return responseCode;}

        /**
         * 执行 http 请求
         * @return http请求结果
         */
        public String execute() {
            String ret = null;
            HttpURLConnection conn = null;
            boolean isMulti = false; // 是否为 multipart/form-data 提交
            try {
                URL url = null;
                if (StringUtils.isEmpty(urlStr)) throw new IllegalArgumentException("url不能为空");
                if ("GET".equals(method)) url = new URL(buildUrl(urlStr, params));
                else if ("POST".equals(method)) url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                if (conn instanceof HttpsURLConnection) { // 如果是https, 就忽略验证
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, new TrustManager[] {new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException { }
                        @Override
                        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException { }
                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }
                    }}, new java.security.SecureRandom());
                    ((HttpsURLConnection) conn).setHostnameVerifier((s, sslSession) -> true);
                    ((HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
                }
                conn.setRequestMethod(method);
                conn.setRequestProperty("Charset", "UTF-8");
                conn.setRequestProperty("Accept-Charset", "UTF-8");
                // conn.setRequestProperty("User-Agent", "xnatural-http-client");
                conn.setConnectTimeout(timeout);
                conn.setReadTimeout(timeout);
                // header 设置
                if (isNotEmpty(headers)) {
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        conn.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }

                String boundary = null;
                if ("POST".equals(method)) {
                    conn.setUseCaches(false);
                    conn.setDoOutput(true);
                    if ("multipart/form-data".equals(contentType) || (params != null && params.values().stream().anyMatch(o -> o instanceof File))) {
                        boundary = "----CustomFormBoundary" + UUID.randomUUID();
                        contentType = "multipart/form-data;boundary=" + boundary;
                        isMulti = true;
                    }
                }
                conn.setRequestProperty("Content-Type", contentType);

                // cookie 设置
                if (isNotEmpty(cookies)) {
                    StringBuilder sb = new StringBuilder();
                    cookies.forEach((s, o) -> {
                        if (o != null) sb.append(s).append("=").append(o).append(";");
                    });
                    conn.setRequestProperty("Cookie", sb.toString());
                }

                conn.connect();  // 连接

                if ("POST".equals(method)) {
                    DataOutputStream os = new DataOutputStream(conn.getOutputStream());
                    if ("application/json".equals(contentType) && (isNotEmpty(params) || jsonBody != null)) {
                        if (jsonBody == null) os.write(JSON.toJSONString(params).getBytes());
                        else os.write(jsonBody.getBytes());
                        os.flush(); os.close();
                    } else if (isMulti && isNotEmpty(params)) {
                        String end = "\r\n";
                        String twoHyphens = "--";
                        for (Map.Entry<String, Object> entry : params.entrySet()) {
                            os.writeBytes(twoHyphens + boundary + end);
                            if (entry.getValue() instanceof File) {
                                String s = "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"; filename=\"" + ((File) entry.getValue()).getName() + "\"" + end;
                                os.write(s.getBytes("utf-8")); // 这样写是为了避免中文文件名乱码
                                os.writeBytes(end);
                                IOUtils.copy(new FileInputStream((File) entry.getValue()), os);
                            }
//                            else if (entry.getValue() instanceof MultipartFile) {
//                                String s = "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"; filename=\"" + ((MultipartFile) entry.getValue()).getOriginalFilename() + "\"" + end;
//                                os.write(s.getBytes("utf-8")); // 这样写是为了避免中文文件名乱码
//                                os.writeBytes(end);
//                                IOUtils.copy(((MultipartFile) entry.getValue()).getInputStream(), os);
//                            }
                            else {
                                os.write(("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"" + end).getBytes("utf-8"));
                                os.writeBytes(end);
                                os.write(entry.getValue().toString().getBytes("utf-8"));
                            }
                            os.writeBytes(end);
                        }
                        os.writeBytes(twoHyphens + boundary + twoHyphens + end);
                        os.flush(); os.close();
                    } else if (isNotEmpty(params)) {
                        StringBuilder sb = new StringBuilder();
                        for (Map.Entry<String, Object> entry : params.entrySet()) {
                            if (entry.getValue() != null) sb.append(entry.getKey() + "=" + URLEncoder.encode(entry.getValue().toString(), "utf-8") + "&");
                        };
                        os.write(sb.toString().getBytes("utf-8"));
                        os.flush(); os.close();
                    }
                }
                // http 状态码
                responseCode = conn.getResponseCode();
                // 保存cookie
                List<String> cs = conn.getHeaderFields().get("Set-Cookie");
                if (isNotEmpty(cs)) {
                    for (String c : cs) {
                        String[] arr = c.split(";")[0].split("=");
                        cookie(arr[0], arr[1]);
                    }
                }
                // 取结果
                if (responseCode == 200) ret = IOUtils.toString(conn.getInputStream(), "UTF-8");
            } catch (Exception e) {
                log.error(e, "http 错误, url: " + urlStr);
            } finally {
                if (conn != null) conn.disconnect();
            }
            return ret;
        }
    }


    /**
     * 得到jvm进程号
     * @return
     */
    public static String getPid() {
        return ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
    }


    /**
     * 主机名
     * @return
     */
    public static String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "";
        }
    }


    /**
     * 遍历所有方法并处理
     * @param clz
     * @param fns
     */
    public static void iterateMethod(final Class clz, Consumer<Method>... fns) {
        if (fns == null || fns.length < 1) return;
        Class c = clz;
        do {
            for (Method m : c.getDeclaredMethods()) for (Consumer<Method> fn : fns) fn.accept(m);
            c = c.getSuperclass();
        } while (c != null);
    }


    /**
     * 遍历所有字段并处理
     * @param clz
     * @param fns
     */
    public static void iterateField(final Class clz, Consumer<Field>... fns) {
        if (fns == null || fns.length < 1) return;
        Class c = clz;
        do {
            for (Field f : c.getDeclaredFields()) for (Consumer<Field> fn : fns) fn.accept(f);
            c = c.getSuperclass();
        } while (c != null);
    }


    /**
     * 遍历一个包下边的所有class
     * @param pkgName 包名
     * @param cl ClassLoader
     * @param fns
     */
    public static void iterateClass(final String pkgName, ClassLoader cl, Consumer<Class>... fns) {
        if (isEmpty(pkgName) || fns == null || fns.length == 0) return;
        cl = (cl == null ? Utils.class.getClassLoader() : cl);
        String pkgDir = pkgName.replaceAll("\\.", "/");
        try {
            Enumeration<URL> resources = cl.getResources(pkgDir);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if ("file".equals(resource.getProtocol())) {
                    File[] arr = new File(cl.getResource(pkgDir).getFile()).listFiles(f -> f.getName().endsWith(".class"));
                    if (arr != null) for (File f : arr) {
                        if (f.isDirectory()) {
                            for (File ff : f.listFiles(ff -> ff.getName().endsWith(".class"))) {
                                iterateClass(pkgName + "." + ff.getName(), cl, fns);
                            }
                        } else if (f.isFile() && f.getName().endsWith(".class")) {
                            Class<?> clz = cl.loadClass(pkgName + "." + f.getName().replace(".class", ""));
                            if (clz != null) for (Consumer<Class> fn : fns) { fn.accept(clz); }
                        }
                    }
                } else if ("jar".equals(resource.getProtocol())) {
                    Enumeration<JarEntry> es = ((JarURLConnection) resource.openConnection()).getJarFile().entries();
                    while (es.hasMoreElements()) {
                        JarEntry e = es.nextElement();
                        String name = e.getName();
                        // 如果是以/开头的
                        if (name.charAt(0) == '/') name = name.substring(1);

                        if (e.isDirectory() || !name.startsWith(pkgDir) || !name.endsWith(".class")) {
                            continue;
                        }
                        // 加载类
                        Class clz = cl.loadClass(name.substring(0, name.length() - 6).replace("/", "."));
                        if (clz != null) for (Consumer<Class> fn : fns) { fn.accept(clz); }
                    }
                }
            }
        } catch (Exception e) {
            log.error(e);
        }
    }


    /**
     * 查找方法
     * @param clz
     * @param mName
     * @param parameterTypes
     * @return
     */
    public static Method findMethod(final Class clz, String mName, Class<?>... parameterTypes) {
        Class c = clz;
        do {
            Method m = null;
            try {
                m = c.getDeclaredMethod(mName, parameterTypes);
            } catch (NoSuchMethodException e) { }
            if (m != null) return m;
            c = c.getSuperclass();
        } while (c != null);
        return null;
    }


    /**
     * 查找方法
     * @param clz
     * @param predicate
     * @return
     */
    public static Method findMethod(final Class clz, Predicate<Method> predicate) {
        Class c = clz;
        do {
            for (Method m : c.getDeclaredMethods()) if (predicate.test(m)) return m;
            c = c.getSuperclass();
        } while (c != null);
        return null;
    }


    /**
     * 方法调用
     * @param m
     * @param target
     * @param args
     * @return
     */
    public static Object invoke(Method m, Object target, Object...args) {
        try {
            m.setAccessible(true); return m.invoke(target, args);
        } catch (Exception e) {}
        return null;
    }


    /**
     * Enhancer 代理
     * @param clz
     * @param mi
     * @return
     */
    public static <T> T proxy(Class<T> clz, MethodInterceptor mi) {
        if (mi == null) return null;
        Enhancer en = new Enhancer();
        en.setSuperclass(clz);
        en.setCallback(mi);
        return (T) en.create();
    }


    /**
     * 把查询参数添加到 url 后边
     * @param urlStr
     * @param params
     * @return
     */
    public static String buildUrl(String urlStr, Map<String, Object> params) {
        if (isEmpty(params)) return urlStr;
        StringBuilder sb = new StringBuilder(urlStr);
        if (!urlStr.endsWith("?")) sb.append("?");
        params.forEach((s, o) -> {
            if (o != null) sb.append(s).append("=").append(o).append("&");
        });
        return sb.toString();
    }


    public static Boolean toBoolean(Object o, Boolean defaultValue) {
        if (o instanceof Boolean) return (Boolean) o;
        else if (o instanceof String) return Boolean.valueOf((String) o);
        return defaultValue;
    }


    public static Integer toInteger(final Object v, final Integer defaultValue) {
        if (v instanceof Integer) return (Integer) v;
        else if (v instanceof Long) return ((Long) v).intValue();
        else if (v instanceof Double) return ((Double) v).intValue();
        else if (v instanceof Float) return ((Float) v).intValue();
        else if (v instanceof BigDecimal) return ((BigDecimal) v).intValue();
        else if (v instanceof String) {
            try {
                return Integer.valueOf(v.toString());
            } catch (final NumberFormatException nfe) {
                return defaultValue;
            }
        }
        return defaultValue;
    }


    public static Long toLong(final Object v, final Long defaultValue) {
        if (v instanceof Long) return (Long) v;
        else if (v instanceof Integer) return ((Integer) v).longValue();
        else if (v instanceof Double) return ((Double) v).longValue();
        else if (v instanceof Float) return ((Float) v).longValue();
        else if (v instanceof BigDecimal) return ((BigDecimal) v).longValue();
        else if (v instanceof String) {
            try {
                return Long.valueOf(v.toString());
            } catch (final NumberFormatException nfe) {
                return defaultValue;
            }
        }
        return defaultValue;
    }


    public static Double toDouble(final Object v, final Double defaultValue) {
        if (v instanceof Double) return (Double) v;
        else if (v instanceof Integer) return ((Integer) v).doubleValue();
        else if (v instanceof Long) return ((Long) v).doubleValue();
        else if (v instanceof Float) return ((Float) v).doubleValue();
        else if (v instanceof BigDecimal) return ((BigDecimal) v).doubleValue();
        else if (v instanceof String) {
            try {
                return Double.valueOf(v.toString());
            } catch (final NumberFormatException nfe) {
                return defaultValue;
            }
        }
        return defaultValue;
    }


    public static Float toFloat(final Object v, final Float defaultValue) {
        if (v instanceof Float) return (Float) v;
        else if (v instanceof Integer) return ((Integer) v).floatValue();
        else if (v instanceof Long) return ((Long) v).floatValue();
        else if (v instanceof Float) return ((Float) v).floatValue();
        else if (v instanceof BigDecimal) return ((BigDecimal) v).floatValue();
        else if (v instanceof String) {
            try {
                return Float.valueOf(v.toString());
            } catch (final NumberFormatException nfe) {
                return defaultValue;
            }
        }
        return defaultValue;
    }


    public static boolean isEmpty(Collection<?> pCollection) {
        return (pCollection == null || pCollection.isEmpty());
    }


    public static boolean isNotEmpty(Collection<?> pCollection) {
        return (!isEmpty(pCollection));
    }


    public static boolean isEmpty(Object[] array) {
        return (array == null || Array.getLength(array) == 0);
    }


    public static boolean isNotEmpty(Object[] array) {
        return !isEmpty(array);
    }


    public static boolean isEmpty(final Map<?, ?> map) {
        return map == null || map.isEmpty();
    }


    public static boolean isNotEmpty(final Map<?, ?> map) {
        return map != null && !map.isEmpty();
    }


    public static boolean isNotEmpty(final String s) {
        return !isEmpty(s);
    }


    public static boolean isEmpty(final String s) {
        return s == null || s.isEmpty();
    }


    public static boolean isNotBlank(final CharSequence... cs) {
        if (cs == null || cs.length < 1) {
            return false;
        }
        for (final CharSequence c : cs){
            if (isBlank(c)) {
                return false;
            }
        }
        return true;
    }


    public static boolean isBlank(final CharSequence cs) {
        int strLen;
        if (cs == null || (strLen = cs.length()) == 0) {
            return true;
        }
        for (int i = 0; i < strLen; i++) {
            if (Character.isWhitespace(cs.charAt(i)) == false) {
                return false;
            }
        }
        return true;
    }
}
