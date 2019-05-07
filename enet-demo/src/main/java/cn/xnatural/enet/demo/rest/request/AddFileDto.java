package cn.xnatural.enet.demo.rest.request;

import cn.xnatural.enet.demo.rest.BasePojo;
import cn.xnatural.enet.demo.rest.FileData;

public class AddFileDto extends BasePojo {
    private String   name;
    private Integer  age;
    private FileData headportrait;


    public String getName() {
        return name;
    }


    public void setName(String name) {
        this.name = name;
    }


    public Integer getAge() {
        return age;
    }


    public void setAge(Integer age) {
        this.age = age;
    }


    public FileData getHeadportrait() {
        return headportrait;
    }


    public void setHeadportrait(FileData headportrait) {
        this.headportrait = headportrait;
    }
}
