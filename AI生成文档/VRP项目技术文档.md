# 车辆路径规划(VRP)系统技术文档

## 1. 项目概述

基于 Spring Boot 的乡镇配送路径优化系统，使用模拟退火算法求解多车辆配送路径问题。系统支持基于实际道路距离的路径计算，实现"仓库→多乡镇"的最优配送方案。

### 技术栈

- **后端框架**: Spring Boot 3.5.12 + Java 17
- **数据持久化**: MyBatis-Plus + MySQL + Druid连接池
- **核心算法**: 模拟退火算法(Simulated Annealing)
- **距离计算**: Dijkstra最短路径算法

---

## 2. 系统架构

### 2.1 分层结构

```
┌─────────────┐
│ controller/ │  REST API入口
├─────────────┤
│ service/    │  业务逻辑 + 任务管理
├─────────────┤
│ algorithm/  │  模拟退火算法实现
├─────────────┤
│ vo/         │  算法层数据模型
├─────────────┤
│ entity/     │  数据库实体
├─────────────┤
│ mapper/     │  数据访问层
└─────────────┘
```

### 2.2 核心模块职责

| 模块 | 职责 |
|------|------|
| `VrpController` | REST API入口，接收请求并返回结果 |
| `VrpServiceImpl` | 业务逻辑，任务状态管理，数据加载与保存 |
| `DistanceService` | 道路距离计算，Dijkstra预计算，距离缓存 |
| `SimulatedAnnealingAlgorithm` | 核心优化算法，路径规划求解 |
| `NodeVO` / `VehicleVO` / `SolutionVO` | 算法层数据模型 |

---

## 3. 数据模型

### 3.1 数据库表结构

#### vrp_node (节点表)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |
| name | String | 节点名称 |
| type | Integer | 类型(1=配送点, 2=仓库) |
| x | Double | X坐标(经度) |
| y | Double | Y坐标(纬度) |
| demand | Double | 需求量 |

#### vrp_vehicle (车辆表)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |
| code | String | 车辆编码 |
| capacity | Double | 载重能力 |

#### vrp_task (任务表)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |
| name | String | 任务名称 |
| status | Integer | 状态(0=待计算, 1=计算中, 2=完成, 3=失败) |
| total_distance | Double | 总里程 |
| result_json | String | 结果JSON |

#### vrp_route_detail (路线明细表)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键 |
| task_id | Long | 任务ID |
| vehicle_id | Long | 车辆ID |
| sequence | Integer | 配送顺序 |
| node_id | Long | 节点ID |
| segment_distance | Double | 段距离 |

### 3.2 VO层数据模型

#### NodeVO (节点)

```java
- id: Long
- x, y: Double (坐标)
- demand: Double (需求量)
- angle: Double (相对于仓库的角度)

方法:
- calculateAngle(depot): 计算角度
- distanceTo(other): 计算到另一节点的距离
```

#### VehicleVO (车辆)

```java
- id: Long
- capacity: Double
- route: List<NodeVO> (路线)
- sectorStart, sectorEnd: Double (扇形区域)

方法:
- addNode(node): 添加节点
- removeNode(node): 移除节点
- calculateDistance(): 计算路线总距离
- deepCopy(): 深拷贝
```

#### SolutionVO (解决方案)

```java
- vehicleRoutes: Map<VehicleVO, List<NodeVO>>

方法:
- calculateTotalDistance(): 计算总里程
- calculateClusterScore(): 计算聚类评分
- calculateBalanceScore(): 计算均衡评分
- calculateScore(): 综合评分
```

---

## 4. 核心算法

### 4.1 目标函数

```
score = w1 * 总里程 + w2 * 聚类评分 + w3 * 均衡评分
```

- **w1 = 0.10**: 总里程权重
- **w2 = 0.10**: 聚类紧凑度权重
- **w3 = 0.80**: 负载均衡权重(当前配置偏重均衡)

### 4.2 算法流程

#### 阶段一：初始解生成

1. **扇形区域划分**
   - 按360度/车辆数划分扇形区域
   - 默认40%重叠比例增加灵活性

2. **节点分配**
   - 计算每个配送点相对于仓库的角度(atan2)
   - 将节点按角度分配到对应车辆的扇形区域

3. **负载均衡**
   - 目标范围：平均值的80%-120%
   - 过载车辆向欠载车辆溢出节点

#### 阶段二：模拟退火主循环

```
温度 T = initialTemperature (100000)
while T > finalTemperature (0.01):
    随机选择两辆车 v1, v2
    随机选择邻域操作(交换/移动/均衡移动)
    生成新解
    计算 delta = newScore - oldScore

    if delta < 0:  # 更优解
        接受新解
    else:
        以概率 exp(-delta/T) 接受

    T = T * coolingRate (0.9999)
```

**邻域操作**:

1. **交换(Swap)**: 两车互换一个节点(需满足扇形区域约束)
2. **移动(Move)**: 将一个节点从某车移到另一车
3. **均衡移动(BalanceMove)**: 负载不均衡时，从高负载车向低负载车移动节点(放宽扇形限制)

#### 阶段三：顺路捎带优化

- 退火结束后执行后处理
- 检查每辆车路线，如果将其他车辆的某节点插入当前路线的绕行成本低(阈值50km或25%比例)，则转移该节点
- 最多迭代50轮

---

## 5. 距离计算

### 5.1 DistanceService

- 实现`NodeVO.DistanceProvider`接口
- 启动时从`dazhou_delivery_route`表加载道路网络
- 使用Dijkstra算法预计算所有节点对的最短路径
- 缓存到`allPairsDistance` Map中，运行时O(1)查表

### 5.2 距离模式

```java
// NodeVO.distanceTo() 逻辑
if (distanceProvider != null) {
    return distanceProvider.getDistance(this, other);  // 道路距离
} else {
    return calculateEuclideanDistance(this, other);    // 欧几里得距离
}
```

---

## 6. API接口

### 6.1 接口列表

| 方法 | 路径 | 功能 |
|------|------|------|
| POST | `/api/vrp/quick-test` | 一键测试(生成数据+初始化车辆+执行计算) |
| POST | `/api/vrp/task` | 创建并执行VRP任务 |
| POST | `/api/vrp/task/create` | 仅创建任务(不执行) |
| POST | `/api/vrp/task/{taskId}/execute` | 执行指定任务 |
| GET | `/api/vrp/task/{taskId}` | 查询任务结果 |
| GET | `/api/vrp/tasks` | 获取所有任务列表 |
| POST | `/api/vrp/data/nodes/generate` | 生成随机配送点测试数据 |
| POST | `/api/vrp/data/vehicles/init` | 初始化车辆数据 |

### 6.2 请求示例

#### 创建任务

```json
POST /api/vrp/task
{
    "taskName": "乡镇配送任务1",
    "vehicleCount": 5
}
```

#### 生成测试数据

```json
POST /api/vrp/data/nodes/generate
{
    "count": 20,
    "xMin": 104.0,
    "xMax": 108.0,
    "yMin": 30.0,
    "yMax": 33.0,
    "clearExisting": true
}
```

### 6.3 响应示例

```json
{
    "taskId": 1,
    "taskName": "乡镇配送任务1",
    "status": 2,
    "statusText": "完成",
    "totalDistance": 1256.78,
    "routes": [
        {
            "vehicleId": 1,
            "vehicleCode": "V001",
            "nodes": [
                {"sequence": 1, "nodeId": 1, "nodeName": "仓库", "segmentDistance": 0},
                {"sequence": 2, "nodeId": 5, "nodeName": "乡镇A", "segmentDistance": 45.2},
                {"sequence": 3, "nodeId": 8, "nodeName": "乡镇B", "segmentDistance": 32.1}
            ]
        }
    ]
}
```

---

## 7. 配置说明

### 7.1 application.yaml

```yaml
server:
  port: 7070

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/vrp_db
    username: root
    password: root

vrp:
  algorithm:
    initial-temperature: 100000    # 初始温度
    final-temperature: 0.01        # 终止温度
    cooling-rate: 0.9999           # 降温系数
    sector-overlap-ratio: 0.4      # 扇形重叠比例
    weight-distance: 0.10          # 里程权重
    weight-cluster: 0.10           # 聚类权重
    weight-balance: 0.80           # 均衡权重
```

### 7.2 算法参数调优建议

| 参数 | 增大效果 | 减小效果 |
|------|----------|----------|
| initial-temperature | 增加搜索范围 | 收敛更快 |
| cooling-rate | 搜索更充分(更慢) | 收敛更快(可能陷入局部最优) |
| sector-overlap-ratio | 更灵活的初始分配 | 更严格的区域划分 |
| weight-balance | 更注重负载均衡 | 更注重总里程 |

---

## 8. 数据库初始化

### 8.1 执行初始化脚本

```bash
mysql -u root -p < src/main/resources/sql/init.sql
```

### 8.2 道路距离数据

系统需要`dazhou_delivery_routes`表存储达州乡镇间道路距离数据。该表结构：

```sql
CREATE TABLE dazhou_delivery_routes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    origin_id BIGINT,
    destination_id BIGINT,
    distance DOUBLE,
    duration DOUBLE
);
```

---

## 9. 运行与测试

### 9.1 启动应用

```bash
# 开发模式
./mvnw spring-boot:run

# 打包后运行
./mvnw clean package
java -jar target/vrp-demo-0.0.1-SNAPSHOT.jar
```

### 9.2 快速测试

访问一键测试接口：

```bash
curl -X POST http://localhost:7070/api/vrp/quick-test
```

### 9.3 查看结果

```bash
# 查询任务结果
curl http://localhost:7070/api/vrp/task/1

# 查询所有任务
curl http://localhost:7070/api/vrp/tasks
```

---

## 10. 常见问题

### Q1: 启动时报数据库连接错误

检查MySQL服务是否启动，以及`application.yaml`中的数据库配置是否正确。

### Q2: 算法运行时间过长

- 减小`initial-temperature`
- 增大`cooling-rate`(如改为0.999)
- 减少配送点数量进行测试

### Q3: 路径结果不理想

- 调整权重配置，增加`weight-distance`权重
- 增加`sector-overlap-ratio`提高初始解灵活性
- 增大`initial-temperature`扩大搜索范围
