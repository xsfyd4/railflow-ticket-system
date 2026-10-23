package com.ticket.module.passenger.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增和修改乘车人共用。
 *
 * <p>user_id 不能由前端传：它必须来自登录 token 对应的 LoginUserContext。
 * 否则用户 A 可以伪造 user_id，往用户 B 的账户里加乘车人。
 */
public class PassengerSaveDTO {

    @NotBlank(message = "乘车人姓名不能为空")
    @Size(max = 50, message = "乘车人姓名最长 50 个字")
    private String name;

    @NotBlank(message = "证件号不能为空")
    @Pattern(
            regexp = "^\\d{15}$|^\\d{17}[\\dXx]$",
            message = "证件号应为 15 位数字或 18 位身份证号"
    )
    private String idCardNo;

    /** 1=成人，2=学生，3=儿童；新增不传时默认成人 */
    @Min(value = 1, message = "乘车人类型只能是 1、2、3")
    @Max(value = 3, message = "乘车人类型只能是 1、2、3")
    private Integer passengerType;

    /** 空字符串会在 Service 中统一转成 null，避免把 "" 写进数据库。 */
    @Pattern(regexp = "^$|^1\\d{10}$", message = "手机号应为 11 位数字")
    private String phone;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIdCardNo() {
        return idCardNo;
    }

    public void setIdCardNo(String idCardNo) {
        this.idCardNo = idCardNo;
    }

    public Integer getPassengerType() {
        return passengerType;
    }

    public void setPassengerType(Integer passengerType) {
        this.passengerType = passengerType;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
