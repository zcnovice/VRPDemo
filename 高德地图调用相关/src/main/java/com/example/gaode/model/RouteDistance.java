package com.example.gaode.model;

/**
 * 乡镇间道路距离模型
 */
public class RouteDistance {

    private String startNode;
    private double startLng;
    private double startLat;
    private String endNode;
    private double endLng;
    private double endLat;
    private double distanceKm;

    public RouteDistance() {}

    public RouteDistance(String startNode, double startLng, double startLat,
                         String endNode, double endLng, double endLat, double distanceKm) {
        this.startNode = startNode;
        this.startLng = startLng;
        this.startLat = startLat;
        this.endNode = endNode;
        this.endLng = endLng;
        this.endLat = endLat;
        this.distanceKm = distanceKm;
    }

    public String getStartNode() { return startNode; }
    public void setStartNode(String startNode) { this.startNode = startNode; }

    public double getStartLng() { return startLng; }
    public void setStartLng(double startLng) { this.startLng = startLng; }

    public double getStartLat() { return startLat; }
    public void setStartLat(double startLat) { this.startLat = startLat; }

    public String getEndNode() { return endNode; }
    public void setEndNode(String endNode) { this.endNode = endNode; }

    public double getEndLng() { return endLng; }
    public void setEndLng(double endLng) { this.endLng = endLng; }

    public double getEndLat() { return endLat; }
    public void setEndLat(double endLat) { this.endLat = endLat; }

    public double getDistanceKm() { return distanceKm; }
    public void setDistanceKm(double distanceKm) { this.distanceKm = distanceKm; }

    @Override
    public String toString() {
        return String.format("%s -> %s: %.1f km", startNode, endNode, distanceKm);
    }
}
