package org.xnatural.enet;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

@Entity
public class TestEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private Integer age;



    public Long getId() {
        return id;
    }


    public TestEntity setId(Long id) {
        this.id = id;
        return this;
    }
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
