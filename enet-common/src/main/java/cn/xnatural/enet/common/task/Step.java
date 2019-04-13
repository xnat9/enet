package cn.xnatural.enet.common.task;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 执行步骤/执行节点
 * 每个 Step 通过 {@link #next()} 顺序关联
 * 核心方法: {@link #run()}
 * @param <I>  输入参数类型
 * @param <R>  步骤返回值类型
 */
public class Step<I, R> {
    /**
     * 执行步骤 的名称
     */
    private   String            name;
    /**
     * 执行步骤 的说明
     */
    private   String            description;
    /**
     * 开始执行时间
     */
    private   long              startTime;
    /**
     * 执行时所需的参数
     */
    protected I                 param;
    /**
     * 保存执行结果.
     */
    protected R                 result;
    /**
     * 执行次数
     */
    protected AtomicInteger     count = new AtomicInteger(0);
    /**
     * 执行体(当前Step的主要执行逻辑)
     */
    protected Function<I, R>    fn;
    /**
     * 下一个 {@link Step} 判断的函数
     * 返回下一个 执行的 {@link Step}
     */
    protected Function<R, Step> nextStepFn;
    /**
     * 如果为true 则 暂停下一个步骤
     */
    private   boolean           suspendNext;
    /**
     * 所属Task
     */
    protected final TaskWrapper       task;
    /**
     * Step 唯一标识
     */
    protected       String            key;

    public Step(TaskWrapper task, Function<I, R> fn, Function<R, Step> nextStepFn) {
        this.task = task;
        this.fn = fn;
        this.nextStepFn = nextStepFn;
    }

    /**
     * nextStep 默认指向 StopStep
     * @param task
     * @param fn
     */
    public Step(TaskWrapper task, Function<I, R> fn) {
        this(task, fn, r -> task.stopStep()); // 默认 指向 StopStep
    }
    public Step(TaskWrapper task) {
        this(task, null);
    }

    /**
     * 不用 输入参数的 Step
     * @param <R>
     */
    public static class NoInputStep<R> extends Step<Void, R> {
        public NoInputStep(TaskWrapper task, Supplier<R> fn, Function<R, Step> nextStepFn) {
            super(task, aVoid -> fn.get(), nextStepFn);
        }
        public NoInputStep(TaskWrapper task, Function<Void, R> fn) {
            super(task, fn);
        }
        public NoInputStep(TaskWrapper task) {
            super(task);
        }

        @Override
        public Step setParam(Void param) {
            throw new UnsupportedOperationException("NoInputStep not should have input param");
        }
    }

    /**
     * 不用 输出参数的 Step
     * @param <I>
     */
    public static class NoOutStep<I> extends Step<I, Void> {
        public NoOutStep(TaskWrapper task, Consumer<I> fn, Function<Void, Step> nextStepFn) {
            super(task, i -> { fn.accept(i); return null; }, nextStepFn);
        }
        public NoOutStep(TaskWrapper task, Consumer<I> fn) {
            super(task, i -> { fn.accept(i); return null; });
        }
        public NoOutStep(TaskWrapper task) {
            super(task);
        }
    }

    /**
     * 任务Task 停止Step,也是最后一个Step
     * @param <I>
     */
    protected static class StopStep<I> extends NoOutStep<I> {
        public StopStep(TaskWrapper task, Consumer<I> fn) {
            super(task, fn);
        }
        public StopStep(TaskWrapper task) { super(task); }

        @Override
        protected Step next() {
            return null;
        }
        @Override
        public Step setNextStepFn(Function<Void, Step> nextStepFn) {
            throw new UnsupportedOperationException("StopStep not need nextStepFn");
        }
    }


    /**
     * 封闭执行的 Task, 没有输入参数, 也没有返回值
     */
    public static class ClosureStep extends Step<Void, Void> {
        public ClosureStep(TaskWrapper task) { super(task); }
        public ClosureStep(TaskWrapper task, Consumer<Void> fn) {
            super(task, aVoid -> { fn.accept(null); return null; });
        }
        public ClosureStep(TaskWrapper task, Runnable fn, Supplier<Step> nextStepSupplier) {
            super(task, aVoid -> { fn.run(); return null; }, aVoid -> nextStepSupplier.get());
        }
    }

    /**
     * 可重复执行的Step
     * @param <I>
     * @param <R>
     */
    public static class RetryStep<I, R> extends Step<I, R> {
        /**
         * 重试时, 须手动设置为 false
         */
        private boolean complete;
        private Function<I, R> retryFn;
        private Function<RetryStep, I> paramFn;

        public RetryStep(TaskWrapper task) { super(task); }
        public RetryStep(TaskWrapper task, Function<I, R> fn, Function<I, R> retryFn, Function<R, Step> nextStepFn) {
            super(task, fn, nextStepFn);
            this.retryFn = retryFn;
        }

        @Override
        protected void process() {
            count.getAndIncrement();
            if (count.get() > 1) {
                if (retryFn == null) throw new NullPointerException(getKey() + ": retryFn is null");
                if (paramFn != null) param = paramFn.apply(this); //重新计算输入的参数
                result = retryFn.apply(param);
            } else {
                if (fn == null) throw new NullPointerException(getKey() + ": fn is null");
                result = fn.apply(param);
            }
            complete = true;
        }

        public RetryStep setRetryFn(Function<I, R> retryFn) {
            this.retryFn = retryFn;
            return this;
        }

        public Function<I, R> getRetryFn() {
            return retryFn;
        }

        @Override
        public boolean isComplete() {
            return complete;
        }

        public RetryStep setComplete(boolean complete) {
            this.complete = complete;
            return this;
        }

        /**
         * 重新执行
         */
        public final void reRun() {
            complete = false;
            //  task.currentStep(): 即被暂停的Step 有可能 不等于 this.
            // 比如: 一个Task 中有 Step1, Step2, 当 Step2调了suspendNext(), 但之后Step1 调了此方法即ReRun().
            // 那么, 当Step1 reRun 执行完后Step2 却是 suspendNext 状态. 所以 这里, 要先把 被暂停的Step 的 suspendNext 设置为false
            if (task.currentStep() != this) task.currentStep().suspendNext = false;
            task.currentStep(this);
            if (isWaitingNext()) continueNext();
            else task.trigger();
        }

        /**
         * 参数获取函数,
         * 因为RetryStep可能每次的参数不一样
         * @param paramFn
         * @return
         */
        public RetryStep paramFn(Function<RetryStep, I> paramFn) {
            this.paramFn = paramFn;
            param = paramFn.apply(this);
            return this;
        }

        @Override
        public Step setParam(I param) {
            throw new UnsupportedOperationException("Please use method paramFn");
        }
    }

    /**
     * Step执行
     * @return 下一个执行Step
     */
    public final Step run() {
        startTime = System.currentTimeMillis();
        process();
        // endTime
        return next();
    }

    /**
     * @return 如果 返回 null , 应该是 任务结束 或 应该是 任务暂停
     */
    protected Step next() {
        if (task.isShouldStop()) return task.stopStep();
        if (suspendNext) return null;
        return (nextStepFn == null ? null : nextStepFn.apply(result));
    }

    /**
     * 调用执行函数
     */
    protected void process() {
        if (fn == null) throw new NullPointerException(getKey() + ": fn is null");
        // 如果已执行过了 则直接返回
        if (count.incrementAndGet() > 1) {
            task.log.error("Step被重复执行, Task被强制Stop!!!");
            task.shouldStop();
            return;
        }
        result = fn.apply(param);
        if (this instanceof StopStep) {
            task.stopped.compareAndSet(false, true);
        }
    }

    /**
     * 挂起/暂停
     * 等待
     */
    public final void suspendNext() {
        suspendNext = true;
    }

    /**
     * 是否被 暂停 中
     * @return
     */
    public final boolean isWaitingNext() {
        return suspendNext;
    }

    /**
     * 恢复执行
     */
    public final void continueNext() {
        suspendNext = false;
        task.trigger();
    }

    public String getKey() {
        if (key == null) {
            key = task.getKey() + " Step: " + name + "";
        }
        return key;
    }

    public boolean isComplete() {
        return count.get() >= 1;
    }

    public Step setParam(I param) {
        this.param = param;
        return this;
    }

    public Step setNextStepFn(Function<R, Step> nextStepFn) {
        this.nextStepFn = nextStepFn;
        return this;
    }

    public Function<R, Step> getNextStepFn() {
        return nextStepFn;
    }

    public Step setFn(Function<I, R> fn) {
        this.fn = fn;
        return this;
    }


    public Function<I, R> getFn() {
        return fn;
    }


    public final R getResult() {
        return result;
    }

    public String getName() {
        return name;
    }

    public Step setName(String name) {
        this.name = name;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public Step setDescription(String description) {
        this.description = description;
        return this;
    }

    public TaskWrapper getTask() {
        return task;
    }
}
