SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 画室 · 学员档案与画材库存
-- 库名 art_studio；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 注意：mysql 容器是 UTC，时间戳一律写 UTC_TIMESTAMP() / NOW()，日期用 CURDATE()。

CREATE DATABASE IF NOT EXISTS art_studio
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE art_studio;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS course_record;
DROP TABLE IF EXISTS material_use;
DROP TABLE IF EXISTS course_schedule;
DROP TABLE IF EXISTS art_material;
DROP TABLE IF EXISTS student;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 学员 ----------------
CREATE TABLE student (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    code         VARCHAR(32) NOT NULL UNIQUE COMMENT '学号',
    name         VARCHAR(64) NOT NULL COMMENT '姓名',
    phone        VARCHAR(24) COMMENT '手机',
    level        VARCHAR(16) COMMENT '少儿 / 成人 / 艺考',
    status       VARCHAR(16) COMMENT '在读 / 休学 / 结业',
    created_at   DATETIME COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 画材 ----------------
CREATE TABLE art_material (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(128) NOT NULL UNIQUE COMMENT '画材名',
    category     VARCHAR(16) COMMENT '颜料 / 画笔 / 画布 / 工具',
    stock        INT NOT NULL COMMENT '库存',
    unit         VARCHAR(16) COMMENT '单位',
    status       VARCHAR(16) COMMENT '在库 / 停用',
    created_at   DATETIME COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 课程排期（主从：依赖学员） ----------------
CREATE TABLE course_schedule (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id   BIGINT NOT NULL COMMENT '学员 id',
    course_name  VARCHAR(128) NOT NULL COMMENT '课程',
    date         DATE NOT NULL COMMENT '日期',
    start_min    INT NOT NULL COMMENT '开始分钟（如 540 = 09:00）',
    end_min      INT NOT NULL COMMENT '结束分钟（如 600 = 10:00）',
    status       VARCHAR(16) COMMENT '待上课 / 已上课 / 已取消',
    created_at   DATETIME COMMENT '创建时间',
    CONSTRAINT fk_cs_student FOREIGN KEY (student_id) REFERENCES student (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 领用记录（主从：依赖学员 + 画材） ----------------
CREATE TABLE material_use (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id   BIGINT NOT NULL COMMENT '学员 id',
    material_id  BIGINT NOT NULL COMMENT '画材 id',
    qty          INT NOT NULL COMMENT '数量',
    use_date     DATE NOT NULL COMMENT '日期',
    created_at   DATETIME COMMENT '创建时间',
    CONSTRAINT fk_mu_student FOREIGN KEY (student_id) REFERENCES student (id),
    CONSTRAINT fk_mu_material FOREIGN KEY (material_id) REFERENCES art_material (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 课后成果记录（一课次仅一份；依附课次与学员） ----------------
CREATE TABLE course_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id     BIGINT NOT NULL COMMENT '课次 id',
    student_id      BIGINT NOT NULL COMMENT '学员 id（取课次上的学员，落库留痕）',
    work_topic      VARCHAR(128) COMMENT '本次作品主题（草稿可空，提交必填）',
    completion      INT COMMENT '完成度 0-100（草稿可空，提交必填）',
    teacher_comment VARCHAR(1000) COMMENT '教师点评（草稿可空，提交必填）',
    next_practice   VARCHAR(1000) COMMENT '下次练习（选填）',
    status          VARCHAR(16) NOT NULL COMMENT '草稿 / 已提交 / 已归档',
    submitted_at    DATETIME COMMENT '提交时间',
    archived_at     DATETIME COMMENT '归档时间',
    created_at      DATETIME COMMENT '创建时间',
    updated_at      DATETIME COMMENT '最后更新时间',
    UNIQUE KEY uk_cr_schedule (schedule_id) COMMENT '同一课次只能有一份记录（并发收口的最后防线）',
    CONSTRAINT fk_cr_schedule FOREIGN KEY (schedule_id) REFERENCES course_schedule (id),
    CONSTRAINT fk_cr_student FOREIGN KEY (student_id) REFERENCES student (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------
-- 种子数据（含休学 / 结业 / 停用 / 待上课 / 已取消 等异常态，便于审计点检）
-- ---------------------------------------------------------------------

INSERT IGNORE INTO student (code, name, phone, level, status, created_at) VALUES
('S2026001', '林小满', '13800138001', '少儿', '在读', NOW()),
('S2026002', '周慕白', '13900139002', '成人', '在读', NOW()),
('S2026003', '陈嘉树', '13700137003', '艺考', '休学', NOW()),
('S2026004', '苏晚晴', '13600136004', '少儿', '结业', NOW()),
('S2026005', '许清欢', '13500135005', '成人', '在读', NOW()),
('S2026006', '江予安', '13400134006', '艺考', '在读', NOW());

INSERT IGNORE INTO art_material (name, category, stock, unit, status, created_at) VALUES
('温莎牛顿丙烯颜料', '颜料', 120, '支', '在库', NOW()),
('榭得堂尼龙画笔',   '画笔', 200, '支', '在库', NOW()),
('纯棉亚麻画布',     '画布', 60,  '张', '在库', NOW()),
('马利素描铅笔',     '工具', 300, '支', '在库', NOW()),
('调色刮刀',         '工具', 0,   '把', '停用', NOW()),
('刮画刀',           '工具', 15,  '把', '在库', NOW());

INSERT IGNORE INTO course_schedule (student_id, course_name, date, start_min, end_min, status, created_at) VALUES
(1, '少儿创意水彩', CURDATE(), 540, 600, '待上课', NOW()),
(1, '少儿创意水彩', CURDATE(), 660, 720, '待上课', NOW()),
(2, '成人油画基础', CURDATE(), 540, 660, '已上课', NOW()),
(5, '成人国画写意', CURDATE(), 600, 660, '待上课', NOW()),
(6, '艺考素描冲刺', DATE_ADD(CURDATE(), INTERVAL 1 DAY), 780, 900, '待上课', NOW()),
(2, '成人油画基础', CURDATE(), 720, 780, '已取消', NOW());

INSERT IGNORE INTO material_use (student_id, material_id, qty, use_date, created_at) VALUES
(1, 1, 2, CURDATE(), NOW()),
(1, 2, 5, CURDATE(), NOW()),
(2, 3, 1, CURDATE(), NOW()),
(5, 4, 3, CURDATE(), NOW()),
(6, 1, 1, CURDATE(), NOW());

-- 课次 3（成人油画基础 · 已上课）自带一份已归档成果记录，用于详情/档案展示
INSERT IGNORE INTO course_record
    (schedule_id, student_id, work_topic, completion, teacher_comment, next_practice, status, submitted_at, archived_at, created_at, updated_at)
VALUES
    (3, 2, '静物：苹果与陶罐', 85, '色调关系基本准确，暗部再透气些会更通透。', '下周练习单色明暗稿两张，注意边缘虚实。',
     '已归档', NOW(), NOW(), NOW(), NOW());
