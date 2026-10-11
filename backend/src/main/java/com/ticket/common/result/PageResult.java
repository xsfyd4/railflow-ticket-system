package com.ticket.common.result;

import java.util.List;

/**
 * 统一分页返回体，放在 R 的 data 字段里面。
 *
 * <p>字段：total 总条数、pages 总页数、current 当前页、size 每页条数、records 当前页数据。
 *
 * <p>字段名故意和 MyBatis-Plus 的分页对象保持一致，
 * 以后从 IPage 转过来时不用改字段名，前端也不用改。
 */
public class PageResult<T> {

    private long total;
    private long pages;
    private long current;
    private long size;
    private List<T> records;

    public PageResult() {
    }

    public PageResult(long total, long pages, long current, long size, List<T> records) {
        this.total = total;
        this.pages = pages;
        this.current = current;
        this.size = size;
        this.records = records;
    }

    /** 根据总条数、当前页、每页条数自动算好 pages，免得每个地方都自己算一遍 */
    public static <T> PageResult<T> of(long total, long current, long size, List<T> records) {
        long pages = (size <= 0) ? 0 : (total + size - 1) / size;
        return new PageResult<>(total, pages, current, size, records);
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getPages() {
        return pages;
    }

    public void setPages(long pages) {
        this.pages = pages;
    }

    public long getCurrent() {
        return current;
    }

    public void setCurrent(long current) {
        this.current = current;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public List<T> getRecords() {
        return records;
    }

    public void setRecords(List<T> records) {
        this.records = records;
    }
}
