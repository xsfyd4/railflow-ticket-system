# -*- coding: utf-8 -*-
"""
生成演示数据：约 100 个车站 + 40 趟车（含停站、分段、票价）。

用法：
    python scripts/gen_demo_data.py

输出：
    sql/03-demo-data-large.sql

为什么用脚本而不是手写 SQL：
    40 趟车 × (8 个停站 + 7 个分段 + 21 条票价) ≈ 1500 行。
    手写不现实，而且改一个参数（比如速度）就要全部重算。

数据的性质：
    车站名是真实的，但**三字码、里程、时刻、票价都是演示值**，不是官方数据。
    目的是让分页、搜索、查票有足够的样本，能看出效果。
"""

import os
import zlib
from datetime import datetime, timedelta

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(BASE, "sql", "03-demo-data-large.sql")

# (车站名, 城市, 省份, 全拼, 首字母)
STATIONS = [
    # 华北
    ("北京南", "北京", "北京市", "beijingnan", "bjn"),
    ("北京西", "北京", "北京市", "beijingxi", "bjx"),
    ("北京", "北京", "北京市", "beijing", "bj"),
    ("天津", "天津", "天津市", "tianjin", "tj"),
    ("天津西", "天津", "天津市", "tianjinxi", "tjx"),
    ("滨海", "天津", "天津市", "binhai", "bh"),
    ("石家庄", "石家庄", "河北省", "shijiazhuang", "sjz"),
    ("保定东", "保定", "河北省", "baodingdong", "bdd"),
    ("唐山", "唐山", "河北省", "tangshan", "ts"),
    ("秦皇岛", "秦皇岛", "河北省", "qinhuangdao", "qhd"),
    ("廊坊", "廊坊", "河北省", "langfang", "lf"),
    ("沧州西", "沧州", "河北省", "cangzhouxi", "czx"),
    ("衡水北", "衡水", "河北省", "hengshuibei", "hsb"),
    ("太原南", "太原", "山西省", "taiyuannan", "tyn"),
    ("大同南", "大同", "山西省", "datongnan", "dtn"),
    ("呼和浩特东", "呼和浩特", "内蒙古自治区", "huhehaotedong", "hhhtd"),
    ("包头", "包头", "内蒙古自治区", "baotou", "bt"),
    # 东北
    ("沈阳北", "沈阳", "辽宁省", "shenyangbei", "syb"),
    ("沈阳", "沈阳", "辽宁省", "shenyang", "sy"),
    ("大连北", "大连", "辽宁省", "dalianbei", "dlb"),
    ("锦州南", "锦州", "辽宁省", "jinzhounan", "jzn"),
    ("长春", "长春", "吉林省", "changchun", "cc"),
    ("吉林", "吉林", "吉林省", "jilin", "jl"),
    ("哈尔滨西", "哈尔滨", "黑龙江省", "haerbinxi", "hebx"),
    ("哈尔滨", "哈尔滨", "黑龙江省", "haerbin", "heb"),
    ("齐齐哈尔南", "齐齐哈尔", "黑龙江省", "qiqihaernan", "qqhen"),
    # 华东
    ("上海虹桥", "上海", "上海市", "shanghaihongqiao", "shhq"),
    ("上海", "上海", "上海市", "shanghai", "sh"),
    ("上海南", "上海", "上海市", "shanghainan", "shn"),
    ("南京南", "南京", "江苏省", "nanjingnan", "njn"),
    ("南京", "南京", "江苏省", "nanjing", "nj"),
    ("苏州北", "苏州", "江苏省", "suzhoubei", "szb"),
    ("苏州", "苏州", "江苏省", "suzhou", "sz"),
    ("无锡东", "无锡", "江苏省", "wuxidong", "wxd"),
    ("常州北", "常州", "江苏省", "changzhoubei", "czb"),
    ("徐州东", "徐州", "江苏省", "xuzhoudong", "xzd"),
    ("南通", "南通", "江苏省", "nantong", "nt"),
    ("扬州东", "扬州", "江苏省", "yangzhoudong", "yzd"),
    ("镇江", "镇江", "江苏省", "zhenjiang", "zj"),
    ("杭州东", "杭州", "浙江省", "hangzhoudong", "hzd"),
    ("杭州", "杭州", "浙江省", "hangzhou", "hz"),
    ("宁波", "宁波", "浙江省", "ningbo", "nb"),
    ("温州南", "温州", "浙江省", "wenzhounan", "wzn"),
    ("金华", "金华", "浙江省", "jinhua", "jh"),
    ("绍兴北", "绍兴", "浙江省", "shaoxingbei", "sxb"),
    ("合肥南", "合肥", "安徽省", "hefeinan", "hfn"),
    ("合肥", "合肥", "安徽省", "hefei", "hf"),
    ("芜湖", "芜湖", "安徽省", "wuhu", "wh"),
    ("蚌埠南", "蚌埠", "安徽省", "bengbunan", "bbn"),
    ("黄山北", "黄山", "安徽省", "huangshanbei", "hsb"),
    ("济南西", "济南", "山东省", "jinanxi", "jnx"),
    ("济南", "济南", "山东省", "jinan", "jn"),
    ("青岛北", "青岛", "山东省", "qingdaobei", "qdb"),
    ("青岛", "青岛", "山东省", "qingdao", "qd"),
    ("烟台", "烟台", "山东省", "yantai", "yt"),
    ("潍坊北", "潍坊", "山东省", "weifangbei", "wfb"),
    ("淄博北", "淄博", "山东省", "zibobei", "zbb"),
    ("泰安", "泰安", "山东省", "taian", "ta"),
    ("曲阜东", "曲阜", "山东省", "qufudong", "qfd"),
    ("福州", "福州", "福建省", "fuzhou", "fz"),
    ("福州南", "福州", "福建省", "fuzhounan", "fzn"),
    ("厦门北", "厦门", "福建省", "xiamenbei", "xmb"),
    ("厦门", "厦门", "福建省", "xiamen", "xm"),
    ("泉州", "泉州", "福建省", "quanzhou", "qz"),
    ("莆田", "莆田", "福建省", "putian", "pt"),
    ("南昌西", "南昌", "江西省", "nanchangxi", "ncx"),
    ("南昌", "南昌", "江西省", "nanchang", "nc"),
    ("赣州西", "赣州", "江西省", "ganzhouxi", "gzx"),
    ("九江", "九江", "江西省", "jiujiang", "jj"),
    # 华中
    ("郑州东", "郑州", "河南省", "zhengzhoudong", "zzd"),
    ("郑州", "郑州", "河南省", "zhengzhou", "zz"),
    ("洛阳龙门", "洛阳", "河南省", "luoyanglongmen", "lylm"),
    ("开封北", "开封", "河南省", "kaifengbei", "kfb"),
    ("武汉", "武汉", "湖北省", "wuhan", "whan"),
    ("汉口", "武汉", "湖北省", "hankou", "hk"),
    ("武昌", "武汉", "湖北省", "wuchang", "wc"),
    ("宜昌东", "宜昌", "湖北省", "yichangdong", "ycd"),
    ("襄阳东", "襄阳", "湖北省", "xiangyangdong", "xyd"),
    ("长沙南", "长沙", "湖南省", "changshanan", "csn"),
    ("长沙", "长沙", "湖南省", "changsha", "cs"),
    ("株洲西", "株洲", "湖南省", "zhuzhouxi", "zzx"),
    ("岳阳东", "岳阳", "湖南省", "yueyangdong", "yyd"),
    ("衡阳东", "衡阳", "湖南省", "hengyangdong", "hyd"),
    # 华南
    ("广州南", "广州", "广东省", "guangzhounan", "gzn"),
    ("广州", "广州", "广东省", "guangzhou", "gz"),
    ("深圳北", "深圳", "广东省", "shenzhenbei", "sznb"),
    ("深圳", "深圳", "广东省", "shenzhen", "szn"),
    ("东莞", "东莞", "广东省", "dongguan", "dg"),
    ("佛山", "佛山", "广东省", "foshan", "fs"),
    ("珠海", "珠海", "广东省", "zhuhai", "zh"),
    ("惠州北", "惠州", "广东省", "huizhoubei", "hzb"),
    ("湛江西", "湛江", "广东省", "zhanjiangxi", "zjx"),
    ("南宁东", "南宁", "广西壮族自治区", "nanningdong", "nnd"),
    ("桂林北", "桂林", "广西壮族自治区", "guilinbei", "glb"),
    ("柳州", "柳州", "广西壮族自治区", "liuzhou", "lz"),
    ("海口东", "海口", "海南省", "haikoudong", "hkd"),
    # 西南 / 西北
    ("成都东", "成都", "四川省", "chengdudong", "cdd"),
    ("重庆北", "重庆", "重庆市", "chongqingbei", "cqb"),
    ("贵阳北", "贵阳", "贵州省", "guiyangbei", "gyb"),
    ("昆明南", "昆明", "云南省", "kunmingnan", "kmn"),
    ("西安北", "西安", "陕西省", "xianbei", "xab"),
    ("兰州西", "兰州", "甘肃省", "lanzhouxi", "lzx"),
]

# 线路：(起点, 途经站, 终点)
ROUTES = [
    ("北京南", ["济南西", "南京南", "苏州北"], "上海虹桥"),
    ("北京西", ["石家庄", "郑州东", "武汉", "长沙南"], "广州南"),
    ("上海虹桥", [], "杭州东"),
    ("北京南", [], "天津"),
    ("广州南", [], "深圳北"),
    ("北京西", ["郑州东"], "西安北"),
    ("上海虹桥", ["南京南"], "合肥南"),
    ("杭州东", [], "宁波"),
    ("南京南", ["镇江", "常州北", "无锡东", "苏州北"], "上海虹桥"),
    ("济南西", ["淄博北", "潍坊北"], "青岛北"),
    ("武汉", ["岳阳东", "长沙南"], "广州南"),
    ("成都东", [], "重庆北"),
    ("成都东", ["西安北"], "郑州东"),
    ("深圳北", ["惠州北"], "厦门北"),
    ("福州", ["莆田", "泉州"], "厦门北"),
    ("南昌西", ["九江"], "武汉"),
    ("合肥南", [], "南京南"),
    ("天津", ["唐山", "秦皇岛"], "沈阳北"),
    ("西安北", ["洛阳龙门"], "郑州东"),
    ("长沙南", ["衡阳东"], "广州南"),
]

SEAT_TYPES = [(1, 3.0), (2, 1.6), (3, 1.0)]  # (席别, 相对二等座的倍数)


def distance(a, b):
    """两站之间的演示里程：确定性伪随机，同一对站永远一样"""
    key = ("%s|%s" % (a, b)).encode("utf-8")
    return 80 + zlib.crc32(key) % 420


def money(x):
    return round(x, 2)


def build():
    name_to_id = {s[0]: i for i, s in enumerate(STATIONS, start=1)}

    for start, mids, end in ROUTES:
        for s in [start] + mids + [end]:
            if s not in name_to_id:
                raise SystemExit("线路里的车站不存在：%s" % s)

    out = []
    add = out.append

    add("-- 本文件由 scripts/gen_demo_data.py 自动生成，请勿手改。")
    add("-- ⚠️ 会先清空 station / train / train_stop / train_segment /")
    add("--    train_segment_price / ticket_stock 六张表，只适合开发阶段使用。")
    add("-- 车站名真实，但三字码、里程、时刻、票价均为演示值。")
    add("")
    add("USE `train_ticket`;")
    add("")
    add("DELETE FROM `ticket_stock`;")
    add("DELETE FROM `train_segment_price`;")
    add("DELETE FROM `train_segment`;")
    add("DELETE FROM `train_stop`;")
    add("DELETE FROM `train`;")
    add("DELETE FROM `station`;")
    add("")

    # ---------------- 车站 ----------------
    add("-- ========== 车站：%d 个 ==========" % len(STATIONS))
    used_codes = set()
    rows = []
    for i, (name, city, prov, py, abbr) in enumerate(STATIONS, start=1):
        # 直接用拼音首字母当演示三字码，不补位。
        # 补位（比如 tj → TJX）会和真实的 tjx（天津西）撞车，反而更乱。
        code = abbr.upper()[:10]
        base = code
        code = base
        n = 1
        while code in used_codes:
            code = "%s%d" % (base[:8], n)
            n += 1
        used_codes.add(code)
        rows.append("(%d, '%s', '%s', '%s', '%s', '%s', '%s', 1)" %
                    (i, code, name, city, prov, py, abbr))
    add("INSERT INTO `station` (`id`, `station_code`, `station_name`, `city`, "
        "`province`, `pinyin`, `pinyin_abbr`, `status`) VALUES")
    add(",\n".join(rows) + ";")
    add("")

    # ---------------- 车次 + 停站 + 分段 + 票价 ----------------
    train_rows, stop_rows, seg_rows, price_rows = [], [], [], []
    train_id = 0
    stop_id = 0
    seg_id = 0
    no_seq = 1001

    for start, mids, end in ROUTES:
        path = [start] + mids + [end]
        # 每条线路两趟车：一趟早上、一趟下午
        for variant in range(2):
            train_id += 1
            train_no = "G%d" % no_seq
            no_seq += 2

            depart = datetime(2026, 1, 1, 6 + variant * 6 + (train_id % 3), (train_id * 17) % 60)
            speed = 280.0
            t = depart
            cum = 0.0
            total_mileage = sum(distance(path[i], path[i + 1]) for i in range(len(path) - 1))

            train_rows.append("(%d, '%s', 'G', %d, %d, %.1f, 1, '演示车次')" %
                              (train_id, train_no, name_to_id[start],
                               name_to_id[end], total_mileage))

            for idx, st_name in enumerate(path):
                stop_id += 1
                if idx == 0:
                    arr_time, arr_off = None, 0
                else:
                    d = distance(path[idx - 1], st_name)
                    cum += d
                    t = t + timedelta(minutes=max(8, int(d / speed * 60)))
                    arr_time = t.time()
                    arr_off = (t.date() - depart.date()).days

                if idx == len(path) - 1:
                    dep_time, dep_off = None, arr_off
                else:
                    dep_off = arr_off
                    if idx > 0:
                        t = t + timedelta(minutes=2)
                    dep_time = t.time()

                stop_rows.append("(%d, %d, %d, %d, %s, %s, %d, %.1f, %d, %d)" % (
                    stop_id, train_id, idx + 1, name_to_id[st_name],
                    "'%s'" % arr_time.strftime("%H:%M:%S") if arr_time else "NULL",
                    "'%s'" % dep_time.strftime("%H:%M:%S") if dep_time else "NULL",
                    dep_off,
                    cum,
                    1 if idx == 0 else 0,
                    1 if idx == len(path) - 1 else 0,
                ))

                # 第 idx 站之后的那一段（最后站没有）
                if idx < len(path) - 1:
                    seg_id += 1
                    seg_mileage = distance(st_name, path[idx + 1])
                    seg_rows.append("(%d, %d, %d, %d, %d, %d, %d, %.1f)" % (
                        seg_id, train_id, idx + 1, idx + 1, idx + 2,
                        name_to_id[st_name], name_to_id[path[idx + 1]], seg_mileage))

                    base_price = seg_mileage * 0.45
                    for seat_type, ratio in SEAT_TYPES:
                        price_rows.append("(%d, %d, %d, %.2f)" % (
                            train_id, idx + 1, seat_type, money(base_price * ratio)))

    add("-- ========== 车次：%d 趟 ==========" % train_id)
    add("INSERT INTO `train` (`id`, `train_no`, `train_type`, `start_station_id`, "
        "`end_station_id`, `total_mileage`, `status`, `remark`) VALUES")
    add(",\n".join(train_rows) + ";")
    add("")

    add("-- ========== 停站：%d 条 ==========" % len(stop_rows))
    add("INSERT INTO `train_stop` (`id`, `train_id`, `stop_order`, `station_id`, "
        "`arrival_time`, `departure_time`, `day_offset`, `mileage`, "
        "`is_start`, `is_end`) VALUES")
    add(",\n".join(stop_rows) + ";")
    add("")

    add("-- ========== 分段：%d 条 ==========" % len(seg_rows))
    add("INSERT INTO `train_segment` (`id`, `train_id`, `segment_index`, "
        "`from_stop_order`, `to_stop_order`, `from_station_id`, `to_station_id`, "
        "`mileage`) VALUES")
    add(",\n".join(seg_rows) + ";")
    add("")

    add("-- ========== 票价：%d 条 ==========" % len(price_rows))
    add("INSERT INTO `train_segment_price` (`train_id`, `segment_index`, "
        "`seat_type`, `price`) VALUES")
    add(",\n".join(price_rows) + ";")
    add("")
    add("-- 提示：ticket_stock（库存）不在这里生成，")
    add("-- 阶段 4 会写「按日期批量生成库存」的接口，用程序生成。")

    return "\n".join(out) + "\n", train_id, len(stop_rows), len(seg_rows), len(price_rows)


def main():
    sql, trains, stops, segs, prices = build()
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(sql)
    print("已生成：%s" % OUT)
    print("车站 %d 个 / 车次 %d 趟 / 停站 %d 条 / 分段 %d 条 / 票价 %d 条" %
          (len(STATIONS), trains, stops, segs, prices))


if __name__ == "__main__":
    main()
