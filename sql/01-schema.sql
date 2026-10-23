-- ============================================================================
--  火车票购票系统 · 数据库结构
--  数据库：MySQL 8.0+
--  说明：这是「阶段 1」就要落地的完整表结构。前期用不到的表可以先不建，
--        但建议一次建好，方便理解整体关系（见 docs/02-数据库设计.md 第十二节）。
--  约定：表名、字段名一律小写下划线；金额用 DECIMAL；时间用 DATETIME。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `train_ticket`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;

USE `train_ticket`;


-- ============================================================================
--  一、账号与乘车人
-- ============================================================================

-- 1. 用户端账号
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`   VARCHAR(50)  NOT NULL                COMMENT '登录账号（唯一）',
  `password`   VARCHAR(100) NOT NULL                COMMENT 'BCrypt 加密后的密码，绝不存明文',
  `phone`      VARCHAR(20)           DEFAULT NULL   COMMENT '手机号',
  `nickname`   VARCHAR(50)           DEFAULT NULL   COMMENT '昵称',
  `avatar`     VARCHAR(255)          DEFAULT NULL   COMMENT '头像 URL',
  `status`     TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1=正常，0=禁用',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                     ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`),
  UNIQUE KEY `uk_user_phone` (`phone`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户端账号表';


-- 2. 管理端（服务端）账号
--    为什么不和 sys_user 合成一张表？前期两端登录逻辑、权限、字段需求都不一样，
--    分开写更直观；等以后想做统一账号体系再合并（加 user_type 字段）。
DROP TABLE IF EXISTS `sys_admin`;
CREATE TABLE `sys_admin` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`      VARCHAR(50)  NOT NULL                COMMENT '登录账号（唯一）',
  `password`      VARCHAR(100) NOT NULL                COMMENT 'BCrypt 加密后的密码',
  `real_name`     VARCHAR(50)           DEFAULT NULL   COMMENT '姓名',
  `role`          VARCHAR(20)  NOT NULL DEFAULT 'ADMIN' COMMENT '角色：SUPER=超管，ADMIN=普通管理员',
  `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '状态：1=正常，0=禁用',
  `last_login_at` DATETIME              DEFAULT NULL   COMMENT '最后登录时间',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                        ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '管理端账号表';


-- 3. 乘车人（常用联系人）
--    一个账号可以给多个人买票，所以「谁买的」和「票是谁的」要分开存。
DROP TABLE IF EXISTS `passenger`;
CREATE TABLE `passenger` (
  `id`             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`        BIGINT      NOT NULL                COMMENT '所属账号 sys_user.id',
  `name`           VARCHAR(50) NOT NULL                COMMENT '乘车人姓名',
  `id_card_no`     VARCHAR(64) NOT NULL                COMMENT '证件号（真实项目必须加密存储，本项目可只存后 4 位+密文）',
  `passenger_type` TINYINT     NOT NULL DEFAULT 1      COMMENT '票种：1=成人，2=学生，3=儿童',
  `phone`          VARCHAR(20)          DEFAULT NULL   COMMENT '联系电话',
  `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                        ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_passenger_user_card` (`user_id`, `id_card_no`),
  KEY `idx_passenger_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '乘车人表';


-- ============================================================================
--  二、基础数据：车站 / 车次 / 停站 / 段 / 价格
-- ============================================================================

-- 4. 车站
DROP TABLE IF EXISTS `station`;
CREATE TABLE `station` (
  `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `station_code` VARCHAR(10) NOT NULL                COMMENT '车站三字码，如 BJP（唯一）',
  `station_name` VARCHAR(50) NOT NULL                COMMENT '车站名，如 北京南',
  `city`         VARCHAR(50) NOT NULL                COMMENT '所属城市，如 北京',
  `province`     VARCHAR(50)          DEFAULT NULL   COMMENT '所属省份',
  `pinyin`       VARCHAR(50)          DEFAULT NULL   COMMENT '全拼，用于搜索',
  `pinyin_abbr`  VARCHAR(20)          DEFAULT NULL   COMMENT '拼音首字母，如 bjn',
  `status`       TINYINT     NOT NULL DEFAULT 1      COMMENT '状态：1=启用，0=停用',
  `created_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                      ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_station_code` (`station_code`),
  KEY `idx_station_name` (`station_name`),
  KEY `idx_station_city` (`city`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '车站表';


-- 5. 车次（只存「一趟车是什么」，不含日期）
--    注意：日期不在这张表里，同一个车次每天都有，日期体现在 ticket_stock.travel_date。
DROP TABLE IF EXISTS `train`;
CREATE TABLE `train` (
  `id`               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `train_no`         VARCHAR(20) NOT NULL                COMMENT '车次号，如 G1001（唯一）',
  `train_type`       CHAR(1)     NOT NULL                COMMENT '类型：G=高铁，D=动车，K=快速，T=特快，Z=直达',
  `start_station_id` BIGINT      NOT NULL                COMMENT '始发站 station.id（冗余，方便列表展示）',
  `end_station_id`   BIGINT      NOT NULL                COMMENT '终到站 station.id（冗余，可由停站表推导）',
  `total_mileage`    DECIMAL(8,1)         DEFAULT NULL   COMMENT '全程里程（公里）',
  `status`           TINYINT     NOT NULL DEFAULT 1      COMMENT '状态：1=启用，0=停运',
  `remark`           VARCHAR(255)         DEFAULT NULL   COMMENT '备注',
  `created_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_train_no` (`train_no`),
  KEY `idx_train_start` (`start_station_id`),
  KEY `idx_train_end` (`end_station_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '车次表';


-- 6. 停站表（一趟车沿途停靠哪些站、什么顺序）
--    这是整个分段模型的「源头」：有了停站顺序，才能切出段。
DROP TABLE IF EXISTS `train_stop`;
CREATE TABLE `train_stop` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `train_id`       BIGINT       NOT NULL                COMMENT '所属车次 train.id',
  `stop_order`     INT          NOT NULL                COMMENT '第几站，从 1 开始（1=始发站）',
  `station_id`     BIGINT       NOT NULL                COMMENT '车站 station.id',
  `arrival_time`   TIME                  DEFAULT NULL   COMMENT '到达时刻（始发站为 NULL）',
  `departure_time` TIME                  DEFAULT NULL   COMMENT '发车时刻（终到站为 NULL）',
  `day_offset`     INT          NOT NULL DEFAULT 0      COMMENT '相对发车日的天数：0=当天，1=次日（跨天车次用）',
  `mileage`        DECIMAL(8,1) NOT NULL DEFAULT 0      COMMENT '从始发站算起的累计里程（公里）',
  `is_start`       TINYINT      NOT NULL DEFAULT 0      COMMENT '是否始发站：1=是',
  `is_end`         TINYINT      NOT NULL DEFAULT 0      COMMENT '是否终到站：1=是',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stop_order` (`train_id`, `stop_order`),
  -- 保证「一个车次里同一个车站只出现一次」，这样按车站反查停站序号时结果唯一。
  -- 若以后要支持环线车次（同一站停两次），删掉这个唯一索引即可。
  UNIQUE KEY `uk_stop_train_station` (`train_id`, `station_id`),
  KEY `idx_stop_station` (`station_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '车次停站表（按 stop_order 排列）';


-- 7. 段定义（相邻两站 = 一段；由停站表推导并落库，供查询直接用）
--    第 k 段 = 第 k 站 → 第 k+1 站。若一趟车停 5 站，则有 4 段。
DROP TABLE IF EXISTS `train_segment`;
CREATE TABLE `train_segment` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `train_id`        BIGINT       NOT NULL                COMMENT '所属车次 train.id',
  `segment_index`   INT          NOT NULL                COMMENT '第几段，从 1 开始',
  `from_stop_order` INT          NOT NULL                COMMENT '起点站序号（= segment_index）',
  `to_stop_order`   INT          NOT NULL                COMMENT '终点站序号（= segment_index + 1）',
  `from_station_id` BIGINT       NOT NULL                COMMENT '起点站 station.id',
  `to_station_id`   BIGINT       NOT NULL                COMMENT '终点站 station.id',
  `mileage`         DECIMAL(8,1) NOT NULL DEFAULT 0      COMMENT '本段里程（公里）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_segment_index` (`train_id`, `segment_index`),
  KEY `idx_segment_station` (`from_station_id`, `to_station_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '车次分段表（相邻两站为一段）';


-- 8. 段价格（每段 × 每席别 的价格；总价 = 覆盖的段价格之和）
DROP TABLE IF EXISTS `train_segment_price`;
CREATE TABLE `train_segment_price` (
  `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `train_id`      BIGINT        NOT NULL                COMMENT '所属车次',
  `segment_index` INT           NOT NULL                COMMENT '第几段',
  `seat_type`     TINYINT       NOT NULL                COMMENT '席别：1=商务座，2=一等座，3=二等座',
  `price`         DECIMAL(10,2) NOT NULL                COMMENT '本段本席别票价（元）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_price` (`train_id`, `segment_index`, `seat_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '车次分段票价表';


-- ============================================================================
--  三、库存（整个项目的心脏）
-- ============================================================================

-- 9. 分段库存：日期 + 车次 + 第几段 + 席别 → 余票数
--    用户说的「日期车次前一站后一站剩余票数」就是这张表。
--    查询「A 到 B 有没有票」= 取 A、B 之间所有段 remaining_count 的最小值。
DROP TABLE IF EXISTS `ticket_stock`;
CREATE TABLE `ticket_stock` (
  `id`              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `travel_date`     DATE     NOT NULL                COMMENT '乘车日期',
  `train_id`        BIGINT   NOT NULL                COMMENT '车次 train.id',
  `segment_index`   INT      NOT NULL                COMMENT '第几段，对应 train_segment.segment_index',
  `seat_type`       TINYINT  NOT NULL                COMMENT '席别：1=商务座，2=一等座，3=二等座',
  `from_station_id` BIGINT   NOT NULL                COMMENT '本段起点站（冗余，方便直接看数据和写调试 SQL）',
  `to_station_id`   BIGINT   NOT NULL                COMMENT '本段终点站（冗余）',
  `total_count`     INT      NOT NULL                COMMENT '本段该席别总票数（定员）',
  `sold_count`      INT      NOT NULL DEFAULT 0      COMMENT '已售出数量',
  `remaining_count` INT      NOT NULL                COMMENT '剩余票数 = total_count - sold_count',
  `version`         INT      NOT NULL DEFAULT 0      COMMENT '版本号（乐观锁/排查问题用）',
  `updated_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                     ON UPDATE CURRENT_TIMESTAMP COMMENT '最后变动时间',
  PRIMARY KEY (`id`),
  -- 一天 + 一趟车 + 一段 + 一个席别，只有一条库存记录
  UNIQUE KEY `uk_stock` (`travel_date`, `train_id`, `segment_index`, `seat_type`),
  -- 专门给「查某天某车次某席别、某个区间的余票」用的索引
  -- （seat_type 放在 segment_index 前面，才能先等值过滤再范围扫描）
  KEY `idx_stock_query` (`travel_date`, `train_id`, `seat_type`, `segment_index`),
  CONSTRAINT `chk_stock_remaining` CHECK (`remaining_count` >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '分段余票库存表（按日期）';


-- ============================================================================
--  四、订单
-- ============================================================================

-- 10. 订单主表：一次下单（一个车次一次出行）对应一条
DROP TABLE IF EXISTS `ticket_order`;
CREATE TABLE `ticket_order` (
  `id`                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no`           VARCHAR(32)   NOT NULL                COMMENT '业务订单号，如 202610101200001（唯一）',
  `user_id`            BIGINT        NOT NULL                COMMENT '下单账号 sys_user.id',
  `train_id`           BIGINT        NOT NULL                COMMENT '车次 train.id',
  `train_no`           VARCHAR(20)   NOT NULL                COMMENT '车次号（冗余，订单展示不再联表）',
  `travel_date`        DATE          NOT NULL                COMMENT '乘车日期',
  `from_station_id`    BIGINT        NOT NULL                COMMENT '出发站 station.id',
  `to_station_id`      BIGINT        NOT NULL                COMMENT '到达站 station.id',
  `from_segment_index` INT           NOT NULL                COMMENT '起售段号（含）',
  `to_segment_index`   INT           NOT NULL                COMMENT '止售段号（含）',
  `seat_type`          TINYINT       NOT NULL                COMMENT '席别',
  `passenger_count`    INT           NOT NULL                COMMENT '乘车人数',
  `total_amount`       DECIMAL(10,2) NOT NULL                COMMENT '订单总金额（元）',
  `status`             TINYINT       NOT NULL DEFAULT 0      COMMENT '0=待支付，1=已支付，2=已取消，3=部分退票，4=全部退票，5=已完成',
  `expire_time`        DATETIME      NOT NULL                COMMENT '支付截止时间，超时自动取消并回补库存',
  `paid_at`            DATETIME               DEFAULT NULL   COMMENT '支付时间',
  `refunded_count`     INT           NOT NULL DEFAULT 0      COMMENT '累计已退票数',
  `refunded_amount`    DECIMAL(10,2) NOT NULL DEFAULT 0      COMMENT '累计退款金额',
  `last_refunded_at`   DATETIME               DEFAULT NULL   COMMENT '最后一次退票时间',
  `cancelled_at`       DATETIME               DEFAULT NULL   COMMENT '取消时间',
  `created_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                 ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_order_user` (`user_id`, `created_at`),
  KEY `idx_order_train` (`travel_date`, `train_id`),
  KEY `idx_order_status` (`status`, `created_at`),
  KEY `idx_order_expire` (`status`, `expire_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单主表';


-- 11. 订单明细：一个订单买了几个人的票，就有几条（一票一行）
DROP TABLE IF EXISTS `ticket_order_item`;
CREATE TABLE `ticket_order_item` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id`       BIGINT        NOT NULL                COMMENT '所属订单 ticket_order.id',
  `passenger_id`   BIGINT        NOT NULL                COMMENT '乘车人 passenger.id',
  `passenger_name` VARCHAR(50)   NOT NULL                COMMENT '乘车人姓名（快照，防止改名后订单信息变化）',
  `id_card_no`     VARCHAR(64)   NOT NULL                COMMENT '证件号（快照，建议脱敏存储）',
  `seat_type`      TINYINT       NOT NULL                COMMENT '席别',
  `price`          DECIMAL(10,2) NOT NULL                COMMENT '这张票的价格',
  `coach_no`       VARCHAR(10)            DEFAULT NULL   COMMENT '车厢号（前期可不选座，留空）',
  `seat_no`        VARCHAR(10)            DEFAULT NULL   COMMENT '座位号（前期可不选座，留空）',
  `status`         TINYINT       NOT NULL DEFAULT 0      COMMENT '0=有效待出票，1=已出票，2=已退票',
  `refunded_at`    DATETIME               DEFAULT NULL   COMMENT '该张票的退票时间',
  `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_item_order` (`order_id`),
  KEY `idx_item_passenger` (`passenger_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单明细表（一票一行）';


-- 12. 管理端操作日志（阶段 6 再接入）
DROP TABLE IF EXISTS `admin_operation_log`;
CREATE TABLE `admin_operation_log` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `admin_id`   BIGINT       NOT NULL                COMMENT '操作人 sys_admin.id',
  `admin_name` VARCHAR(50)           DEFAULT NULL   COMMENT '操作人用户名（快照）',
  `module`     VARCHAR(50)  NOT NULL                COMMENT '模块，如 车次管理',
  `action`     VARCHAR(50)  NOT NULL                COMMENT '动作，如 新增/修改/删除',
  `target_id`  VARCHAR(64)           DEFAULT NULL   COMMENT '操作对象 id',
  `detail`     VARCHAR(500)          DEFAULT NULL   COMMENT '变更内容摘要',
  `ip`         VARCHAR(64)           DEFAULT NULL   COMMENT '操作 IP',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_log_admin` (`admin_id`, `created_at`),
  KEY `idx_log_module` (`module`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '管理端操作日志表';
