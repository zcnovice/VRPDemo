package com.example.gaode.config;

/**
 * 高德地图API配置
 */
public class GaodeConfig {

    /** 高德API Key */
    public static final String API_KEY = "4faf77e5e24bb544b0de6cdbcc2eac97";

    /** 行政区域查询API */
    public static final String DISTRICT_URL = "https://restapi.amap.com/v3/config/district";

    /** 地理编码API */
    public static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";

    /** 驾车路线规划API */
    public static final String DRIVING_URL = "https://restapi.amap.com/v3/direction/driving";

    /** 达州市行政区划代码 */
    public static final String DAZHOU_AD_CODE = "511700";

    /** 请求间隔（毫秒），避免触发限流 */
    public static final long REQUEST_INTERVAL = 200;
}
