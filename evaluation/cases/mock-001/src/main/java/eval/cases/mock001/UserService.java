package eval.cases.mock001;

public class UserService {
    private final UserRepository repository;
    public UserService(UserRepository repository) { this.repository = repository; }
    public String name(long id) { return repository.findName(id); }
}

interface UserRepository { String findName(long id); }
