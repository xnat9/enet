package org.xnatural.enet.common.handler;

/**
 * scope is global.
 * Logical split.
 * please extends AbstractProcessor.
 *
 * @author hubert
 */
@FunctionalInterface
public interface Processor {
    /**
     * 用于和 Status.key 匹配.
     *
     * @return processor key.
     */
    default Object getKey() {
        return getClass().getSimpleName() + ":" + Integer.toHexString(hashCode());
    }

    /**
     * logic process method.
     *
     * @param handlerContext HandlerContext
     * @return Status
     */
    Status process(HandlerContext handlerContext);

}
