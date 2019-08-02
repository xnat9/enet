package cn.xnatural.enet.common;

import org.apache.commons.beanutils.BeanUtils;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.spi.LocationAwareLogger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static cn.xnatural.enet.common.Utils.findMethod;
import static org.slf4j.event.Level.*;

/**
 * 通用 Log
 * 1. 支持 log.debug("user name is {0.name}, age is {0.age}", user)
 *    name和age 是 user对象的一个属性.
 *    这样做的好处是: 只有当debug为true时才会去计算 user.getName();
 * 2. 支持日志前后缀
 * 依赖 slf4j
 * 注意: 必须要调有下 {@link #init(Runnable)} 才能正常使用
 * @author hubert
 */
public class Log implements Logger {
    private static       boolean early         = true;
    private static final Pattern PATTERN_PARAM = Pattern.compile("\\{(([0-9]+).([\\w]+))\\}");
    private static final Pattern PATTERN_INDEX = Pattern.compile("\\{([0-9]+)\\}");
    private static final Pattern PATTERN_BRACE = Pattern.compile("\\{\\}");

    /**
     * delegate logger.
     */
    private              LocationAwareLogger logger;
    /**
     * 日志名
     */
    private              String              name;
    /**
     * 日志前缀提供器
     */
    private              Supplier<String>    prefixSupplier;
    /**
     * 日志后缀提供器
     */
    private              Supplier<String>    suffixSupplier;
    private static final Object[]            EMPTY = new Object[0];
    private static final boolean             POST_1_6;
    private static final Method              LOG_METHOD;
    private static final String              FQCN  = Log.class.getName();
    private static       Log                 root;

    static {
        Method[] methods = LocationAwareLogger.class.getDeclaredMethods();
        Method logMethod = null;
        boolean post16 = false;
        for (Method method : methods) {
            if (method.getName().equals("log")) {
                logMethod = method;
                Class<?>[] parameterTypes = method.getParameterTypes();
                post16 = parameterTypes.length == 6;
                break;
            }
        }
        if (logMethod == null) {
            throw new NoSuchMethodError("Cannot find LocationAwareLogger.log() method");
        }
        POST_1_6 = post16;
        LOG_METHOD = logMethod;
        root = of(Logger.ROOT_LOGGER_NAME);
        if ("true".equalsIgnoreCase(System.getProperty("enet.initlog", "false"))) init(null);
    }


    /**
     * format String like "my name: {0.name}" use pArgs.
     * 支持格式字符串: "{}", "{0}", "{0.name}"
     * @param pFormat
     *            format String.
     * @param pArgs
     *            arguments
     * @return formatted String.
     */
    public static String format(String pFormat, Object... pArgs) {
        if (pArgs == null || pArgs.length < 1) {
            return pFormat;
        }
        // find {}
        Matcher matcher = PATTERN_BRACE.matcher(pFormat);
        if (matcher.find()) return MessageFormatter.arrayFormat(pFormat, pArgs).getMessage();
        // find 类似于: {0}
        Matcher m = PATTERN_INDEX.matcher(pFormat);
        if (m.find()) return MessageFormat.format(pFormat, pArgs);
        // find 类似于: {0.name}
        matcher = PATTERN_PARAM.matcher(pFormat);
        if (!matcher.find()) return pFormat;

        StringBuilder sb = new StringBuilder(pFormat);
        matcher = PATTERN_PARAM.matcher(sb);
        List<Object> args = new ArrayList<>(pArgs.length + 2);
        // "{0.name}" regex resolve:
        // group(1) => {0.name}
        // group(2) => 0
        // group(3) => name
        int count = 0;
        while (matcher.find()) {
            Object indexMappedObject = null;
            try {
                int index = Utils.toInteger(matcher.group(2), -1);
                if (index < 0) {
                    continue;
                }
                String propExpression = matcher.group(3);
                indexMappedObject = BeanUtils.getProperty(pArgs[index], propExpression);
            } catch (Exception e) {
                // ignore. set it's mapped value is null.
                System.out.println(e.getMessage());
            }
            args.add(indexMappedObject);
            // change "0.name" to "0".
            sb = new StringBuilder(sb.toString().replaceFirst(matcher.group(1), String.valueOf(count)));
            //            sb = sb.replaceFirst(matcher.group(1), String.valueOf(count));
            matcher = PATTERN_PARAM.matcher(sb);
            count++;
        }
        return MessageFormat.format(sb.toString(), args.toArray());
    }


    protected Log() {}
    protected Log(LocationAwareLogger logger) {
        this.logger = logger;
    }

    /**
     *
     * @param name NOTE: 不要是个变化的String
     * @return
     */
    public static Log of(String name) {
        if (early) {
            synchronized (Log.class) {
                if (early) {
                    Log l = new Log(); l.name = name;
                    return l;
                }
            }
        };
        return new Log((LocationAwareLogger) LoggerFactory.getLogger(name));
    }
    public static Log of(Class<?> clazz) { return of(clazz.getName()); }
    public static Log root() { return root; }


    /**
     * 配置日志系统
     * @param cfgFn 配置函数
     */
    public static void init(Runnable cfgFn) {
        LoggerFactory.getILoggerFactory(); // 必须先执行
        if (early) {
            synchronized (Log.class) {
                if (early) {
                    if (cfgFn != null) cfgFn.run();
                    while (!earlyLog.isEmpty()) {
                        LogData d = earlyLog.poll();
                        if (d.log.logger == null) {d.log.logger = (LocationAwareLogger) LoggerFactory.getLogger(d.log.name); d.log.name = null;}
                        if (d.log.isEnabled(d.level)) {
                            StringBuilder sb = new StringBuilder();
                            if (d.log.prefixSupplier != null) sb.append(d.log.prefixSupplier.get()).append(d.msg);
                            else sb.append(d.msg);
                            if (d.log.suffixSupplier != null) sb.append(d.log.suffixSupplier.get());
                            doLog(d.log.logger, null, FQCN, translate(d.level), format(sb.toString(), d.args), d.th);
                        }
                    }
                    early = false;
                }
            }
        }
    }


    /**
     * 日志数据结构
     */
    protected static class LogData {
        Log log; Level level;
        String msg; Object[] args; Throwable th;
    }

    // 早期日志
    private static Queue<LogData> earlyLog = new ConcurrentLinkedQueue<>();


    public void doLog(final Level level, final Throwable th, final String msg, final Object... args) {
        doLog(level, null, th, msg, args);
    }


    private void doLog(final Level level, Marker marker, final Throwable th, final String msg, final Object... args) {
        if (early) {
            synchronized (Log.class) {
                if (early) { // 早期日志(环境还没初始化完成)
                    LogData d = new LogData();
                    d.log = this; d.level = level;
                    d.msg = msg; d.args = args; d.th = th;
                    earlyLog.offer(d);
                    if (earlyLog.size() > 200) {
                        earlyLog.poll(); System.err.println("early log too much");
                    }
                    return;
                }
            }
        }

        if (logger == null) {
            logger = (LocationAwareLogger) LoggerFactory.getLogger(name); name = null;
        }

        if (isEnabled(level)) {
            StringBuilder sb = new StringBuilder();
            if (prefixSupplier != null) sb.append(prefixSupplier.get()).append(msg);
            else sb.append(msg);
            if (suffixSupplier != null) sb.append(suffixSupplier.get());
            doLog(logger, marker, FQCN, translate(level), format(sb.toString(), args), th);
        }
    }


    private static void doLog(LocationAwareLogger logger, Marker marker, String className, int level, String msg, Throwable th) {
        try {
            if (POST_1_6) {
                LOG_METHOD.invoke(logger, marker, className, level, msg, EMPTY, th);
            } else {
                LOG_METHOD.invoke(logger, marker, className, level, msg, th);
            }
        } catch (InvocationTargetException e) {
            try {
                throw e.getCause();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Error er) {
                throw er;
            } catch (Throwable t) {
                throw new UndeclaredThrowableException(t);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }


    private static int translate(Level level) {
        if (level != null) switch (level) {
            case ERROR: return LocationAwareLogger.ERROR_INT;
            case WARN:  return LocationAwareLogger.WARN_INT;
            case INFO:  return LocationAwareLogger.INFO_INT;
            case DEBUG: return LocationAwareLogger.DEBUG_INT;
            case TRACE: return LocationAwareLogger.TRACE_INT;
        }
        return LocationAwareLogger.TRACE_INT;
    }


    public void setLevel(String level) {
        setLevel(logger.getName(), level);
    }


    /**
     * 运行时改变 日志等级
     * @param logger
     * @param level
     */
    public static void setLevel(String logger, String level) {
        ILoggerFactory fa = LoggerFactory.getILoggerFactory();
        // 配置 logback
        if ("ch.qos.logback.classic.LoggerContext".equals(fa.getClass().getName())) {
            // 设置日志级别
            try {
                Method setLevel = findMethod(Class.forName("ch.qos.logback.classic.Logger"), "setLevel", Class.forName("ch.qos.logback.classic.Level"));
                Method toLevel = findMethod(Class.forName("ch.qos.logback.classic.Level"), "toLevel", String.class);
                setLevel.invoke(fa.getLogger(logger), toLevel.invoke(null, level));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ====================log method ==============

    public void error(String pMsg, Object... pArgs) {
        doLog(ERROR, null, pMsg, pArgs, null);
    }


    @Override
    public void error(String msg, Throwable t) {
        doLog(ERROR, null, t, msg);
    }


    @Override
    public boolean isErrorEnabled(Marker marker) {
        return logger == null ? true : logger.isErrorEnabled(marker);
    }


    @Override
    public void error(Marker marker, String msg) {
        doLog(ERROR, marker, null, msg);
    }


    @Override
    public void error(Marker marker, String format, Object arg) {
        doLog(ERROR, marker, null, format, arg);
    }


    @Override
    public void error(Marker marker, String format, Object arg1, Object arg2) {
        doLog(ERROR, marker, null, format, arg1, arg2);
    }


    @Override
    public void error(Marker marker, String format, Object... arguments) {
        doLog(ERROR, marker, null, format, arguments);
    }


    @Override
    public void error(Marker marker, String msg, Throwable t) {
        doLog(ERROR, marker, t, msg);
    }


    public void error(Throwable t) {
        doLog(ERROR, null, t, null);
    }


    public void error(Throwable t, String pMsg, Object... pArgs) {
        doLog(ERROR, null, t, pMsg, pArgs);
    }


    public void warn(Throwable t, String pMsg, Object... pArgs) {
        doLog(WARN, null, t, pMsg, pArgs);
    }


    public void warn(String pMsg, Object... pArgs) {
        doLog(WARN, null, null, pMsg, pArgs);
    }


    @Override
    public void warn(String format, Object arg1, Object arg2) {
        doLog(WARN, null, (Throwable) null, format, arg1, arg2);
    }


    @Override
    public void warn(String msg, Throwable t) {
        doLog(WARN, null, t, msg);
    }


    @Override
    public boolean isWarnEnabled(Marker marker) {
        return logger == null ? true : logger.isWarnEnabled(marker);
    }


    @Override
    public void warn(Marker marker, String msg) {
        doLog(WARN, marker, null, msg);
    }


    @Override
    public void warn(Marker marker, String format, Object arg) {
        doLog(WARN, marker, null, format, arg);
    }


    @Override
    public void warn(Marker marker, String format, Object arg1, Object arg2) {
        doLog(WARN, marker, null, format, arg1, arg2);
    }


    @Override
    public void warn(Marker marker, String format, Object... arguments) {
        doLog(WARN, marker, null, format, arguments);
    }


    @Override
    public void warn(Marker marker, String msg, Throwable t) {
        doLog(WARN, marker, t, msg);
    }


    public void info(String pMsg, Object... pArgs) {
        doLog(INFO, null, null, pMsg, pArgs);
    }


    @Override
    public void info(String msg, Throwable t) {
        doLog(INFO, null, t, msg);
    }


    @Override
    public boolean isInfoEnabled(Marker marker) {
        return logger == null ? true : logger.isInfoEnabled(marker);
    }


    @Override
    public void info(Marker marker, String msg) {
        doLog(INFO, marker, null, msg);
    }


    @Override
    public void info(Marker marker, String format, Object arg) {
        doLog(INFO, marker, null, format, arg);
    }


    @Override
    public void info(Marker marker, String format, Object arg1, Object arg2) {
        doLog(INFO, marker, null, format, arg1, arg2);
    }


    @Override
    public void info(Marker marker, String format, Object... arguments) {
        doLog(INFO, marker, null, format, arguments);
    }


    @Override
    public void info(Marker marker, String msg, Throwable t) {
        doLog(INFO, marker, t, msg);
    }


    public void info(Throwable t, String pMsg, Object... pArgs) {
        doLog(INFO, null, t, pMsg, pArgs);
    }


    public void debug(String pMsg, Object... pArgs) {
        doLog(DEBUG, null, null, pMsg, pArgs);
    }


    @Override
    public void debug(String msg, Throwable t) {
        doLog(DEBUG, null, t, msg);
    }


    @Override
    public boolean isDebugEnabled(Marker marker) {
        return logger == null ? false : logger.isDebugEnabled(marker);
    }


    @Override
    public void debug(Marker marker, String msg) {
        doLog(DEBUG, marker, null, msg);
    }


    @Override
    public void debug(Marker marker, String format, Object arg) {
        doLog(DEBUG, marker, null, format, arg);
    }


    @Override
    public void debug(Marker marker, String format, Object arg1, Object arg2) {
        doLog(DEBUG, marker, null, format, arg1, arg2);
    }


    @Override
    public void debug(Marker marker, String format, Object... arguments) {
        doLog(DEBUG, marker, null, format, arguments);
    }


    @Override
    public void debug(Marker marker, String msg, Throwable t) {
        doLog(DEBUG, marker, t, null);
    }


    public void debug(Throwable t, String pMsg, Object... pArgs) {
        doLog(DEBUG, null, t, pMsg, pArgs);
    }


    public void trace(String pMsg, Object... pArgs) {
        doLog(TRACE, null, null, pMsg, pArgs);
    }


    @Override
    public void trace(String msg, Throwable t) {
        doLog(TRACE, null, t, null);
    }


    @Override
    public boolean isTraceEnabled(Marker marker) {
        return (logger == null ? false : logger.isTraceEnabled(marker));
    }


    @Override
    public void trace(Marker marker, String msg) {
        doLog(TRACE, marker, null, msg);
    }


    @Override
    public void trace(Marker marker, String format, Object arg) {
        doLog(TRACE, marker, null, format, arg);
    }


    @Override
    public void trace(Marker marker, String format, Object arg1, Object arg2) {
        doLog(TRACE, marker, null, format, arg1, arg2);
    }


    @Override
    public void trace(Marker marker, String format, Object... argArray) {
        doLog(TRACE, marker, null, format, argArray);
    }


    @Override
    public void trace(Marker marker, String msg, Throwable t) {
        doLog(TRACE, marker, t, msg);
    }


    public void trace(Throwable t, String pMsg, Object... pArgs) {
        doLog(TRACE, null, t, pMsg, pArgs);
    }



    // ====================log method ==============
    private boolean isEnabled(final Level level) {
        if (logger == null && !early) {
            logger = (LocationAwareLogger) LoggerFactory.getLogger(name); name = null;
        }
        if (level != null) switch (level) {
            case ERROR: return logger == null ? true : logger.isErrorEnabled();
            case WARN:  return logger == null ? true : logger.isWarnEnabled();
            case INFO:  return logger == null ? true : logger.isInfoEnabled();
            case DEBUG: return logger == null ? false : logger.isDebugEnabled();
            case TRACE: return logger == null ? false : logger.isTraceEnabled();
        }
        return false;
    }


    public boolean isInfoEnabled() {
        return isEnabled(INFO);
    }


    @Override
    public void info(String msg) {
        doLog(INFO, null, (Throwable) null, msg);
    }


    @Override
    public void info(String format, Object arg) {
        doLog(INFO, null, (Throwable) null, format, arg);
    }


    @Override
    public void info(String format, Object arg1, Object arg2) {
        doLog(INFO, null, (Throwable) null, format, arg1, arg2);
    }


    public boolean isWarnEnabled() {
        return isEnabled(WARN);
    }


    @Override
    public void warn(String msg) {
        doLog(WARN, null, (Throwable) null, msg);
    }


    @Override
    public void warn(String format, Object arg) {
        doLog(WARN, null, (Throwable) null, format, arg);
    }


    public boolean isErrorEnabled() {
        return isEnabled(ERROR);
    }


    @Override
    public void error(String msg) {
        doLog(ERROR, null, (Throwable) null, msg);
    }


    @Override
    public void error(String format, Object arg) {
        doLog(ERROR, null, (Throwable) null, format, arg);
    }


    @Override
    public void error(String format, Object arg1, Object arg2) {
        doLog(ERROR, null, (Throwable) null, format, arg1, arg2);
    }


    public boolean isDebugEnabled() {
        return isEnabled(DEBUG);
    }


    @Override
    public void debug(String msg) {
        doLog(DEBUG, null, (Throwable) null, msg);
    }


    @Override
    public void debug(String format, Object arg) {
        doLog(DEBUG, null, (Throwable) null, format, arg);
    }


    @Override
    public void debug(String format, Object arg1, Object arg2) {
        doLog(DEBUG, null, (Throwable) null, format, arg1, arg2);
    }


    @Override
    public String getName() {
        return logger == null ? name : logger.getName();
    }


    public boolean isTraceEnabled() {
        return isEnabled(TRACE);
    }


    @Override
    public void trace(String msg) {
        doLog(TRACE, null, (Throwable) null, msg);
    }


    @Override
    public void trace(String format, Object arg) {
        doLog(TRACE, null, (Throwable) null, format, arg);
    }


    @Override
    public void trace(String format, Object arg1, Object arg2) {
        doLog(TRACE, null, (Throwable) null, format, arg1, arg2);
    }


    public Log setPrefixSupplier(Supplier<String> prefixSupplier) {
        this.prefixSupplier = prefixSupplier;
        return this;
    }

    public Log setSuffixSupplier(Supplier<String> suffixSupplier) {
        this.suffixSupplier = suffixSupplier;
        return this;
    }


    @Override
    public String toString() {
        return logger == null ? name: logger.getName();
    }
}
