package cn.xnatural.enet.common.builder;

import cn.xnatural.enet.common.Context;

/**
 * 从一个运行上下文中, 计算出一个值
 *
 * @param <T>
 * @author hubert
 */
@FunctionalInterface
public interface Builder<T> {

    T build(Context ctx);
}
