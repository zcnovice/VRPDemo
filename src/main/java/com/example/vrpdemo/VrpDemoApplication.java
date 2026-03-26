package com.example.vrpdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * VRP Demo 应用程序启动类
 * 
 * 该项目实现了基于模拟退火算法的车辆路径问题(VRP)求解器
 * 主要功能：
 * 1. 从数据库加载配送点和车辆数据
 * 2. 使用模拟退火算法计算最优配送路线
 * 3. 将计算结果保存到数据库
 * 
 * @author VRP Demo
 */
@SpringBootApplication
public class VrpDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(VrpDemoApplication.class, args);
        System.out.println("========================================");
        System.out.println("  VRP Demo 服务启动成功！");
        System.out.println("  API文档: http://localhost:8080/api/vrp");
        System.out.println("========================================");
    }
}
