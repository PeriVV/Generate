package eval.cases.fixture004;

public class AnimalPolicy {
    public int fee(Animal animal, boolean weekend) {
        if (animal == null) throw new IllegalArgumentException("animal");
        int fee = animal.age() < 1 ? 5 : 10;
        if (animal instanceof Dog dog && dog.large()) fee += 8;
        if (animal instanceof Cat cat && cat.indoor()) fee -= 2;
        if (animal.age() > 12) fee += 4;
        if (weekend) fee += 6;
        return Math.max(fee, 0);
    }
}

abstract class Animal {
    private final int age;
    Animal(int age) { this.age = age; }
    int age() { return age; }
}
final class Dog extends Animal {
    private final boolean large;
    Dog(int age, boolean large) { super(age); this.large = large; }
    boolean large() { return large; }
}
final class Cat extends Animal {
    private final boolean indoor;
    Cat(int age, boolean indoor) { super(age); this.indoor = indoor; }
    boolean indoor() { return indoor; }
}
