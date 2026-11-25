package com.ticket.module.stock.dto;

/**
 * 索引查票时某个候选车次真正需要读取的车段范围。
 */
public class StockRangeQuery {

    private Long trainId;
    private Integer fromSegmentIndex;
    private Integer toSegmentIndex;

    public StockRangeQuery() {
    }

    public StockRangeQuery(Long trainId,
                           Integer fromSegmentIndex,
                           Integer toSegmentIndex) {
        this.trainId = trainId;
        this.fromSegmentIndex = fromSegmentIndex;
        this.toSegmentIndex = toSegmentIndex;
    }

    public Long getTrainId() {
        return trainId;
    }

    public void setTrainId(Long trainId) {
        this.trainId = trainId;
    }

    public Integer getFromSegmentIndex() {
        return fromSegmentIndex;
    }

    public void setFromSegmentIndex(Integer fromSegmentIndex) {
        this.fromSegmentIndex = fromSegmentIndex;
    }

    public Integer getToSegmentIndex() {
        return toSegmentIndex;
    }

    public void setToSegmentIndex(Integer toSegmentIndex) {
        this.toSegmentIndex = toSegmentIndex;
    }
}
