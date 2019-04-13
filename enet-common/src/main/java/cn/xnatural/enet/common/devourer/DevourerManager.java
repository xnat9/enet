package cn.xnatural.enet.common.devourer;

import cn.xnatural.enet.common.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * {@link Devourer} 管理器
 */
public class DevourerManager {
    protected final Log                   log  = Log.of(DevourerManager.class);
    protected final Map<Object, Devourer> dMap = new ConcurrentHashMap<>(7);
    protected       Executor              exec;


    public DevourerManager() {}
    public DevourerManager(Executor exec) {
        this.exec = exec;
    }


    public Devourer of(Object devourerKey) {
        return dMap.computeIfAbsent(devourerKey, o -> new Devourer(devourerKey, exec, this));
    }


    public Devourer offer(Object devourerKey, Runnable fn) {
        return dMap.computeIfAbsent(devourerKey, o -> new Devourer(devourerKey, exec, this)).offer(fn);
    }


    public void remove(Object devourerKey) {
        dMap.remove(devourerKey);
    }
}
