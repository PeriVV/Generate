package eval.cases.classmixed002;

public class WorkflowService {
    public String transition(String current, String event) {
        if (current == null || event == null) throw new IllegalArgumentException("state");
        if ("NEW".equals(current) && "submit".equals(event)) return "PENDING";
        if ("PENDING".equals(current) && "approve".equals(event)) return "APPROVED";
        if ("PENDING".equals(current) && "reject".equals(event)) return "REJECTED";
        return current;
    }
    public String transition(String current, boolean approved) {
        return transition(current, approved ? "approve" : "reject");
    }
    static boolean terminal(String state) {
        return "APPROVED".equals(state) || "REJECTED".equals(state);
    }
    protected String reset(String state) {
        if (!terminal(state)) throw new IllegalStateException("not terminal");
        return "NEW";
    }
}
