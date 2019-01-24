package org.xnatural.enet.common;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 常用工具方法集
 */
public class Utils {

    static final Log log = Log.of(Utils.class);


    /**
     * 得到当前运行的进程id
     * @return
     */
    public static String getPid() {
        return ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
    }


    public static String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "";
        }
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


    /**
     * 将List转为Map类型
     *
     * @param collection 要转换的collection
     * @param keyFn    从collection中如何取得用在map的key的字段
     * @param valueFn  从collection中如何取得用在map的value的字段
     * @param <T>        collection中元素的类型
     * @param <K>        map的key的类型
     * @param <V>        map的value的类型
     * @return 转换后的map
     */
    public static <T, K, V> Map<K, V> colToMap(Collection<T> collection, Function<T, K> keyFn, Function<T, V> valueFn) {
        Map<K, V> map = new LinkedHashMap<>(); // 保证有序
        if (collection == null) return map;
        collection.forEach(t -> map.put(keyFn.apply(t), valueFn.apply(t)));
        return map;
    }


    public static <T> String join(Collection<T> col, Function<T, String> toStringFn, String delimiter) {
        return col.stream().map(toStringFn::apply).reduce((s, s2) -> s + delimiter + s2).orElse("");
    }


    public static <T> String join(Collection<T> col, String delimiter) {
        return join(col, t -> t.toString(), delimiter);
    }




    public static String toAntPath(String pPath) {
        if (!pPath.startsWith("/")) pPath += "/" + pPath;
        if (pPath.endsWith("/")) return pPath + "**";
        else if (pPath.endsWith("/*")) return pPath + "*";
        else if (pPath.endsWith("/**")) return pPath;
        return pPath + "/**";
    }


    public static Boolean toBoolean(Object o, Boolean defaultValue) {
        if (o instanceof Boolean) return (Boolean) o;
        else if (o instanceof String) return Boolean.valueOf((String) o);
        return defaultValue;
    }


    public static Integer toInteger(final Object obj, final Integer defaultValue) {
        if (obj instanceof Integer) return (Integer) obj;
        else if (obj instanceof Double) return ((Double) obj).intValue();
        else if (obj instanceof Float) return ((Float) obj).intValue();
        else if (obj instanceof BigDecimal) return ((BigDecimal) obj).intValue();
        else if (obj instanceof String) {
            try {
                return Integer.valueOf(obj.toString());
            } catch (final NumberFormatException nfe) {
                return defaultValue;
            }
        }
        return defaultValue;
    }


    public static Long toLong(final Object obj, final Long defaultValue) {
        if (obj instanceof Integer) return (Long) obj;
        else if (obj instanceof Double) return ((Double) obj).longValue();
        else if (obj instanceof Float) return ((Float) obj).longValue();
        else if (obj instanceof BigDecimal) return ((BigDecimal) obj).longValue();
        else if (obj instanceof String) {
            try {
                return Long.valueOf(obj.toString());
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
