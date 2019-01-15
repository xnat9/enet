package org.xnatural.enet.common.devourer;

import org.xnatural.enet.common.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * {@link Devourer} 管理器
 */
public class DevourerManager {
    protected final Log                   log         = Log.of(getClass());
    protected final Map<Object, Devourer> devourerMap = new ConcurrentHashMap<>();
    protected       Executor              executor;


    public Devourer of(Object devourerKey) {
        return devourerMap.computeIfAbsent(devourerKey, o -> new Devourer(devourerKey, executor, this));
    }


    public Devourer offer(Object devourerKey, Runnable fn) {
        return devourerMap.computeIfAbsent(devourerKey, o -> new Devourer(devourerKey, executor, this)).offer(fn);
    }


    public void remove(Object devourerKey) {
        devourerMap.remove(devourerKey);
    }
}
