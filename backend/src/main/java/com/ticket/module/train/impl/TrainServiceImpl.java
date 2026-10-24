package com.ticket.module.train.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.PageResult;
import com.ticket.common.result.ResultCode;
import com.ticket.module.station.entity.Station;
import com.ticket.module.station.mapper.StationMapper;
import com.ticket.module.train.TrainService;
import com.ticket.module.train.dto.TrainQueryDTO;
import com.ticket.module.train.dto.TrainSaveDTO;
import com.ticket.module.train.entity.Train;
import com.ticket.module.train.mapper.TrainMapper;
import com.ticket.module.train.vo.TrainVO;
import com.ticket.module.ticket.TicketCacheInvalidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;

import java.util.DuplicateFormatFlagsException;
import java.util.List;

/**
 * 车次管理的实现。
 *
 * <p>和车站相比，这里多了两处**新的业务规则**：
 * <ol>
 *   <li><b>始发站不能等于终到站</b>——这是数据合法性问题，
 *       一个车次不可能从北京南开到北京南</li>
 *   <li><b>删除要级联</b>——车次被删，它的停站、分段、票价、库存
 *       全是孤儿数据，必须在一个事务里一起删</li>
 * </ol>
 *
 * <p>删除前三步检查：
 * ① 车次存在吗？② 有订单引用吗？③ 都通过才级联删。
 */
@Service
public class TrainServiceImpl implements TrainService {

    private final TrainMapper trainMapper;
    private final StationMapper stationMapper;
    private final TicketCacheInvalidator cacheInvalidator;

    public TrainServiceImpl(TrainMapper trainMapper,
                            StationMapper stationMapper,
                            TicketCacheInvalidator cacheInvalidator) {
        this.trainMapper = trainMapper;
        this.stationMapper = stationMapper;
        this.cacheInvalidator = cacheInvalidator;
    }

    @Override
    public PageResult<TrainVO> page(TrainQueryDTO query) {
        // TODO 和 StationServiceImpl.page() 几乎一样
        // 提示：
        //   1. 兜底 page/size，并把修正后的值写回 query（XML 里用 #{query.size}）
        //   2. offset = (page - 1) * size
        //   3. countByQuery + findPage（findPage 已经带站名了，不用再转 VO）
        //   4. PageResult.of(total, page, size, records)
        int page=(query.getPage()==null||query.getPage()<1)?1: query.getPage();
        int size=(query.getSize()==null||query.getSize()<10)?10:query.getSize();
        if (size>100){
            size=100;
        }
        query.setSize(size);
        query.setPage(page);
        int offest=(page-1)*10;
        long total=trainMapper.countByQuery(query);
        List<TrainVO> records=trainMapper.findPage(query,offest);
        return  PageResult.of(total, page, size, records);

    }

    @Override
    public TrainVO detail(Long id) {
        // TODO 查不到抛 ResultCode.TRAIN_NOT_FOUND
        TrainVO trainVo=trainMapper.findById(id);
        if (trainVo==null){
            throw new BizException(ResultCode.TRAIN_NOT_FOUND);
        }else {
            return trainVo;
        }

    }

    @Override
    public Long create(TrainSaveDTO dto) {
        // TODO
        // 提示：
        //   1. 先校验 startStationId != endStationId，相等抛 BizException(400, "始发站和终到站不能相同")
        //   2. findByTrainNo 查重，重复抛 TRAIN_NO_EXISTS
        //   3. DTO → Train 实体（status 没传默认 1）
        //   4. insert，返回 train.getId()
        if (dto.getStartStationId().equals(dto.getEndStationId())){
            throw  new BizException(400,"始发站终点站不可相同");
        }
        Train unexisting=trainMapper.findByTrainNo(dto.getTrainNo());
        if (unexisting!=null){
            throw new BizException(ResultCode.TRAIN_NO_EXISTS);
        };
        Train train=new Train();
        train.setTrainNo(dto.getTrainNo());
        train.setTrainType(dto.getTrainType());
        train.setStartStationId(dto.getStartStationId());
        train.setEndStationId(dto.getEndStationId());
        train.setTotalMileage(dto.getTotalMileage());
        train.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        train.setRemark(dto.getRemark());
        try {
            trainMapper.insert(train);
        }catch(DuplicateKeyException e){
            throw new BizException(ResultCode.TRAIN_NO_EXISTS);
        }
        cacheInvalidator.invalidateAllAfterCommit();
        return train.getId();
    }

    @Override
    public void update(Long id, TrainSaveDTO dto) {
        // TODO
        // 提示：和车站的 update 一样——先查存在、再查重（排除自己）、改字段、update
        // 注意 status 没传时要保持原值
        if (dto.getStartStationId().equals(dto.getEndStationId())){
            throw  new BizException(400,"始发站终点站不可相同");
        }
        // 查一次，后面都用它（顺便拿到原来的 status）
        TrainVO existing = trainMapper.findById(id);
        if (existing == null) {
            throw new BizException(ResultCode.TRAIN_NOT_FOUND);
        }
        if (!existing.getTrainNo().equals(dto.getTrainNo())
                && trainMapper.findByTrainNo(dto.getTrainNo()) != null) {
            throw new BizException(ResultCode.TRAIN_NO_EXISTS);
        }
        Train train=new Train();
        train.setId(id);
        train.setTrainNo(dto.getTrainNo());
        train.setTrainType(dto.getTrainType());
        train.setStartStationId(dto.getStartStationId());
        train.setEndStationId(dto.getEndStationId());
        train.setTotalMileage(dto.getTotalMileage());
        // status 没传时保持原值，否则会被写成 null（NOT NULL 列，直接报错）
        train.setStatus(dto.getStatus() == null ? existing.getStatus() : dto.getStatus());
        train.setRemark(dto.getRemark());
        try {
            trainMapper.update(train);
        }catch(DuplicateKeyException e){
            throw new BizException(ResultCode.TRAIN_NO_EXISTS);
        }
        cacheInvalidator.invalidateAllAfterCommit();

    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {

        // 1. 车次必须存在
        TrainVO train = trainMapper.findById(id);
        if (train == null) {
            throw new BizException(ResultCode.TRAIN_NOT_FOUND);
        }

        // 2. 有订单引用就不能删
        long orderCount = trainMapper.countOrders(id);
        if (orderCount > 0) {
            throw new BizException(ResultCode.TRAIN_IN_USE);     // 3005
        }

        // 3. 级联删除：从"孙子"往"父母"删
        trainMapper.deleteStocksByTrainId(id);      // 库存
        trainMapper.deletePricesByTrainId(id);      // 票价
        trainMapper.deleteSegmentsByTrainId(id);    // 分段
        trainMapper.deleteStopsByTrainId(id);       // 停站
        trainMapper.deleteById(id);                 // 车次本身
        cacheInvalidator.invalidateAllAfterCommit();
    }
}
