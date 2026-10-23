package com.ticket.module.passenger.vo;

import com.ticket.module.passenger.entity.Passenger;

import java.time.LocalDateTime;

/** 返回给用户端的乘车人信息，证件号已经脱敏。 */
public class PassengerVO {

    private Long id;
    private String name;
    private String idCardNo;
    private Integer passengerType;
    private String phone;
    private LocalDateTime createdAt;

    public static PassengerVO from(Passenger passenger) {
        PassengerVO vo = new PassengerVO();
        vo.setId(passenger.getId());
        vo.setName(passenger.getName());
        vo.setIdCardNo(maskIdCard(passenger.getIdCardNo()));
        vo.setPassengerType(passenger.getPassengerType());
        vo.setPhone(passenger.getPhone());
        vo.setCreatedAt(passenger.getCreatedAt());
        return vo;
    }

    /**
     * 110101199001011234 → 110***********1234
     *
     * <p>列表只需要让用户认出是哪张证件，不需要把完整证件号发给浏览器。
     */
    static String maskIdCard(String idCardNo) {
        if (idCardNo == null || idCardNo.length() <= 8) {
            return "****";
        }
        int prefixLength = 3;
        int suffixLength = 4;
        int hiddenLength = idCardNo.length() - prefixLength - suffixLength;
        return idCardNo.substring(0, prefixLength)
                + "*".repeat(hiddenLength)
                + idCardNo.substring(idCardNo.length() - suffixLength);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
