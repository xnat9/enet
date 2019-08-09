package cn.xnatural.enet.core;

import cn.xnatural.enet.common.Log;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;
import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import groovy.util.GroovyScriptEngine;
import org.codehaus.groovy.runtime.InvokerHelper;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * @author xiangxb, 2019-07-06
 */
public class GroovyEngine {
    final     Log        log;
    @Resource
    protected Executor   exec;
    @Resource
    protected EP         ep;
    @Resource
    protected AppContext ctx;

    protected       Map                bindAttr;
    protected       GroovyScriptEngine gse;
    protected       GroovyClassLoader  gcl;


    public GroovyEngine() {
        log = Log.of(GroovyEngine.class);
        log.setPrefixSupplier(() -> "[Groovy Engine]: ");
    }


    @EL(name = "sys.starting", async = false)
    protected void init() throws Exception {
        gcl = new GroovyClassLoader();
        bindAttr = new HashMap(5);
        bindAttr.put("log", log);
        bindAttr.put("ctx", ctx);
        bindAttr.put("env", ctx.env());
        bindAttr.put("ep", ep);
        bindAttr.put("exec", exec);

        String root_urls = ctx.env().getString("groovy.root-urls", "./script") + "/";
        gse = new GroovyScriptEngine(root_urls);
        log.info("inited. root-urls: {}", root_urls);
    }


    /**
     * 执行一段脚本代码
     * @param scriptText 脚本代码
     * @param fn 消费脚本函数
     * @return 当fn为空时,默认执行脚本并返回结果
     */
    @EL(name = {"groovy.eval"}, async = false)
    public Object eval(String scriptText, Consumer<Script> fn) {
        Script script = InvokerHelper.createScript(gcl.parseClass(scriptText), new Binding(new LinkedHashMap(bindAttr)));
        if (fn == null) return script.run(); // 默认执行
        else fn.accept(script);
        return null;
    }


    /**
     * 执行一个脚本文件
     * @param scriptFile 脚本文件名
     * @param fn 消费脚本函数
     * @return 当fn为空时,默认执行脚本并返回结果
     */
    @EL(name = {"groovy.script"}, async = false)
    public Object script(String scriptFile, Consumer<Script> fn) {
        Script script;
        try {
            script = InvokerHelper.createScript(gse.loadScriptByName(scriptFile), new Binding(new LinkedHashMap(bindAttr)));
        } catch (Exception e) { throw new RuntimeException(e); }
        if (fn == null) return script.run(); // 默认执行
        else fn.accept(script);
        return null;
    }
}
