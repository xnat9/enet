package org.xnatural.enet.common.handler;

import org.xnatural.enet.common.Log;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * v2
 * 按一定的规则执行逻辑链 link: processors
 * 按Processor 返回的状态值不同变换执行规则.
 *
 * @author hubert
 */
public class HandlerChain {
    final   Log             log        = Log.of(getClass());
    /**
     * current handler chain name.
     */
    private String          name;
    private List<Processor> processors = new CopyOnWriteArrayList<>();
    private Processor       breakCall;


    public HandlerChain() {
        this(null);
    }


    public HandlerChain(String name) {
        this.name = name;
    }


    public static HandlerChain create() {
        return new HandlerChain();
    }


    /**
     * add processor to this handler chain.
     *
     * @param processor Processor
     * @return
     */
    public HandlerChain add(Processor processor) {
        validate(processor);
        processors.add(processor);
        return this;
    }


    public HandlerChain breakCall(Processor processor) {
        validate(processor);
        breakCall = processor;
        return this;
    }


    public HandlerChain head(Processor processor) {
        validate(processor);
        processors.add(0, processor);
        return this;
    }


    public HandlerChain tail(Processor processor) {
        validate(processor);
        processors.add(processor);
        return this;
    }


    /**
     * 此方法不能写在 方法 chain 前边.
     *
     * @param processor
     * @return
     */
    public HandlerChain replace(Object key, Processor processor) {
        validate(processor);
        processors.set(processors.indexOf(findProcessorByKey(key)), processor);
        return this;
    }


    public HandlerChain before(Object key, Processor processor) {
        validate(processor);
        int i = processors.indexOf(findProcessorByKey(key));
        if (i == 0) processors.add(0, processor); //add head
        if (i == -1) throw new IllegalArgumentException("key(Processor): " + key + "不存在");
        else processors.add(i, processor);
        return this;
    }


    public HandlerChain after(Object key, Processor processor) {
        validate(processor);
        processors.add(processors.indexOf(findProcessorByKey(key)) + 1, processor);
        return this;
    }


    public Object run() {
        return run(new HandlerContext());
    }


    public Object run(HandlerContext ctx) {
        if (processors.size() < 1) throw new IllegalArgumentException("processor 至少一个");
        ctx.setHandler(this);
        for (int i = 0, size = processors.size(); i < size; i++) {
            Processor processor = processors.get(i);
            ctx.currentProcessor(processor);
            Status status;
            try {
                status = processor.process(ctx);
            } catch (Exception e) {
                throw e;
            }
            log.debug("processor({0}): {1}, status: {2}", i, processor, status);
            if (Status.BREAK == status) {
                if (breakCall != null) breakCall.process(ctx);
                break;
            } else if (status.isJump()) {
                while (i < size) {
                    if (i + 1 >= size) throw new IllegalArgumentException("jump 后没有可执行的Processor");
                    processor = processors.get(i + 1);
                    if (Objects.equals(processor.getKey(), status.key)) break;
                    else i++; // 后移
                }
            }
        }
        return ctx.getResult();
    }


    private Processor findProcessorByKey(Object key) {
        for (Processor processor : processors) {
            if (Objects.equals(key, processor.getKey())) return processor;
        }
        return null;
    }


    private void validate(Processor processor) {
        if (processor == null) {
            throw new IllegalArgumentException("processor 不能为空");
        }
        if (findProcessorByKey(processor.getKey()) != null) {
            throw new IllegalArgumentException("已存在key: " + processor.getKey());
        }
    }


    @Override
    public String toString() {
        return "handler chain: " + name;
    }


    public String getName() {
        return name;
    }


    public HandlerChain setName(String name) {
        this.name = name;
        return this;
    }


    public List<Processor> getProcessors() {
        return processors;
    }


    public void setProcessors(List<Processor> processors) {
        this.processors = processors;
    }
}
