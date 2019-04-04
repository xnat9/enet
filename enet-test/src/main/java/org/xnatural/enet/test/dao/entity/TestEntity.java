package org.xnatural.enet.test.dao.entity;

import org.xnatural.enet.server.dao.hibernate.LongIdEntity;

import javax.persistence.Entity;

@Entity
public class TestEntity extends LongIdEntity {

    private String name;
    private Integer age;


    public String getName() {
        return name;
    }


    public TestEntity setName(String name) {
        this.name = name;
        return this;
    }


    public Integer getAge() {
        return age;
    }


    public TestEntity setAge(Integer age) {
        this.age = age;
        return this;
    }
}
