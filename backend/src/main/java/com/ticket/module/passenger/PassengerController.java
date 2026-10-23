package com.ticket.module.passenger;

import com.ticket.common.result.R;
import com.ticket.module.passenger.dto.PassengerSaveDTO;
import com.ticket.module.passenger.vo.PassengerVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/user/passengers")
public class PassengerController {

    private final PassengerService passengerService;

    public PassengerController(PassengerService passengerService) {
        this.passengerService = passengerService;
    }

    @GetMapping
    public R<List<PassengerVO>> list() {
        return R.ok(passengerService.list());
    }

    @PostMapping
    public R<Long> create(@RequestBody @Valid PassengerSaveDTO dto) {
        return R.ok("新增成功", passengerService.create(dto));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id,
                          @RequestBody @Valid PassengerSaveDTO dto) {
        passengerService.update(id, dto);
        return R.ok("修改成功", null);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        passengerService.delete(id);
        return R.ok("删除成功", null);
    }
}
