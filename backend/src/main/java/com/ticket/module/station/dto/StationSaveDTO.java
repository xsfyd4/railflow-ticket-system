package com.ticket.module.station.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增和修改车站共用的入参。
 *
 * <p>注意这里**没有 id**：新增时 id 由数据库生成，
 * 修改时 id 来自 URL 路径（/stations/{id}），都不该由前端塞进请求体。
 */
public class StationSaveDTO {

    /**
     * 车站三字码，比如 VNP（北京南）。
     * 规则：3~10 位，只能是数字和大写字母。
     */
    @NotBlank(message = "车站三字码不能为空")
    @Pattern(regexp = "^[A-Z0-9]{3,10}$", message = "三字码只能是 3~10 位数字或大写字母")
    private String stationCode;

    @NotBlank(message = "车站名称不能为空")
    @Size(max = 50, message = "车站名称最长 50 个字")
    private String stationName;

    @NotBlank(message = "所属城市不能为空")
    @Size(max = 50, message = "城市名最长 50 个字")
    private String city;

    @Size(max = 50, message = "省份名最长 50 个字")
    private String province;

    /** 全拼，用于搜索，比如 beijingnan */
    @Size(max = 50, message = "拼音最长 50 个字符")
    private String pinyin;

    /** 拼音首字母，比如 bjn */
    @Size(max = 20, message = "拼音首字母最长 20 个字符")
    private String pinyinAbbr;

    /** 1=启用，0=停用；不传时由 Service 默认设为 1 */
    private Integer status;

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
