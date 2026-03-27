package com.example.vrpdemo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 节点VO（View Object）
 * 算法层使用的节点数据模型
 * 包含算法计算所需的坐标和角度信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NodeVO {

    /** 节点ID（对应数据库主键） */
    private Long id;

    /** 节点编码 */
    private String nodeCode;

    /** 节点名称（用于匹配道路距离表中的乡镇名称） */
    private String name;

    /** X坐标（经度） */
    private double x;

    /** Y坐标（纬度） */
    private double y;

    /** 需求量 */
    private double demand;

    /** 
     * 相对于仓库的角度（0-360度）
     * 用于扇形区域分配算法
     */
    private double angle;

    /** 距离服务（静态引用，由Spring容器初始化时注入） */
    private static DistanceProvider distanceProvider;

    /**
     * 距离提供者接口
     * 用于解耦NodeVO和Spring服务
     */
    /* 只是定义接口，不依赖于任何Spring服务  比如我会引入外部的方法要使用到Spring注解@Autowired
     * 这就让我无法在NodeVO中使用@Autowired注解，因为NodeVO是静态的，无法使用@Autowired注解，所以
     * 只是提供接口 */
    public interface DistanceProvider {
        /**
         * 获取两点之间的道路距离
         * @param nodeName1 节点1名称
         * @param nodeName2 节点2名称
         * @return 距离（公里），不可达返回Double.MAX_VALUE
         */
        double getDistance(String nodeName1, String nodeName2);

        /**
         * 检查两点之间是否可达
         */
        boolean isReachable(String nodeName1, String nodeName2);
    }

    /**
     * 设置距离提供者（由Spring容器调用）
     */
    public static void setDistanceProvider(DistanceProvider provider) {
        distanceProvider = provider;
    }

    /** 上一次距离计算的警告信息 */
    private static String lastDistanceWarning = null;

    /**
     * 获取上一次距离计算的警告信息
     */
    public static String getLastDistanceWarning() {
        return lastDistanceWarning;
    }

    /**
     * 清除警告信息
     */
    public static void clearDistanceWarning() {
        lastDistanceWarning = null;
    }

    /**
     * 计算到另一个节点的距离
     * 优先使用道路距离表中的实际距离，如果不可达则返回极大值
     * 
     * @param other 目标节点
     * @return 两点之间的道路距离（公里），不可达返回999999.0
     */
    public double distanceTo(NodeVO other) {
        // 如果有距离提供者，使用实际道路距离
        if (distanceProvider != null && this.name != null && other.name != null) {
            double distance = distanceProvider.getDistance(this.name, other.name);
            if (distance == Double.MAX_VALUE) {
                lastDistanceWarning = "节点[" + this.name + "]与节点[" + other.name + "]之间没有道路连接";
                return 999999.0; // 返回一个大数值，而不是无穷大
            }
            return distance;
        }
        
        // 降级为欧几里得距离（用于兼容旧数据或测试）
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * 检查到另一个节点是否可达
     * 
     * @param other 目标节点
     * @return true-可达，false-不可达
     */
    public boolean isReachableTo(NodeVO other) {
        if (distanceProvider != null && this.name != null && other.name != null) {
            return distanceProvider.isReachable(this.name, other.name);
        }
        return true; // 无距离提供者时默认可达
    }

    /**
     * 计算相对于仓库节点/原点的角度
     * 角度范围：0-360度
     * 用于将节点分配到扇形区域
     * 
     * @param depotX 仓库X坐标
     * @param depotY 仓库Y坐标
     * @return 角度值（0-360度）
     */
    public double calculateAngle(double depotX, double depotY) {
        // 计算相对于仓库的坐标差
        double dx = this.x - depotX;
        double dy = this.y - depotY;
        
        // 使用atan2计算角度（弧度），然后转换为度
        this.angle = Math.toDegrees(Math.atan2(dy, dx));
        
        // 将角度转换到0-360范围
        if (this.angle < 0) {
            this.angle += 360;
        }
        
        return this.angle;
    }

    /**
     * 简化构造方法（用于测试）
     */
    public NodeVO(Long id, double x, double y) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.demand = 0;
    }
}
