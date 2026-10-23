package com.ticket.module.passenger.impl;

import com.ticket.common.exception.BizException;
import com.ticket.module.passenger.PassengerService;
import com.ticket.module.passenger.dto.PassengerSaveDTO;
import com.ticket.module.passenger.mapper.PassengerMapper;
import com.ticket.module.passenger.vo.PassengerVO;
import com.ticket.security.LoginUser;
import com.ticket.security.LoginUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class PassengerServiceImplIntegrationTest {

    @Autowired
    private PassengerService passengerService;

    @Autowired
    private PassengerMapper passengerMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                INSERT INTO sys_user (username, password, status)
                VALUES ('__passenger_test_user__', 'not-used', 1)
                """);
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = '__passenger_test_user__'",
                Long.class);
        LoginUserContext.set(new LoginUser(userId, "__passenger_test_user__", "USER"));
    }

    @AfterEach
    void tearDown() {
        LoginUserContext.clear();
    }

    @Test
    void shouldCreateListUpdateAndDeleteOwnPassenger() {
        Long passengerId = passengerService.create(dto("张三", "11010119900101123x"));

        List<PassengerVO> list = passengerService.list();
        assertEquals(1, list.size());
        assertEquals("110***********123X", list.get(0).getIdCardNo(),
                "列表返回的证件号必须脱敏，且末位小写 x 应转成大写 X");

        passengerService.update(passengerId, dto("张三丰", "110101199001011234"));
        assertEquals("张三丰",
                passengerMapper.findByIdAndUserId(passengerId, userId).getName());
        assertEquals("110101199001011234",
                passengerMapper.findByIdAndUserId(passengerId, userId).getIdCardNo());

        passengerService.delete(passengerId);
        assertNull(passengerMapper.findByIdAndUserId(passengerId, userId));
    }

    @Test
    void shouldRejectDuplicateCardWithinSameAccount() {
        passengerService.create(dto("张三", "110101199001011234"));

        BizException error = assertThrows(BizException.class,
                () -> passengerService.create(dto("李四", "110101199001011234")));
        assertEquals(4002, error.getCode());
    }

    @Test
    void shouldNotAllowAnotherUserToDeletePassenger() {
        Long passengerId = passengerService.create(dto("张三", "110101199001011234"));

        jdbcTemplate.update("""
                INSERT INTO sys_user (username, password, status)
                VALUES ('__passenger_other_user__', 'not-used', 1)
                """);
        Long otherUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = '__passenger_other_user__'",
                Long.class);
        LoginUserContext.set(new LoginUser(otherUserId, "__passenger_other_user__", "USER"));

        BizException error = assertThrows(BizException.class,
                () -> passengerService.delete(passengerId));
        assertEquals(4001, error.getCode());
        assertTrue(passengerMapper.findByIdAndUserId(passengerId, userId) != null,
                "其他账号不能删掉原账号的乘车人");
    }

    private PassengerSaveDTO dto(String name, String idCardNo) {
        PassengerSaveDTO dto = new PassengerSaveDTO();
        dto.setName(name);
        dto.setIdCardNo(idCardNo);
        dto.setPassengerType(1);
        dto.setPhone("13800000000");
        return dto;
    }
}
