package org.xnatural.enet.server.dao.hibernate;

import java.util.Collection;

public class Page<E extends IEntity> {
    private Integer       pageIndex;
    private Integer       pageSize;
    private Integer       totalPage;
    private Collection<E> list;


    public Page(Collection<E> list, Integer pageIndex, Integer pageSize, Integer totalPage) {
        this.pageIndex = pageIndex;
        this.pageSize = pageSize;
        this.totalPage = totalPage;
        this.list = list;
    }


    public Page() { }


    public Integer getPageIndex() {
        return pageIndex;
    }


    public Page<E> setPageIndex(Integer pageIndex) {
        this.pageIndex = pageIndex;
        return this;
    }


    public Integer getPageSize() {
        return pageSize;
    }


    public Page<E> setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
        return this;
    }


    public Integer getTotalPage() {
        return totalPage;
    }


    public Page<E> setTotalPage(Integer totalPage) {
        this.totalPage = totalPage;
        return this;
    }


    public Collection<E> getList() {
        return list;
    }


    public Page<E> setList(Collection<E> list) {
        this.list = list;
        return this;
    }
}
