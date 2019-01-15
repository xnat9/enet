package org.xnatural.enet.common.handler;

/**
 * Processor 的执行返回结果状态.
 * @author hubert
 *
 */
public class Status {
    /**
     * 按顺序连续执行下一个 Processor
     */
    public static final Status CONTINUE = new Status(null);
    /**
     * 跳出执行链.
     */
    public static final Status BREAK = new Status(null);
	/**
	 * a key stand for what. different environment should comment key is stand for what mean.
	 */
	Object key;
	public Status(Object key) {
		this.key = key;
	}

    /**
     * 跳跃执行到某个和key相等的Processor
     * @param key
     * @return
     */
	public static Status jump(Object key) {
	    return new Status(key);
    }
    public boolean isJump() {
	    return key != null;
    }
	
	@Override
	public boolean equals(Object obj) {
		if (obj == this) return true;
		// code identify this object.
		if (obj instanceof Status) {
			return (((Status) obj).key.equals(this.key));
		}
		return false;
	}

    @Override
    public String toString() {
	    if (this == CONTINUE) {
            return "CONTINUE";
        } else if (this == BREAK) {
	        return "BREAK";
        }
        return "JUMP key: " + key;
    }
}
