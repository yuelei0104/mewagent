public class ProfileService {
    public String greeting(Account account) {
        return "Hello, " + account.getUserName();
    }
}
