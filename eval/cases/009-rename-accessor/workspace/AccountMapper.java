public class AccountMapper {
    public String toRow(Account account) {
        return "name=" + account.getUserName();
    }
}
