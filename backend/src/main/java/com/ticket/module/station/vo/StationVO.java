package com.ticket.module.station.vo;

import com.ticket.module.auth.entity.SysAdmin;
import com.ticket.module.auth.vo.AdminVO;
import com.ticket.module.station.entity.Station;

/**
 * 返回给前端的车站信息。
 *
 * <p>比实体少了 createdAt / updatedAt，因为前端表格用不上。
 * 这就是实体和 VO 分开的意义：**返回什么字段，由接口决定，不由表结构决定**。
 */
public class StationVO {

    private Long id;
    private String stationCode;
    private String stationName;
    private String city;
    private String province;
    private String pinyin;
    private String pinyinAbbr;
    private Integer status;

    /**
     * 实体 → VO。
     *
     * <p>提示：对照 AdminVO.from() 写，注意实体为 null 时返回 null。
     */
    public static StationVO from(Station station) {
        if (station == null) {
            return null;
        }
        StationVO vo = new StationVO();
        vo.setId(station.getId());
        vo.setStationCode(station.getStationCode());
        vo.setStationName(station.getStationName());
        vo.setCity(station.getCity());
        vo.setProvince(station.getProvince());
        vo.setPinyin(station.getPinyin());
        vo.setPinyinAbbr(station.getPinyinAbbr());
        vo.setStatus(station.getStatus());
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStationCode() {
        return stationCode;
    }

    public void setStationCode(String stationCode) {
        this.stationCode = stationCode;
    }

    public String getStationName() {
        return stationName;
    }

    public void setStationName(String stationName) {
        this.stationName = stationName;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getPinyin() {
        return pinyin;
    }

    public void setPinyin(String pinyin) {
        this.pinyin = pinyin;
    }

    public String getPinyinAbbr() {
        return pinyinAbbr;
    }

    public void setPinyinAbbr(String pinyinAbbr) {
        this.pinyinAbbr = pinyinAbbr;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
