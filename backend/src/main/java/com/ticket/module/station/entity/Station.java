package com.ticket.module.station.entity;

import java.time.LocalDateTime;

/**
 * 对应数据库 station 表。
 *
 * <p>字段和表列一一对应：
 * station_code → stationCode
 * station_name → stationName
 * pinyin_abbr  → pinyinAbbr
 * 这个转换靠 application.yml 里的 map-underscore-to-camel-case: true 自动完成。
 *
 * <p>实体只负责"装数据"，不带任何业务逻辑。
 * 它也不直接返回给前端（用 StationVO）。
 */
public class Station {

    private Long id;
    private String stationCode;
    private String stationName;

    public Station(Long id, String stationCode, String stationName, String city, String province, String pinyin, String pinyinAbbr, Integer status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.stationCode = stationCode;
        this.stationName = stationName;
        this.city = city;
        this.province = province;
        this.pinyin = pinyin;
        this.pinyinAbbr = pinyinAbbr;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    private String city;
    private String province;
    private String pinyin;
    private String pinyinAbbr;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Station() {

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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}