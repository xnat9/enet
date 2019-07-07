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
import java.util.concurrent.atomic.AtomicLong;
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
    protected final AtomicLong         counter = new AtomicLong(0);
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

        gse = new GroovyScriptEngine(ctx.env().getString("groovy.root-urls", "./script"));
        log.info("inited");
    }


    @EL(name = {"groovy.eval"}, async = false)
    public Object eval(String scriptText, Consumer<Script> fn) {
        Script script = InvokerHelper.createScript(gcl.parseClass(scriptText, genScriptName()), new Binding(new LinkedHashMap(bindAttr)));
        if (fn == null) return script.run(); // 默认执行
        else fn.accept(script);
        return null;
    }


    @EL(name = {"groovy.script"}, async = false)
    public Object script(String scriptFile, Consumer<Script> fn) {
        Script script = null;
        try {
            script = InvokerHelper.createScript(gse.loadScriptByName(scriptFile), new Binding(new LinkedHashMap(bindAttr)));
        } catch (Exception e) { throw new RuntimeException(e); }
        if (fn == null) script.run(); // 默认执行
        else fn.accept(script);
        return null;
    }


    protected String genScriptName() {
        return "Script" + counter.getAndIncrement() + ".groovy";
    }
}
