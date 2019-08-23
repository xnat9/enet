package cn.xnatural.enet.demo.dao.entity;

import cn.xnatural.enet.server.dao.hibernate.LongIdEntity;

import javax.persistence.Entity;

@Entity
public class Test extends LongIdEntity {

    private String name;
    private Integer age;


    public String getName() {
        return name;
    }


    public Test setName(String name) {
        this.name = name;
        return this;
    }


    public Integer getAge() {
        return age;
    }


    public Test setAge(Integer age) {
        this.age = age;
        return this;
    }
}
