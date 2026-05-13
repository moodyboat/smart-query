package com.smartquery.store;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 通用状态管理容器 — 直译 Claude Code store.ts
 *
 * <p>核心设计:
 * <ul>
 *   <li>不可变状态: 每次 setState 返回新对象，原对象不变</li>
 *   <li>CAS 更新: AtomicReference 保证线程安全</li>
 *   <li>订阅机制: 状态变化时通知所有监听器</li>
 *   <li>跳过无变化: Objects.equals 比较避免不必要通知</li>
 * </ul>
 *
 * <p>翻译对照:
 * <pre>
 * TS: type Store&lt;T&gt; = { getState, setState, subscribe }
 * Java: Store&lt;T&gt; with getState(), setState(), subscribe()
 *
 * TS: const state = initial; setState(updater) { const next = updater(state); if (Object.is(next, state)) return; ... }
 * Java: AtomicReference&lt;T&gt; + compareAndSet + Objects.equals
 * </pre>
 */
public class Store<T> {

    private final AtomicReference<T> stateRef;
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
    private final Consumer<Change<T>> onChange;

    public record Change<T>(T newState, T oldState) {}

    public Store(T initialState) {
        this(initialState, null);
    }

    public Store(T initialState, Consumer<Change<T>> onChange) {
        this.stateRef = new AtomicReference<>(initialState);
        this.onChange = onChange;
    }

    /**
     * 直译 store.ts: getState() => state
     */
    public T getState() {
        return stateRef.get();
    }

    /**
     * 直译 store.ts: setState(updater) => { const prev = state; const next = updater(prev); if (Object.is(next, prev)) return; state = next; ... }
     */
    public void setState(Function<T, T> updater) {
        T prev;
        T next;
        do {
            prev = stateRef.get();
            next = updater.apply(prev);
            if (java.util.Objects.equals(next, prev)) {
                return;
            }
        } while (!stateRef.compareAndSet(prev, next));

        if (onChange != null) {
            onChange.accept(new Change<>(next, prev));
        }
        for (Consumer<T> listener : listeners) {
            listener.accept(next);
        }
    }

    /**
     * 直译 store.ts: subscribe(listener) => { listeners.push(listener); return () => { listeners.splice(...) } }
     */
    public Runnable subscribe(Consumer<T> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
