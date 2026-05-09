package com.example.gaode.model;

/**
 * 乡镇信息模型
 */
public class TownshipInfo {

    private String name;
    private String adCode;
    private String district;
    private double longitude;
    private double latitude;
    private String level;

    public TownshipInfo() {}

    public TownshipInfo(String name, String adCode, String district, double longitude, double latitude, String level) {
        this.name = name;
        this.adCode = adCode;
        this.district = district;
        this.longitude = longitude;
        this.latitude = latitude;
        this.level = level;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAdCode() { return adCode; }
    public void setAdCode(String adCode) { this.adCode = adCode; }

    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    @Override
    public String toString() {
        return String.format("%s (%.6f, %.6f) [%s]", name, longitude, latitude, district);
    }
}
