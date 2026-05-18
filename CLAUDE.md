# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 重要规则

- **算法代码操作**：对算法代码（algorithm/、vo/目录下的文件）进行修改、读取、新增、删除操作时，**不要**主动查看"乡镇配送项目文档"目录。只有在用户明确要求读取时才去读取分析该目录内容。

## 项目概述

基于Spring Boot 3.5.12的车辆路径规划(VRP)系统，使用模拟退火算法求解多车辆配送路径优化问题。支持基于实际道路距离的路径计算。

技术栈：Java 17、MyBatis-Plus 3.5.7、Druid连接池、MySQL（数据库名 `vrp_car`）。

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
dto/         → 请求/响应DTO（TaskCreateRequest, TaskResultResponse等）
config/      → 配置类（VrpAlgorithmConfig, MybatisPlusConfig）
sc/          → 启动诊断（DistanceDiagnostic）及独立原型（Dome/VRPDemo.java）
```

### 数据流向

1. `VrpController` 接收请求 → `VrpService` 创建任务记录
2. `VrpServiceImpl` 从数据库加载节点、车辆数据
3. `DistanceService` 启动时预计算所有节点对的最短道路距离（Dijkstra）
4. `SimulatedAnnealingAlgorithm.solve()` 执行路径优化
5. 结果保存到 `vrp_task` 和 `vrp_route_detail` 表

### 算法要点

- **初始解**：按角度将配送点分配到各车辆的扇形区域，含重叠（默认30%），然后执行负载均衡（目标gapRatio≤40%）
- **邻域操作**：交换（swap）、移动（move）、均衡移动（balanceMove）三种策略，由 `generateNeighbor` 随机选择
- **后处理**（SA主循环后依次执行）：
  1. `performDetourPickup` — 顺路捎带，低绕行成本的节点在车间转移
  2. `performBalanceRefinement` — 均衡精调，最小化最大最小里程差（目标gapRatio≤10%，总里程增幅≤10%）
- **目标函数**：归一化加权求和 `score = w1*(总里程/refDist) + w2*(聚类/refCluster) + w3*(均衡/refBalance) + w4*(极差比/refGapRatio)`，参考值从初始解或配置获取
- **评分指标**：`SolutionVO.calculateMetrics()` 计算四项指标：totalDistance、clusterScore、balanceScore、gapRatioScore

### DistanceService 与 NodeVO 的耦合

`DistanceService` 通过静态注入将自身设置到 `NodeVO.DistanceProvider`，使算法层可以透明使用道路距离。这是项目中的特殊设计，修改距离计算逻辑时需注意。

## 数据库

- 初始化脚本：`src/main/resources/sql/init.sql`（注意：脚本创建 `vrp_db`，但 application.yaml 连接的是 `vrp_car`）
- 核心表：`vrp_node`（节点，nodeType: 1=配送点, 2=仓库）、`vrp_vehicle`（车辆）、`vrp_task`（任务，status: 0待处理/1计算中/2完成/3失败）、`vrp_route_detail`（路线明细）
- 道路距离表：`dazhou_delivery_routes`（达州乡镇间道路距离，含经纬度和公里数）
- 所有 Mapper 均使用注解方式（`@Select`/`@Delete`），无 XML 映射文件

## 配置说明

- 主配置：`application.yaml`（端口、数据库、算法参数）
- 算法参数在 `VrpAlgorithmConfig` 中定义（`@ConfigurationProperties(prefix = "vrp.algorithm")`），yaml 会覆盖 Java 默认值
- 主要算法配置项：`initial-temperature`、`final-temperature`、`cooling-rate`、`weight-distance`、`weight-cluster`、`weight-balance`、`weight-gap-ratio`、`overlap-ratio`、`ref-total-distance`、`ref-cluster`、`ref-balance`、`log-interval`
- `DistanceDiagnostic` 作为 CommandLineRunner，启动时自动执行连通性分析
- 注意：同时存在 `application.yaml` 和 `application.yml`，Spring Boot 优先加载 `.yaml`；`.yml` 中有 `mapper-locations` 配置

## API 入口

- `POST /api/vrp/task` - 创建并执行VRP任务
- `POST /api/vrp/task/create` - 仅创建任务（不执行）
- `POST /api/vrp/task/{taskId}/execute` - 执行已创建的任务
- `GET /api/vrp/task/{taskId}` - 查询任务结果
- `GET /api/vrp/tasks` - 查询所有任务
- `POST /api/vrp/data/nodes/generate` - 生成测试配送节点
- `POST /api/vrp/data/vehicles/init` - 初始化车辆（默认20辆）
- `GET /api/vrp/distance?startNode=X&endNode=Y` - 查询两节点间最短道路距离
- `POST /api/vrp/quick-test?nodeCount=100&vehicleCount=10` - 一键测试（生成数据+计算+返回结果）
