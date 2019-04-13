package cn.xnatural.enet.common.builder;

import cn.xnatural.enet.common.Context;
import cn.xnatural.enet.common.Log;

/**
 * This abstract class is a template which build any kind of value and return it
 *
 * @param <T>
 * @author hubert
 */
public abstract class AbstractBuilder<T> implements Builder<T> {
    protected final Log log = Log.of(getClass());

    /**
     * Enable or disable this generator. Usually it is used for feature
     * controlling.
     */
    private boolean enabled = true;


    /**
     * Generate any Object and return it. If isEnabled() == false. Return null.
     * If isValid() == false return null.
     *
     * @param ctx . It contains the information needed to build the value.
     */
    @Override
    public T build(Context ctx) {
        if (!isEnabled() || !isValid(ctx)) return null;
        return doBuild(ctx);
    }




    /**
     * do build javaBean by context.
     *
     * @param ctx context
     * @return javaBean
     */
    protected abstract T doBuild(Context ctx);


    /**
     * Validate the current context. Return false if fails
     *
     * @param ctx GeneratorContext
     * @return
     */
    protected boolean isValid(Context ctx) {
        return true;
    }


    /**
     * check whether params exists in pGeneratorContext.
     *
     * @param ctx generatorContext
     * @param keys
     * @return valid
     */
    protected boolean validateObjectInsideContext(Context ctx, Object... keys) {
        if (ctx == null || keys == null) {
            return true;
        }
        boolean valid = true;
        for (Object key : keys) {
            if (ctx.getAttr(key) == null) {
                valid = false;
                log.warn("当前 GeneratorContext 中, 不存在 key: " + key);
                break;
            }
        }
        return valid;
    }


    @Override
    public String toString() {
        return getClass().getSimpleName() + "@" + Integer.toHexString(hashCode());
    }


    public boolean isEnabled() {
        return enabled;
    }


    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
