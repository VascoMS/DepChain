package consensus.util;

public interface Observer<T> {
    void update(T message);
}
