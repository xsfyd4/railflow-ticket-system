package com.ticket.module.order.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单详情中的一位乘车人及对应的票。 */
public class OrderItemVO {

    private Long id;
    private String passengerName;
    private String idCardNo;
    private Integer seatType;
    private BigDecimal price;
    private String coachNo;
    private String seatNo;
    private Integer status;
    private LocalDateTime refundedAt;

    public static String maskIdCard(String idCardNo) {
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

    public String getPassengerName() {
        return passengerName;
    }

    public void setPassengerName(String passengerName) {
        this.passengerName = passengerName;
    }

    public String getIdCardNo() {
        return idCardNo;
    }

    public void setIdCardNo(String idCardNo) {
        this.idCardNo = idCardNo;
    }

    public Integer getSeatType() {
        return seatType;
    }

    public void setSeatType(Integer seatType) {
        this.seatType = seatType;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getCoachNo() {
        return coachNo;
    }

    public void setCoachNo(String coachNo) {
        this.coachNo = coachNo;
    }

    public String getSeatNo() {
        return seatNo;
    }

    public void setSeatNo(String seatNo) {
        this.seatNo = seatNo;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getRefundedAt() {
        return refundedAt;
    }

    public void setRefundedAt(LocalDateTime refundedAt) {
        this.refundedAt = refundedAt;
    }
}
