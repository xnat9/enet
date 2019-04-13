package cn.xnatural.enet.test.rest;

import org.apache.commons.lang3.StringUtils;

import java.io.InputStream;

/**
 * Created by xxb on 2017/8/17.
 */
public class FileData extends BasePojo {
    /**
     * 扩展名
     */
    private           String      extension;
    /**
     * 原始文件名(包含扩展名)
     */
    private           String      originName;
    /**
     * 系统生成的唯一名(不包含扩展名)
     */
    private           String      generatedName;
    /**
     * 文件流
     */
    private transient InputStream inputStream;
    /**
     * 大小
     */
    private           Long        size;


    @Override
    public String toString() {
        return "[originName: " + getOriginName() + ", resultName: " + getResultName() + ", size: " + size + "]";
    }


    /**
     * 最终保存的文件名
     *
     * @return
     */
    public String getResultName() {
        return (StringUtils.isEmpty(getExtension()) ? getGeneratedName() : (getGeneratedName() + "." + getExtension()));
    }


    public Long getSize() {
        return size;
    }


    public FileData setSize(Long size) {
        this.size = size;
        return this;
    }


    public String getExtension() {
        return extension;
    }


    public FileData setExtension(String extension) {
        this.extension = extension;
        return this;
    }


    public String getOriginName() {
        return originName;
    }


    public FileData setOriginName(String originName) {
        this.originName = originName;
        return this;
    }


    public InputStream getInputStream() {
        return inputStream;
    }


    public FileData setInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
        return this;
    }


    public String getGeneratedName() {
        return generatedName;
    }


    public FileData setGeneratedName(String generatedName) {
        this.generatedName = generatedName;
        return this;
    }
}
