public class Money {
    public static String format(long cents) {
        return "$" + (cents / 100) + "." + String.format("%02d", Math.abs(cents % 100));
    }
}
