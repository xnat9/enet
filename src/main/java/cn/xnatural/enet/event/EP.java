package cn.xnatural.enet.event;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;

/**
 * event publisher 事件发布器.事件分发中心
 * TODO 事件死锁. 事件执行链
 */
public class EP {
    protected Logger                      log;
    protected Executor                    exec;
    /**
     * 事件名 -> 监听器{@link Listener}
     */
    protected Map<String, List<Listener>> lsMap;
    /**
     * 需要追踪执行的事件名字
     */
    protected Set<String>                 trackEvents;


    public EP() { init(null, null); }
    public EP(Executor exec) { init(exec, null); }
    public EP(Executor exec, Logger log) { init(exec, log); }


    /**
     * 初始化
     * @param exec
     */
    protected void init(Executor exec, Logger log) {
        this.exec = exec;
        if (log == null) this.log = LoggerFactory.getLogger(EP.class);
        else this.log = log;
        lsMap = new ConcurrentHashMap<>(7);
        trackEvents = new HashSet<>(7);
    }


    /**
     * {@link #doPublish(String, EC)}
     * @param eName 事件名
     */
    public Object fire(String eName) {
        return fire(eName, new EC());
    }


    /**
     * {@link #doPublish(String, EC)}
     * @param eName 事件名
     * @param args 监听器方法的参数列表
     */
    public Object fire(String eName, Object...args) {
        return fire(eName, new EC().args(args));
    }


    /**
     * 触发事件
     * @param eName 事件名
     * @param ec 事件执行上下文
     */
    public Object fire(String eName, EC ec) {
        return doPublish(eName, ec);
    }


    /**
     * 发布事件到各个监听者并执行
     * @param eName 事件名
     * @param ec {@link EC} 事件执行过程上下文
     * @return 事件执行结果. Note: 取返回值时, 要注意是同步执行还是异步执行
     */
    protected Object doPublish(String eName, EC ec) {
        if (eName == null || eName.isEmpty()) throw new IllegalArgumentException("eName is empty");
        if (ec == null) throw new NullPointerException("ec must not be null");
        if (trackEvents.contains(eName) || log.isTraceEnabled()) ec.track = true;

        List<Listener> ls = lsMap.get(eName); // 获取需要执行的监听器
        ls = new LinkedList<>(ls == null ? emptyList() : ls); // 避免在执行的过程中动态增删事件的监听器而导致个数错误
        ec.start(eName, ls, this); // 初始化

        if (ec.isNoListener()) { ec.tryFinish(); return ec.result; } // 没有监听器的情况

        if (exec == null) { // 只能同步执行
            for (Listener l : ls) l.invoke(ec);
        } else {
            // 异步, 同步执行的监听器, 分开执行
            List<Listener> asyncLs = new ArrayList<>(ls.size()); // 异步执行的监听器
            List<Listener> syncLs = new ArrayList<>(ls.size()); // 同步执行的监听器
            if (ec.async == null) {
                for (Listener l : ls) {
                    if (l.async) asyncLs.add(l);
                    else syncLs.add(l);
                }
            } else {
                if (ec.async) asyncLs.addAll(ls); // 强制全部异步执行
                else syncLs.addAll(ls); // 强制全部同步执行
            }

            syncLs.forEach(l -> l.invoke(ec)); // 先执行同步监听器
            asyncLs.forEach(l -> exec.execute(() -> l.invoke(ec)));
        }
        return ec.result;
    }


    /**
     * 是否存在事件监听器
     * @param eNames
     * @return true if exist
     */
    public boolean exist(String...eNames) {
        if (eNames == null) return false;
        for (String n : eNames) {
            if (!lsMap.containsKey(n)) return false;
        }
        return true;
    }


    /**
     * 添加对象源(解析出监听器)
     * @param source
     * @return
     */
    public EP addListenerSource(Object source) {
        resolve(source);
        return this;
    }


    /**
     * 设置某个事件需要追踪执行
     * @param eNames 事件名字(可以多个)
     * @return
     */
    public EP addTrackEvent(String... eNames) {
        if (eNames == null) return this;
        for (String n : eNames) if (n != null && !n.trim().isEmpty()) trackEvents.add(n.trim());
        return this;
    }


    /**
     * 删除事件追踪
     * @param eNames
     * @return
     */
    public EP delTrackEvent(String... eNames) {
        if (eNames == null) return this;
        for (String n : eNames) if (n != null && !n.trim().isEmpty()) trackEvents.remove(n);
        return this;
    }


    /**
     * 删除指定对象源中的事件监听
     * @param eName 事件名
     * @param source 监听器对象源
     * @return this ep
     */
    public EP removeEvent(String eName, Object source) {
        log.debug("remove event '{}' with source: {}", eName, source);
        if (source == null) lsMap.remove(eName);
        else {
            for (Iterator<Listener> it = lsMap.get(eName).iterator(); it.hasNext(); ) {
                if (it.next().source == source) it.remove();
            }
        }
        return this;
    }

    /**
     * 移除事件监听器
     * @param eName 事件名
     * @return this ep
     */
    public EP removeEvent(String eName) {return removeEvent(eName, null);}


    /**
     * 添加监听
     * @param eName 事件名
     * @param fn 函数
     * @param async 是否异步
     * @param order 执行顺序, 越小越先执行
     * @return {@link EP}
     */
    public EP listen(String eName, Runnable fn, boolean async, float order) {
        if (fn == null) throw new NullPointerException("fn must not be null");
        List<Listener> ls = lsMap.get(eName);
        if (ls == null) {
            synchronized (this) {
                ls = lsMap.get(eName);
                if (ls == null) {
                    ls = new LinkedList<>(); lsMap.put(eName, ls);
                }
            }
        }
        Listener l = new Listener(); ls.add(l);
        l.name = eName; l.fn = fn; l.async = async; l.order = order;
        return this;
    }


    /**
     * 添加监听
     * @param eName 事件名
     * @param fn 函数
     * @param async 是否异步
     * @param order 执行顺序, 越小越先执行
     * @return {@link EP}
     */
    public EP listen(String eName, Function fn, boolean async, float order) {
        if (fn == null) throw new NullPointerException("fn must not be null");
        List<Listener> ls = lsMap.get(eName);
        if (ls == null) {
            synchronized (this) {
                ls = lsMap.get(eName);
                if (ls == null) {
                    ls = new LinkedList<>(); lsMap.put(eName, ls);
                }
            }
        }
        Listener l = new Listener(); ls.add(l);
        l.name = eName; l.fn1 = fn; l.async = async; l.order = order;
        return this;
    }


    /**
     * 添加监听
     * @param eName 事件名
     * @param fn 函数
     * @param async 是否异步
     * @param order 执行顺序, 越小越先执行
     * @return {@link EP}
     */
    public EP listen(String eName, BiFunction fn, boolean async, float order) {
        if (fn == null) throw new NullPointerException("fn must not be null");
        List<Listener> ls = lsMap.get(eName);
        if (ls == null) {
            synchronized (this) {
                ls = lsMap.get(eName);
                if (ls == null) {
                    ls = new LinkedList<>(); lsMap.put(eName, ls);
                }
            }
        }
        Listener l = new Listener(); ls.add(l);
        l.name = eName; l.fn2 = fn; l.async = async; l.order = order;
        return this;
    }


    /**
     * 添加监听
     * @param eName 事件名
     * @param fn 函数
     * @return {@link EP}
     */
    public EP listen(String eName, Runnable fn) { return listen(eName, fn, false, 0); }

    /**
     * 添加监听
     * @param eName 事件名
     * @param fn 函数
     * @param async 是否异步
     * @return {@link EP}
     */
    public EP listen(String eName, Runnable fn, boolean async) { return listen(eName, fn, async, 0); }

    /**
     * 添加监听
     * @param eName 事件名
     * @param fn 函数
     * @return {@link EP}
     */
    public EP listen(String eName, Function fn) { return listen(eName, fn, false, 0); }

    /**
     * 添加监听
     * @param eName 事件名
     * @param fn 函数
     * @param async 是否异步
     * @return {@link EP}
     */
    public EP listen(String eName, Function fn, boolean async) { return listen(eName, fn, async, 0); }

    /**
     * 添加监听
     * @param eName 事件名
     * @param fn 函数
     * @return {@link EP}
     */
    public EP listen(String eName, BiFunction fn) { return listen(eName, fn, false, 0); }

    /**
     * 添加监听
     * @param eName 事件名
     * @param fn 函数
     * @param async 是否异步
     * @return {@link EP}
     */
    public EP listen(String eName, BiFunction fn, boolean async) { return listen(eName, fn, async, 0); }


    /**
     * 从一个对象中 解析出 所有带有 {@link EL}注解的方法 转换成监听器{@link Listener}
     * 如果带有注解 {@link EL}的方法被重写, 则用子类的方法
     * @param source
     */
    protected void resolve(final Object source) {
        if (source == null) return;
        iterateMethod(source.getClass(), m -> { // 查找包含@EL注解的方法
            EL el = m.getDeclaredAnnotation(EL.class);
            if (el == null) return;
            for (String n : el.name()) {
                Listener listener = new Listener();
                listener.async = el.async(); listener.source = source; listener.order = el.order();
                listener.m = m; m.setAccessible(true); listener.name = parseName(n, source);
                if (listener.name == null) continue;

                List<Listener> ls = lsMap.get(listener.name);
                if (ls == null) {
                    synchronized (this) {
                        ls = lsMap.get(listener.name);
                        if (ls == null) {
                            ls = new LinkedList<>(); lsMap.put(listener.name, ls);
                        }
                    }
                }
                // 同一个对象源中, 不能有相同的事件监听名. 忽略
                if (ls.stream().anyMatch(l -> l.source == source && Objects.equals(l.name, listener.name))) {
                    log.warn("Exist listener. name: {}, source: {}", n, listener.source);
                    continue;
                }
                // 同一个对象源中, 不同的监听, 方法名不能相同.
                if (ls.stream().anyMatch(l -> l.source == source && Objects.equals(l.m.getName(), listener.m.getName()))) {
                    log.warn("Same source same method name only one listener. source: {}, methodName: {}", source, m.getName());
                    continue;
                }
                log.debug("Add listener [name: {}, source: {}, method: {}, async: {}, order: {}]", listener.name, source, m.getName(), listener.async, listener.order);
                ls.add(listener); ls.sort(Comparator.comparing(o -> o.order));
            }
        });
    }


    protected Pattern p = Pattern.compile("\\$\\{(?<attr>\\w+)\\}");
    /**
     * 支持表达式 ${attr}.eventName, ${attr}会被替换成 对象中的属性attr的值
     * @param name
     * @param source
     * @return
     */
    protected String parseName(String name, Object source) {
        Matcher matcher = p.matcher(name);
        if (!matcher.find()) return name;
        String attr = matcher.group("attr");
        // 查找属性对应的get方法
        Method m = findMethod(source.getClass(), mm -> {
            if (Modifier.isPublic(mm.getModifiers()) &&
                mm.getParameterCount() == 0 && // 参数个数为0
                String.class.equals(mm.getReturnType()) && // 返回String类型
                ("get" + (attr.substring(0, 1).toUpperCase() + attr.substring(1))).equals(mm.getName()) // get方法名相同
            ) return true;
            return false;
        });
        if (m != null) {
            try {
                Object v = m.invoke(source);
                if (v == null || v.toString().isEmpty()) {
                    log.warn("Parse event name '{}' error. Get property '{}' is empty from '{}'.", name, attr, source);
                    return null;
                }
                return matcher.replaceAll(v.toString());
            } catch (Exception e) { log.error("", e); }
        }
        log.warn("Parse event name '{}'. Not found property '{}' from {} ", name, attr, source);
        return null;
    }


    // 遍历一个类的方法
    protected void iterateMethod(final Class clz, Consumer<Method>... fns) {
        if (fns == null || fns.length < 1) return;
        Class c = clz;
        do {
            for (Method m : c.getDeclaredMethods()) for (Consumer<Method> fn : fns) fn.accept(m);
            c = c.getSuperclass();
        } while (c != null);
    }


    // 查找类中的方法
    protected Method findMethod(final Class clz, Predicate<Method> predicate) {
        Class c = clz;
        do {
            for (Method m : c.getDeclaredMethods()) if (predicate.test(m)) return m;
            c = c.getSuperclass();
        } while (c != null);
        return null;
    }


    /**
     * 监听器包装类
     */
    protected class Listener {
        //  监听执行体. (一个方法).
        protected Object source; Method  m;
        /**
         * 和 {@link #m} 只存在一个
         * 监听器执行体. (一段执行逻辑)
         */
        protected Runnable fn; protected Function<Object, Object> fn1; protected BiFunction<Object, Object, Object> fn2;
        /**
         * 监听的事件名
         */
        protected String name;
        /**
         * 排序. 一个事件对应多个监听器时生效. {@link #resolve(Object)} {@link #doPublish(String, EC)}
         */
        protected float order;
        /**
         * 是否异步
         */
        protected boolean async;

        // 调用此监听器
        protected void invoke(EC ec) {
            try {
                if (fn != null && (ec.args == null)) fn.run();
                else if (fn1 != null && (ec.args != null && ec.args.length == 1)) ec.result = fn1.apply(ec.args[0]);
                else if (fn2 != null && (ec.args != null && ec.args.length == 2)) ec.result = fn2.apply(ec.args[0], ec.args[1]);
                else {
                    Object r = null;
                    if (m.getParameterCount() == 0) r = m.invoke(source); // 没有参数的情况.直接调用
                    else if (m.getParameterCount() == 1) { // 1个参数的情况
                        Class<?> t = m.getParameterTypes()[0];
                        if (EC.class.isAssignableFrom(t)) r = m.invoke(source, ec);
                        else if (t.isArray()) { // 如果是数组需要转下类型
                            Object arr = Array.newInstance(t.getComponentType(), ec.args.length);
                            for (int i = 0; i < ec.args.length; i++) {
                                Array.set(arr, i, t.getComponentType().cast(ec.args[i]));
                            }
                            r = m.invoke(source, arr);
                        }
                        else {
                            Object arg = (ec.args == null || ec.args.length < 1) ? null : ec.args[0];
                            r = m.invoke(source, arg);
                        }
                    } else { // 参数个数多于1个的情况
                        Object[] args = new Object[m.getParameterCount()]; // 参数传少了, 补null, 传多了忽略
                        if (EC.class.isAssignableFrom(m.getParameterTypes()[0])) {
                            args[0] = ec;
                            if (ec.args != null) {
                                for (int i = 1; i <= ec.args.length && i < m.getParameterCount(); i++) args[i] = ec.args[i-1];
                            }
                        } else if (ec.args != null) {
                            for (int i = 0; i < ec.args.length && i < m.getParameterCount(); i++) args[i] = ec.args[i];
                        }
                        r = m.invoke(source, args);
                    }
                    if (!void.class.isAssignableFrom(m.getReturnType())) ec.result = r;
                }

                ec.passed(this, true);
                if (ec.track) {
                    log.info("Passed listener of event '{}'. method: {}, id: {}, result: {}",
                        name, (m == null ? "" : source.getClass().getSimpleName() + "." + m.getName()),
                        ec.id, ec.result
                    );
                }
            } catch (Throwable e) {
                ec.passed(this, false).ex(e.getCause() == null ? e : e.getCause());
                log.error(
                    MessageFormatter.arrayFormat(
                        "Listener invoke error! eName: {}, id: {}, method: {}, event source: {}",
                        new Object[]{
                            name, ec.id,
                            (m == null ? "" : source.getClass().getSimpleName() + "." + m.getName()),
                            (ec.source() == null ? "" : ec.source().getClass().getSimpleName())
                        }).getMessage(),
                    ec.ex);
            } finally {
                ec.tryFinish();
            }
        }
    }
}
