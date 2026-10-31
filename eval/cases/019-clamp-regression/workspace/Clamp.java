public class Clamp {
    public static int clamp(int value, int lo, int hi) {
        if (value < lo) {
            return hi;
        }
        if (value > hi) {
            return hi;
        }
        return value;
    }
}
