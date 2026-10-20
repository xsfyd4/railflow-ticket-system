# 07 · MyBatis 语法手册

> 按「你实际会用到」的顺序整理，每个语法点都配了车票系统里的例子。

---

## 一、映射文件的骨架

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">

<mapper namespace="com.ticket.module.auth.mapper.SysUserMapper">
    <!-- 所有 SQL 写在这里 -->
</mapper>
```

三条硬规则：

| 规则 | 写错的后果 |
| --- | --- |
| 文件放在 `resources/mapper/` 下 | 启动时 XML 扫不到，所有 Mapper 方法失效 |
| `namespace` = Mapper 接口的全限定名 | 启动报 `Invalid bound statement (not found)` |
| 每个标签的 `id` = 接口里的方法名 | 同上 |

**这两处写错都不报编译错**，只在运行时炸。贴 XML 时先核对 namespace 和 id。

---

## 二、五种标签

| 标签 | 用途 | 返回值 |
| --- | --- | --- |
| `<select>` | 查询 | 一行 / 多行 / 单个值 |
| `<insert>` | 插入 | 影响行数 |
| `<update>` | 更新 | 影响行数 |
| `<delete>` | 删除 | 影响行数 |
| `<sql>` | 可复用的片段 | 不是语句，配合 `<include>` |

### 查询

```xml
<select id="findByUsername" resultType="com.ticket.module.auth.entity.SysUser">
    SELECT id, username, password, phone, nickname, avatar, status
    FROM sys_user
    WHERE username = #{username}
</select>
```

### 插入

```xml
<insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO sys_user (username, password, nickname, status)
    VALUES (#{username}, #{password}, #{nickname}, #{status})
</insert>
```

### 更新 / 删除

```xml
<update id="updateNickname">
    UPDATE sys_user SET nickname = #{nickname} WHERE id = #{id}
</update>

<delete id="deleteById">
    DELETE FROM sys_user WHERE id = #{id}
</delete>
```

### 复用 SQL 片段

```xml
<sql id="baseColumns">
    id, username, password, phone, nickname, avatar, status, created_at, updated_at
</sql>

<select id="findById" resultType="com.ticket.module.auth.entity.SysUser">
    SELECT <include refid="baseColumns"/>
    FROM sys_user
    WHERE id = #{id}
</select>
```

---

## 三、参数：`#{}` 与 `${}`

这是 MyBatis 最重要的一组语法，面试必问。

### `#{}` —— 预编译占位符（默认都用它）

```xml
SELECT * FROM sys_user WHERE username = #{username}
```

MyBatis 把它翻译成 JDBC 的 `?`，值作为**参数**传给数据库。

三个特点：

- 防 SQL 注入（值永远当"数据"，不当"语法"）
- 自动类型转换（String、Long、LocalDateTime 都能塞）
- **字符串自动加引号**，所以写 `#{name}` 就够了。别写成 `'#{name}'`，那会变成 `'张三'` 外面再套一层引号，报错

### `${}` —— 直接拼接（能不用就不用）

```xml
<!-- 危险 -->
SELECT * FROM sys_user WHERE ${column} = #{value}
```

`${column}` 会被原样拼进 SQL 字符串。传 `1=1 OR 1=1` 就注入成功了。

**那它存在的意义？** 有些东西不能用占位符：

| 场景 | 例子 | 为什么必须 `${}` |
| --- | --- | --- |
| 排序字段 | `ORDER BY ${sortField}` | 列名不能当参数传 |
| 表名 | `FROM ${tableName}` | 表名不能当参数传 |

**安全用法**：值必须来自白名单，绝不能直接来自用户输入。

```java
String column = switch (sortField) {
    case "price" -> "price";
    case "time"  -> "depart_time";
    default      -> "id";
};
```

### 参数有几种传法

**① 单个简单参数**

```java
SysUser findByUsername(@Param("username") String username);
```

**② 多个参数 —— 必须用 `@Param` 起名**

```java
List<Train> search(@Param("fromStationId") Long from,
                   @Param("toStationId") Long to);
```

```xml
WHERE from_station_id = #{fromStationId}
  AND to_station_id = #{toStationId}
```

不起名的话只能按位置叫 `arg0`、`param1`，可读性极差。

**③ 一个对象参数**

```java
int insert(SysUser user);
```

```xml
VALUES (#{username}, #{password}, #{nickname})
```

对象里的字段直接写名字，不用 `#{user.username}`。

**④ Map 参数** —— 和对象一样，按 key 取。

### 空值要指定 `jdbcType`

```xml
#{phone, jdbcType=VARCHAR}
```

当值是 `null` 时，JDBC 不知道这个 `?` 该按什么类型处理，某些数据库会报错。
MySQL 一般不强制，但**字段允许为 NULL 时加上 `jdbcType` 更稳**。

---

## 四、结果映射

### `resultType` —— 自动映射（90% 的场景）

```xml
<select id="findById" resultType="com.ticket.module.auth.entity.SysUser">
    SELECT id, username, nickname FROM sys_user WHERE id = #{id}
</select>
```

配合配置：

```yaml
mybatis:
  configuration:
    map-underscore-to-camel-case: true
```

`real_name` 就能自动对上 `realName`。

**返回单个值**：

```xml
<select id="countByUsername" resultType="int">
    SELECT COUNT(*) FROM sys_user WHERE username = #{username}
</select>
```

**返回 Map**：

```xml
<select id="statByDay" resultType="java.util.Map">
    SELECT DATE(created_at) AS day, COUNT(*) AS cnt
    FROM ticket_order GROUP BY DATE(created_at)
</select>
```

**返回 List**：接口声明 `List<T>`，XML 里 `resultType` 写元素类型。

### `resultMap` —— 手动映射（10% 的场景）

什么时候必须用它：

- 列名和属性名对不上，又不想改 SQL 别名
- `COUNT(*)` 这类结果要映射到某个字段
- **一对一 / 一对多关联查询**

```xml
<resultMap id="trainMap" type="com.ticket.module.train.entity.Train">
    <id     property="id"      column="id"/>
    <result property="trainNo" column="train_no"/>
    <result property="type"    column="train_type"/>
</resultMap>

<select id="findById" resultMap="trainMap">
    SELECT id, train_no, train_type FROM train WHERE id = #{id}
</select>
```

`<id>` 标主键（对性能有优化），`<result>` 标普通字段。

### 一对多

一个车次有多个停站：

```xml
<resultMap id="trainWithStops" type="com.ticket.module.train.entity.Train">
    <id property="id" column="id"/>
    <result property="trainNo" column="train_no"/>
    <collection property="stops" ofType="com.ticket.module.train.entity.TrainStop">
        <id     property="id"          column="stop_id"/>
        <result property="stopOrder"   column="stop_order"/>
        <result property="stationName" column="station_name"/>
    </collection>
</resultMap>
```

MyBatis 会把"车次相同的多行"自动合并成一个对象，停站塞进 `List`。

一对一用 `<association>`，写法一样，只是不装集合。

> 经验：**能用两次单表查询拼起来的，就别用嵌套映射。**
> 嵌套映射写起来爽，但 SQL 一复杂就很难调，还容易踩 N+1 查询的坑。

---

## 五、动态 SQL（MyBatis 的核心竞争力）

### `<if>` —— 条件拼接

```xml
<select id="search" resultType="com.ticket.module.station.entity.Station">
    SELECT * FROM station
    WHERE status = 1
    <if test="keyword != null and keyword != ''">
        AND station_name LIKE CONCAT('%', #{keyword}, '%')
    </if>
</select>
```

`test` 里是 **OGNL 表达式**，注意：

- 字符串判空：`keyword != null and keyword != ''`
- 数字判空：`status != null`（别写 `status != ''`）
- 布尔：`enabled == true`，或直接写 `enabled`
- 比字符串：`type == 'G'`（用单引号）

### `<where>` —— 自动处理 WHERE 和多余的 AND

```xml
<select id="search" resultType="com.ticket.module.station.entity.Station">
    SELECT * FROM station
    <where>
        <if test="keyword != null and keyword != ''">
            AND station_name LIKE CONCAT('%', #{keyword}, '%')
        </if>
        <if test="city != null and city != ''">
            AND city = #{city}
        </if>
    </where>
</select>
```

`<where>` 干两件事：

1. 里面有条件时，自动补上 `WHERE`
2. **自动去掉开头多余的 `AND` / `OR`**

没有它就得手写 `WHERE 1=1` 再拼 `AND`，能跑，但丑。

### `<set>` —— 自动处理 UPDATE 的逗号

```xml
<update id="updateSelective">
    UPDATE sys_user
    <set>
        <if test="nickname != null">nickname = #{nickname},</if>
        <if test="phone != null">phone = #{phone},</if>
        <if test="status != null">status = #{status},</if>
    </set>
    WHERE id = #{id}
</update>
```

自动去掉末尾多余的逗号，也不会在没条件时留下一个孤零零的 `SET`。

### `<choose>` —— 多选一

```xml
<where>
    <choose>
        <when test="status != null">status = #{status}</when>
        <when test="orderNo != null">order_no = #{orderNo}</when>
        <otherwise>1 = 1</otherwise>
    </choose>
</where>
```

相当于 Java 的 `switch`，**只命中第一个 `<when>`**。

### `<foreach>` —— 循环（非常重要）

**① IN 查询**

```xml
<select id="findByIds" resultType="com.ticket.module.station.entity.Station">
    SELECT * FROM station
    WHERE id IN
    <foreach collection="ids" item="id" open="(" separator="," close=")">
        #{id}
    </foreach>
</select>
```

生成 `WHERE id IN (?, ?, ?)`。

**② 批量插入**

```xml
<insert id="batchInsert">
    INSERT INTO ticket_stock
        (travel_date, train_id, segment_index, seat_type, total_count, remaining_count)
    VALUES
    <foreach collection="list" item="s" separator=",">
        (#{s.travelDate}, #{s.trainId}, #{s.segmentIndex},
         #{s.seatType}, #{s.totalCount}, #{s.remainingCount})
    </foreach>
</insert>
```

**③ OR 条件**

```xml
<foreach collection="keywords" item="kw" open="(" separator=" OR " close=")">
    station_name LIKE CONCAT('%', #{kw}, '%')
</foreach>
```

**`collection` 该写什么？**

| 接口参数 | collection 写 |
| --- | --- |
| `List<X> ids`（单参数） | `ids` 或 `list` |
| `X[] arr` | `array` |
| `@Param("ids") List<X> ids` | `ids` |

**建议一律用 `@Param` 起名**，省得记这些默认值。

### `<trim>` —— 自定义前后缀

```xml
<trim prefix="WHERE" prefixOverrides="AND |OR ">
    ...
</trim>
```

`<where>` 本质上就是这个配置的 `<trim>`。知道有这回事就行，日常直接用 `<where>`。

### `<bind>` —— 定义变量

```xml
<bind name="pattern" value="'%' + keyword + '%'"/>
SELECT * FROM station WHERE station_name LIKE #{pattern}
```

适合把重复的拼接逻辑抽出来。

---

## 六、主键回填

### 自增主键（MySQL）

```xml
<insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO sys_user (username, password) VALUES (#{username}, #{password})
</insert>
```

```java
sysUserMapper.insert(user);
Long id = user.getId();     // 主键自己回来了
```

**记住：`insert` 返回的是影响行数，不是主键。** 从 JdbcTemplate 转过来最容易搞错的就是这一点。

### 非自增主键（序列 / UUID）

```xml
<insert id="insert">
    <selectKey keyProperty="id" order="BEFORE" resultType="java.lang.Long">
        SELECT NEXT VALUE FOR seq_ticket FROM dual
    </selectKey>
    INSERT INTO ticket_order (id, order_no) VALUES (#{id}, #{orderNo})
</insert>
```

`BEFORE` = 插入前取主键；`AFTER` = 插入后查回来。
MySQL 自增场景用 `useGeneratedKeys` 就够。

---

## 七、批量操作

### 批量插入

```xml
<insert id="batchInsert">
    INSERT INTO ticket_stock
        (travel_date, train_id, segment_index, seat_type, remaining_count)
    VALUES
    <foreach collection="list" item="s" separator=",">
        (#{s.travelDate}, #{s.trainId}, #{s.segmentIndex},
         #{s.seatType}, #{s.remainingCount})
    </foreach>
</insert>
```

比"循环调单条 insert"快得多——一次网络往返。

MySQL 有 `max_allowed_packet` 限制，一次别塞几万条，分批（比如每批 500）更稳。

### 重复数据不报错：`ON DUPLICATE KEY UPDATE`

阶段 4 生成库存时会用到：同一天同一车次重复生成，不能报错，要"存在就跳过"。

```xml
<insert id="insertIgnore">
    INSERT INTO ticket_stock (...)
    VALUES
    <foreach collection="list" item="s" separator=",">
        (...)
    </foreach>
    ON DUPLICATE KEY UPDATE id = id
</insert>
```

`UPDATE id = id` 是"什么都不做"的惯用写法——靠唯一索引挡重复，
比"先查再插"快，也没有并发窗口。

---

## 八、注解写法（不用 XML）

```java
@Mapper
public interface StationMapper {

    @Select("SELECT * FROM station WHERE id = #{id}")
    Station findById(@Param("id") Long id);

    @Insert("INSERT INTO station (station_name, city) VALUES (#{stationName}, #{city})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Station station);

    @Update("UPDATE station SET station_name = #{stationName} WHERE id = #{id}")
    int update(Station station);

    @Delete("DELETE FROM station WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
```

**怎么选？**

| 用注解 | 用 XML |
| --- | --- |
| SQL 很短（一行） | SQL 长、有 JOIN |
| 没有动态条件 | 有 `<if>` / `<foreach>` |
| 一两个接口 | 整个模块 |

**经验规则：单表 CRUD 且无动态条件用注解；其余一律 XML。**
混用没问题，但别为了"统一"把复杂 SQL 硬塞进注解——那会变成一坨字符串拼接。

---

## 九、日志：看 MyBatis 到底执行了什么

`logging.level.com.ticket: debug` 打开后，控制台会出现：

```
==>  Preparing: SELECT id, username FROM sys_user WHERE username = ?
==> Parameters: zhangsan(String)
<==      Total: 1
```

三行分别是：**最终 SQL、实际参数、返回条数**。

调试"为什么查不到数据"时，第一件事就是看这三行，比打断点快得多。

---

## 十、常见错误对照表

| 报错 | 原因 | 怎么修 |
| --- | --- | --- |
| `Invalid bound statement (not found)` | namespace / id / 方法名对不上，或 XML 没被扫描 | 三处逐一核对 |
| `Parameter 'xxx' not found` | 多参数没加 `@Param`，或名字写错 | 补 `@Param("xxx")` |
| 查出来字段全是 null | 列名与属性名对不上，且没开驼峰映射 | 开 `map-underscore-to-camel-case`，或写别名 |
| `TooManyResultsException` | 方法返回单个对象，但 SQL 查出多行 | 加 `LIMIT 1`，或改成 `List` |
| `BadSqlGrammarException` | SQL 语法错 / 表名字段名写错 | 把日志里的最终 SQL 拿去数据库直接跑 |
| 插入后 `getId()` 是 null | 没配 `useGeneratedKeys` / `keyProperty` | 补上这两个属性 |
| 中文乱码 | 连接串缺字符集 | URL 里加 `characterEncoding=utf8` |

---

## 十一、本项目会用到的三个典型场景

### 场景 1：阶段 3 车站分页搜索

```xml
<select id="search" resultType="com.ticket.module.station.entity.Station">
    SELECT id, station_code, station_name, city, status
    FROM station
    <where>
        <if test="keyword != null and keyword != ''">
            AND (station_name LIKE CONCAT('%', #{keyword}, '%')
                 OR pinyin LIKE CONCAT('%', #{keyword}, '%')
                 OR pinyin_abbr LIKE CONCAT('%', #{keyword}, '%'))
        </if>
        <if test="city != null and city != ''">
            AND city = #{city}
        </if>
        AND status = 1
    </where>
    ORDER BY id
    LIMIT #{offset}, #{size}
</select>
```

### 场景 2：阶段 4 批量生成库存

```xml
<insert id="batchInsertIgnore">
    INSERT INTO ticket_stock
        (travel_date, train_id, segment_index, seat_type,
         from_station_id, to_station_id, total_count, sold_count, remaining_count)
    VALUES
    <foreach collection="list" item="s" separator=",">
        (#{s.travelDate}, #{s.trainId}, #{s.segmentIndex}, #{s.seatType},
         #{s.fromStationId}, #{s.toStationId},
         #{s.totalCount}, 0, #{s.remainingCount})
    </foreach>
    ON DUPLICATE KEY UPDATE id = id
</insert>
```

一次插几百条，比循环单插快几十倍。

### 场景 3：阶段 5 扣库存

```xml
<update id="deduct">
    UPDATE ticket_stock
    SET sold_count = sold_count + #{count},
        remaining_count = remaining_count - #{count},
        version = version + 1
    WHERE travel_date = #{travelDate}
      AND train_id = #{trainId}
      AND seat_type = #{seatType}
      AND segment_index BETWEEN #{fromIndex} AND #{toIndex}
      AND remaining_count &gt;= #{count}
</update>
```

**注意 `&gt;=`！** XML 里 `<` 和 `>` 是标签符号，直接写会被当成标签解析而报错。

| 符号 | XML 里写成 |
| --- | --- |
| `<` | `&lt;` |
| `>` | `&gt;` |
| `<=` | `&lt;=` |
| `>=` | `&gt;=` |
| `<>` | `&lt;&gt;` |

也可以整段用 CDATA 包起来：

```xml
<![CDATA[ AND remaining_count >= #{count} ]]>
```

建议统一用转义实体，比 CDATA 好读。

---

## 十二、一句话总结

> **MyBatis 的语法核心就三件事：`#{}` 传参、动态标签拼 SQL、`resultType` 做映射。**
> 其余都是这三件事的变体。
>
> 等到阶段 4 写余票查询时，你会发现 `<if>` + `<foreach>` + `<where>` 这三件套能覆盖 80% 的需求。
