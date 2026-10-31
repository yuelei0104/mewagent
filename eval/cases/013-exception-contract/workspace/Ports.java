public class Ports {
    public static int parse(String text) {
        int port = Integer.parseInt(text);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + text);
        }
        return port;
    }
}
