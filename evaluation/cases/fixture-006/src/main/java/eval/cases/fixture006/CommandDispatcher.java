package eval.cases.fixture006;

public class CommandDispatcher {
    public String dispatch(Command command, Context context) {
        if (command == null || context == null) throw new IllegalArgumentException("input");
        if (!context.active()) return "INACTIVE";
        if (command instanceof Create create) {
            if (create.name() == null || create.name().isBlank()) return "INVALID";
            return context.admin() ? "CREATED_ADMIN" : "CREATED";
        }
        if (command instanceof Delete delete) {
            if (delete.id() <= 0) return "INVALID";
            if (!context.admin()) return "FORBIDDEN";
            return delete.force() ? "DELETED_FORCE" : "DELETED";
        }
        return "UNKNOWN";
    }
}

sealed interface Command permits Create, Delete {}
record Create(String name) implements Command {}
record Delete(long id, boolean force) implements Command {}
record Context(boolean active, boolean admin) {}
