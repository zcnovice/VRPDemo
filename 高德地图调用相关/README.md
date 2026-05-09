# 高德地图乡镇坐标获取工具

## 功能说明

自动获取达州市所有乡镇/街道的地图坐标，生成可导入数据库的SQL文件。

## 使用方法

### 1. 编译项目

```bash
cd 高德地图调用相关
mvn clean compile
```

### 2. 运行获取工具

```bash
mvn exec:java -Dexec.mainClass="com.example.gaode.DazhouTownshipFetcher"
```

或者直接运行 `DazhouTownshipFetcher.java` 的 main 方法。

### 3. 输出文件

运行完成后会生成两个文件：

- **达州市乡镇坐标.csv** - CSV格式，可用Excel打开
- **达州市乡镇坐标.sql** - SQL格式，可直接导入MySQL数据库

## 导入数据库

```bash
mysql -u root -p your_database < 达州市乡镇坐标.sql
```

## API说明

### 高德地图API

- **行政区域查询**: 获取指定区域的下级行政区划
- **地理编码**: 根据地址获取经纬度坐标

### API Key

当前使用的API Key: `4faf77e5e24bb544b0de6cdbcc2eac97`

如需更换，修改 `GaodeConfig.java` 中的 `API_KEY` 常量。

## 注意事项

1. 高德API免费额度为每天5000次调用
2. 请求间隔设置为200ms，避免触发限流
3. 生成的SQL文件会自动创建仓库节点（达州市中心）和配送点节点
