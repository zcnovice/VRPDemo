# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 重要规则

- **算法代码操作**：对算法代码（algorithm/、vo/目录下的文件）进行修改、读取、新增、删除操作时，**不要**主动查看"乡镇配送项目文档"目录。只有在用户明确要求读取时才去读取分析该目录内容。

## 项目概述

基于Spring Boot的车辆路径规划(VRP)系统，使用模拟退火算法求解多车辆配送路径优化问题。支持基于实际道路距离的路径计算。

## 常用命令

```bash
# 构建项目
./mvnw clean package

# 运行应用（默认端口7070）
./mvnw spring-boot:run

# 运行单个测试
./mvnw test -Dtest=VrpDemoApplicationTests

# 跳过测试打包
./mvnw clean package -DskipTests
```

## 架构设计

### 核心分层

```
controller/  → REST API (/api/vrp/*)
service/     → 业务逻辑 + 任务管理
algorithm/   → 模拟退火算法实现
vo/          → 算法层数据模型（NodeVO, VehicleVO, SolutionVO）
entity/      → 数据库实体（MyBatis-Plus）
mapper/      → 数据访问层
```

### 数据流向

1. `VrpController` 接收请求 → `VrpService` 创建任务记录
2. `VrpServiceImpl` 从数据库加载节点、车辆数据
3. `DistanceService` 启动时预计算所有节点对的最短道路距离（Dijkstra）
4. `SimulatedAnnealingAlgorithm.solve()` 执行路径优化
5. 结果保存到 `vrp_task` 和 `vrp_route_detail` 表

### 算法要点

- **初始解**：按角度将配送点分配到各车辆的扇形区域，含30%重叠
- **邻域操作**：交换、移动、均衡移动三种策略
- **后处理**：顺路捎带优化（低绕行成本的节点转移）
- **目标函数**：`score = 0.35*总里程 + 0.10*聚类评分 + 0.55*均衡评分`

### DistanceService 与 NodeVO 的耦合

`DistanceService` 通过静态注入将自身设置到 `NodeVO.DistanceProvider`，使算法层可以透明使用道路距离。这是项目中的特殊设计，修改距离计算逻辑时需注意。

## 数据库

- 初始化脚本：`src/main/resources/sql/init.sql`
- 核心表：`vrp_node`（节点）、`vrp_vehicle`（车辆）、`vrp_task`（任务）、`vrp_route_detail`（路线明细）
- 道路距离表：`dazhou_delivery_routes`（达州乡镇间道路距离）

## 配置说明

- 主配置：`application.yaml`（端口、数据库、算法参数）
- 算法参数在 `VrpAlgorithmConfig` 中定义，yaml 会覆盖默认值
- `DistanceDiagnostic` 作为 CommandLineRunner，启动时自动执行连通性分析

## API 入口

- `POST /api/vrp/quick-test` - 一键测试（生成数据+计算）
- `POST /api/vrp/task` - 创建并执行VRP任务
- `GET /api/vrp/task/{taskId}` - 查询任务结果
