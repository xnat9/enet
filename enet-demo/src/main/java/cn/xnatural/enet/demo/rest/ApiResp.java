package cn.xnatural.enet.demo.rest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * rest api 请求响应的基本数据结构
 *
 * @author hubert
 */
public class ApiResp<T> extends BasePojo {

    private boolean success;
    private T       data;
    private String  errorMsg;
    private String  errorId;


    public static <T> ApiResp<T> ok() {
        return new ApiResp().setSuccess(true);
    }


    public static <T> ApiResp ok(T data) {
        return new ApiResp().setSuccess(true).setData(data);
    }


    public static ApiResp<LinkedHashMap<String, Object>> ok(String attrName, Object attrValue) {
        ApiResp<LinkedHashMap<String, Object>> ret = ok(new LinkedHashMap<>(5));
        ret.getData().put(attrName, attrValue);
        return ret;
    }


    public static ApiResp fail(String errorMsg) {
        return new ApiResp().setSuccess(false).setErrorMsg(errorMsg);
    }


    /**
     * 一般用法 ApiResp.ok().attr("aaa", 111).attr("bbb", 222)
     *
     * @param attrName
     * @param attrValue
     * @return
     */
    public ApiResp<LinkedHashMap<String, Object>> attr(String attrName, Object attrValue) {
        if (getData() == null) {
            setData((T) new LinkedHashMap<String, Object>(5));
        }
        if (getData() != null && !(getData() instanceof Map)) {
            throw new IllegalArgumentException(getClass().getSimpleName() + "的data类型必须为Map类型");
        }
        ((Map) getData()).put(attrName, attrValue);
        return (ApiResp<LinkedHashMap<String, Object>>) this;
    }


    public boolean isSuccess() {
        return success;
    }


    public T getData() {
        return data;
    }


    public ApiResp<T> setSuccess(boolean pSuccess) {
        success = pSuccess;
        return this;
    }


    public ApiResp<T> setData(T data) {
        this.data = data;
        return this;
    }


    public String getErrorMsg() {
        return errorMsg;
    }


    public ApiResp<T> setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
        return this;
    }


    public String getErrorId() {
        return errorId;
    }


    public ApiResp<T> setErrorId(String errorId) {
        this.errorId = errorId;
        return this;
    }
}
