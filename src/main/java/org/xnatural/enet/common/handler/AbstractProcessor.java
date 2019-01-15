package org.xnatural.enet.common.handler;

import java.util.Objects;

public abstract class AbstractProcessor implements Processor {
    /**
     * processor key.
     */
    private Object key;


    public AbstractProcessor() {}


    public AbstractProcessor(Object key) {
        this.key = key;
    }


    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj instanceof Processor) {
            if (Objects.equals(key, ((Processor) obj).getKey())) {
                return true;
            }
        }
        return false;
    }


    @Override
    public String toString() {
        return Objects.toString(key, "");
    }


    @Override
    public Object getKey() {
        if (key == null) key = Processor.super.getKey();
        return key;
    }
}
