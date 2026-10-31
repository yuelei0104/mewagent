public class Invoice {
    public String total(long cents) {
        return "Total: " + Money.format(cents);
    }
}
