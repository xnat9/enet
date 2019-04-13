package cn.xnatural.enet.common.task;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 步骤{@link Step}链
 * @author xiangxb, 2018-10-20
 */
public class StepChain {
    /**
     * 保存当前正在执行的Step
     */
    private AtomicReference<Step> currentStep = new AtomicReference<>();


    public final void run() {

    }



    /**
     * @return 当前正在执行的步骤
     */
    public final Step currentStep() {
        return currentStep.get();
    }


    /**
     * 改变当执行的步骤
     * @param step 之前的执行步骤
     * @return
     */
    public final Step currentStep(Step step) {
        return currentStep.getAndSet(step);
    }
}
