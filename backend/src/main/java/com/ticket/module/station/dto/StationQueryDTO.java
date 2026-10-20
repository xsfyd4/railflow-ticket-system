package com.ticket.module.station.dto;

/**
 * 车站列表的查询条件。
 *
 * <p>Spring 会自动把 URL 上的查询参数绑到这些字段上，比如：
 * {@code ?keyword=北京&city=北京&status=1&page=1&size=10}
 * 所以 Controller 里不需要写五个 @RequestParam。
 *
 * <p>page 和 size 给了默认值 1 和 10，前端不传时就用默认值。
 */
public class StationQueryDTO {

    /** 模糊匹配：车站名 / 全拼 / 拼音首字母 */
    private String keyword;

    /** 城市，精确匹配 */
    private String city;

    /** 状态：1=启用，0=停用；不传表示不过滤 */
    private Integer status;

    /** 第几页，从 1 开始 */
    private Integer page = 1;

    /** 每页几条 */
    private Integer size = 10;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getSize() {
        return size;
    }

    public void setSize(Integer size) {
        this.size = size;
    }
}
