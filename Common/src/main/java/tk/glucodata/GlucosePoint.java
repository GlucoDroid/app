package tk.glucodata;

public class GlucosePoint {
    public long timestamp;
    public float value;
    public float rawValue; // Added for Raw data
    public int color; // Optional, for point coloring

    public GlucosePoint(long timestamp, float value) {
        this.timestamp = timestamp;
        this.value = value;
        this.rawValue = 0f;
    }

    public GlucosePoint(long timestamp, float value, int color) {
        this.timestamp = timestamp;
        this.value = value;
        this.color = color;
        this.rawValue = 0f;
    }

    public GlucosePoint(long timestamp, float value, float rawValue) {
        this.timestamp = timestamp;
        this.value = value;
        this.rawValue = rawValue;
    }
}
