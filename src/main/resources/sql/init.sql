-- =====================================================
-- VRP Demo 数据库初始化脚本
-- 数据库名: vrp_db
-- =====================================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS vrp_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE vrp_db;

-- =====================================================
-- 1. 节点表（配送点/仓库）
-- =====================================================
DROP TABLE IF EXISTS vrp_node;
CREATE TABLE vrp_node (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    node_code VARCHAR(50) NOT NULL COMMENT '节点编码',
    name VARCHAR(100) COMMENT '节点名称',
    x_coordinate DOUBLE NOT NULL COMMENT 'X坐标',
    y_coordinate DOUBLE NOT NULL COMMENT 'Y坐标',
    node_type TINYINT DEFAULT 1 COMMENT '节点类型：1-配送点 2-仓库',
    demand DOUBLE DEFAULT 0 COMMENT '需求量',
    status TINYINT DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_node_code (node_code),
    KEY idx_node_type (node_type),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节点表（配送点/仓库）';

-- =====================================================
-- 2. 车辆表
-- =====================================================
DROP TABLE IF EXISTS vrp_vehicle;
CREATE TABLE vrp_vehicle (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    vehicle_code VARCHAR(50) NOT NULL COMMENT '车辆编码',
    capacity DOUBLE DEFAULT 100 COMMENT '最大载重',
    status TINYINT DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_vehicle_code (vehicle_code),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车辆表';

-- =====================================================
-- 3. 计算任务表
-- =====================================================
DROP TABLE IF EXISTS vrp_task;
CREATE TABLE vrp_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    task_name VARCHAR(100) COMMENT '任务名称',
    vehicle_count INT NOT NULL COMMENT '使用车辆数',
    total_distance DOUBLE COMMENT '总里程',
    status TINYINT DEFAULT 0 COMMENT '状态：0-待计算 1-计算中 2-完成 3-失败',
    result_json TEXT COMMENT '结果JSON',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    complete_time DATETIME COMMENT '完成时间',
    KEY idx_status (status),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='VRP计算任务表';

-- =====================================================
-- 4. 路线明细表
-- =====================================================
DROP TABLE IF EXISTS vrp_route_detail;
CREATE TABLE vrp_route_detail (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    task_id BIGINT NOT NULL COMMENT '任务ID',
    vehicle_id BIGINT NOT NULL COMMENT '车辆ID',
    sequence INT COMMENT '配送顺序',
    node_id BIGINT NOT NULL COMMENT '节点ID',
    distance_from_prev DOUBLE COMMENT '距上一节点距离',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_task_id (task_id),
    KEY idx_vehicle_id (vehicle_id),
    KEY idx_sequence (task_id, vehicle_id, sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='路线明细表';

-- =====================================================
-- 初始化数据
-- =====================================================

-- 插入默认仓库（配送中心）
INSERT INTO vrp_node (node_code, name, x_coordinate, y_coordinate, node_type, demand, status)
VALUES ('DEPOT', '配送中心', 0, 0, 2, 0, 1);

-- 插入默认车辆（20辆）
INSERT INTO vrp_vehicle (vehicle_code, capacity, status) VALUES
('V001', 100, 1), ('V002', 100, 1), ('V003', 100, 1), ('V004', 100, 1), ('V005', 100, 1),
('V006', 100, 1), ('V007', 100, 1), ('V008', 100, 1), ('V009', 100, 1), ('V010', 100, 1),
('V011', 100, 1), ('V012', 100, 1), ('V013', 100, 1), ('V014', 100, 1), ('V015', 100, 1),
('V016', 100, 1), ('V017', 100, 1), ('V018', 100, 1), ('V019', 100, 1), ('V020', 100, 1);

-- =====================================================
-- 完成
-- =====================================================
SELECT '数据库初始化完成！' AS message;
