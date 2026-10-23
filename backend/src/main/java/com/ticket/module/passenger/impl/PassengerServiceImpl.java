package com.ticket.module.passenger.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.ResultCode;
import com.ticket.module.passenger.PassengerService;
import com.ticket.module.passenger.dto.PassengerSaveDTO;
import com.ticket.module.passenger.entity.Passenger;
import com.ticket.module.passenger.mapper.PassengerMapper;
import com.ticket.module.passenger.vo.PassengerVO;
import com.ticket.security.LoginUser;
import com.ticket.security.LoginUserContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class PassengerServiceImpl implements PassengerService {

    /** 防止脚本批量创建几十万条，真实产品一般还会做实名次数限制。 */
    private static final int MAX_PASSENGERS_PER_USER = 20;

    private final PassengerMapper passengerMapper;

    public PassengerServiceImpl(PassengerMapper passengerMapper) {
        this.passengerMapper = passengerMapper;
    }

    @Override
    public List<PassengerVO> list() {
        return passengerMapper.findByUserId(currentUserId()).stream()
                .map(PassengerVO::from)
                .toList();
    }

    @Override
    public Long create(PassengerSaveDTO dto) {
        Long userId = currentUserId();
        if (passengerMapper.countByUserId(userId) >= MAX_PASSENGERS_PER_USER) {
            throw new BizException(ResultCode.PASSENGER_LIMIT_EXCEEDED);
        }

        String idCardNo = normalizeIdCard(dto.getIdCardNo());
        if (passengerMapper.findByUserIdAndIdCardNo(userId, idCardNo) != null) {
            throw new BizException(ResultCode.PASSENGER_ID_CARD_EXISTS);
        }

        Passenger passenger = new Passenger();
        passenger.setUserId(userId);
        copyFields(passenger, dto, 1);

        try {
            passengerMapper.insert(passenger);
        } catch (DuplicateKeyException e) {
            // 两个人同时提交同一证件时，唯一索引是最后一道防线。
            throw new BizException(ResultCode.PASSENGER_ID_CARD_EXISTS);
        }
        return passenger.getId();
    }

    @Override
    public void update(Long id, PassengerSaveDTO dto) {
        Long userId = currentUserId();
        Passenger passenger = passengerMapper.findByIdAndUserId(id, userId);
        if (passenger == null) {
            throw new BizException(ResultCode.PASSENGER_NOT_FOUND);
        }

        String idCardNo = normalizeIdCard(dto.getIdCardNo());
        Passenger sameCard =
                passengerMapper.findByUserIdAndIdCardNo(userId, idCardNo);
        if (sameCard != null && !sameCard.getId().equals(id)) {
            throw new BizException(ResultCode.PASSENGER_ID_CARD_EXISTS);
        }

        copyFields(passenger, dto, passenger.getPassengerType());
        try {
            passengerMapper.update(passenger);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.PASSENGER_ID_CARD_EXISTS);
        }
    }

    @Override
    public void delete(Long id) {
        int affected = passengerMapper.deleteByIdAndUserId(id, currentUserId());
        if (affected == 0) {
            // 不区分“不存在”和“属于别人”，避免泄露别人有哪些乘车人。
            throw new BizException(ResultCode.PASSENGER_NOT_FOUND);
        }
    }

    private void copyFields(Passenger passenger,
                            PassengerSaveDTO dto,
                            Integer defaultType) {
        passenger.setName(dto.getName().trim());
        passenger.setIdCardNo(normalizeIdCard(dto.getIdCardNo()));
        passenger.setPassengerType(
                dto.getPassengerType() == null ? defaultType : dto.getPassengerType());
        passenger.setPhone(normalizeOptional(dto.getPhone()));
    }

    private String normalizeIdCard(String idCardNo) {
        return idCardNo.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private Long currentUserId() {
        LoginUser user = LoginUserContext.get();
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return user.getId();
    }
}
